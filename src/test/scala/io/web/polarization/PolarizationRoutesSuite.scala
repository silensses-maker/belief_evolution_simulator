package io.web.polarization

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.testkit.{RouteTest, RouteTestTimeout, TestFrameworkInterface}
import io.web.auth.ErrorResponses.errorJson
import io.web.auth.{AuthenticatedUser, FieldIssue}
import io.web.testing.JsonTestSupport.*
import io.web.{Cors, ErrorHandling}
import spray.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * #129: the polarization endpoints end to end on a route tree shaped like Server's (CORS preflight,
 * CORS on every route, dual mount, global JSON error handlers), with a stub authenticator (a fixed
 * user for `Bearer good`, the real directive's 401 otherwise) and a stub backend.
 */
class PolarizationRoutesSuite extends munit.FunSuite with RouteTest with TestFrameworkInterface {

    override def failTest(msg: String): Nothing = throw new AssertionError(msg)
    override def testExceptionHandler: ExceptionHandler = ExceptionHandler { case e: AssertionError => throw e }
    override def afterAll(): Unit = { cleanUp(); super.afterAll() }
    implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(30.seconds)

    private val user = AuthenticatedUser("uid-1", "a@b.c", "A", None, Seq("Researcher"), 7)

    private val stubAuthenticate: Directive1[AuthenticatedUser] =
        optionalHeaderValueByName("Authorization").flatMap {
            case Some("Bearer good") => provide(user)
            case _ => complete(StatusCodes.Unauthorized -> errorJson("unauthorized", "Missing Bearer token"))
        }

    private val catalogJson = JsObject("measures" -> JsArray(JsObject("id" -> JsString("MEC"))))
    private val computeJson = JsObject(
        "values" -> JsArray(JsNumber(0.25), JsNull),
        "levels" -> JsArray(JsNull, JsNull),
        "errors" -> JsArray(JsNull, JsString("ValueError: At least one weight must be positive")),
        "package" -> JsObject("ref" -> JsString("0e05564"), "version" -> JsString("0.1.0a2")))

    /** Answers by measure name; records the last request it received. */
    private final class StubBackend extends PolarizationBackend {
        val received = new AtomicReference[PolarizationRequest](null)
        def catalog(): Future[Either[PolarizationFailure, JsObject]] = Future.successful(Right(catalogJson))
        def compute(request: PolarizationRequest): Future[Either[PolarizationFailure, JsObject]] = {
            received.set(request)
            Future.successful(request.measure match {
                case "unknown"  => Left(PolarizationFailure.Rejected("invalid_measure", "Unknown measure: 'unknown'",
                                       List(FieldIssue("measure", "must be one of MEC"))))
                case "badx"     => Left(PolarizationFailure.Rejected("invalid_request", "x must be strictly increasing",
                                       List(FieldIssue("x", "x"))))
                case "bug"      => Left(PolarizationFailure.Rejected("internal_error", "KeyError: secret detail", Nil))
                case "down"     => Left(PolarizationFailure.Unavailable("The polarization worker did not answer within 30 s"))
                case _          => Right(computeJson)
            })
        }
    }

    private def routesWith(backend: Option[PolarizationBackend], maxBodyBytes: Long = PolarizationRequest.MaxBodyBytes): Route = {
        val base: Route = Cors.preflight ~ Cors.addCorsHeaders {
            PolarizationRoutes(stubAuthenticate, backend, maxBodyBytes) ~
            path("simulations" / LongNumber) { id => get { complete(s"run $id") } } // a sibling route
        }
        ErrorHandling.handleErrors { base ~ pathPrefix("api")(base) }
    }

    private val stub = new StubBackend
    private val routes = routesWith(Some(stub))
    private val auth = RawHeader("Authorization", "Bearer good")
    private def json(text: String): HttpEntity.Strict = HttpEntity(ContentTypes.`application/json`, text)
    private val validBody = """{"measure": "MEC", "params": {"alpha": 2}, "k": 3, "histograms": [[1, 0, 1], [0, 0, 0]]}"""

    private def jsonError(expectedStatus: Int, expectedCode: String): JsValue = {
        assertEquals(status.intValue, expectedStatus, responseAs[String])
        assertEquals(contentType, ContentTypes.`application/json`)
        assertEquals(headers.count(_.is("access-control-allow-origin")), 1, s"CORS origin header once: $headers")
        val body = parseAsClient(responseAs[String])
        assertEquals(str(body, "error"), expectedCode)
        assert(str(body, "message").nonEmpty)
        body
    }

    test("no or invalid token → 401 JSON on both endpoints and both mounts (before the body is read)") {
        List("/simulations/polarization/measures", "/api/simulations/polarization/measures").foreach { path =>
            Get(path) ~> routes ~> check { jsonError(401, "unauthorized") }
            Get(path) ~> addHeader(RawHeader("Authorization", "Bearer bad")) ~> routes ~> check { jsonError(401, "unauthorized") }
        }
        List("/simulations/polarization", "/api/simulations/polarization").foreach { path =>
            Post(path, json("not even json")) ~> routes ~> check { jsonError(401, "unauthorized") }
        }
    }

    test("GET …/measures → 200 with the backend's catalog, on both mounts") {
        List("/simulations/polarization/measures", "/api/simulations/polarization/measures").foreach { path =>
            Get(path) ~> addHeader(auth) ~> routes ~> check {
                assertEquals(status, StatusCodes.OK)
                assertEquals(contentType, ContentTypes.`application/json`)
                assertEquals(parseAsClient(responseAs[String]), catalogJson)
                assertEquals(headers.count(_.is("access-control-allow-origin")), 1)
            }
        }
    }

    test("POST → 200 with the worker's result; the backend got the parsed body") {
        List("/simulations/polarization", "/api/simulations/polarization", "/simulations/polarization/").foreach { path =>
            Post(path, json(validBody)) ~> addHeader(auth) ~> routes ~> check {
                assertEquals(status, StatusCodes.OK, responseAs[String])
                assertEquals(parseAsClient(responseAs[String]), computeJson)
            }
        }
        val got = stub.received.get()
        assertEquals((got.measure, got.k, got.rounds), ("MEC", 3, 2))
        assertEquals(got.params, JsObject("alpha" -> JsNumber(2)))
    }

    test("invalid bodies → 400 invalid_body naming the member; the backend is not called") {
        stub.received.set(null)
        Post("/simulations/polarization", json("""{"measure": "MEC", "k": 11, "histograms": [[1]]}""")) ~> addHeader(auth) ~> routes ~> check {
            val body = jsonError(400, "invalid_body")
            assertEquals(array(body, "fields").map(f => str(f, "field")), Vector("k"))
        }
        Post("/simulations/polarization", json("""{"measure": "MEC", "k": 3, "histograms": [[1, 2, -1]]}""")) ~> addHeader(auth) ~> routes ~> check {
            val body = jsonError(400, "invalid_body")
            assertEquals(array(body, "fields").map(f => str(f, "field")), Vector("histograms[0][2]"))
        }
        Post("/simulations/polarization", json("""{"measure": """)) ~> addHeader(auth) ~> routes ~> check {
            assert(str(jsonError(400, "invalid_body"), "message").startsWith("Malformed JSON"))
        }
        Post("/simulations/polarization", json("")) ~> addHeader(auth) ~> routes ~> check { jsonError(400, "invalid_body") }
        Post("/simulations/polarization", HttpEntity(ContentTypes.`text/plain(UTF-8)`, validBody)) ~> addHeader(auth) ~> routes ~> check {
            jsonError(415, "unsupported_media_type")
        }
        assertEquals(stub.received.get(), null)
    }

    test("body over the endpoint's size limit → 413 payload_too_large") {
        val small = routesWith(Some(stub), maxBodyBytes = 64)
        Post("/simulations/polarization", json(validBody + " ".repeat(100))) ~> addHeader(auth) ~> small ~> check {
            jsonError(413, "payload_too_large")
        }
    }

    test("worker answers: invalid_measure → 422 (fields kept), invalid_request → 400, unavailable → 503, other → 500 without detail") {
        def post(measure: String): RouteTestResult =
            Post("/simulations/polarization", json(s"""{"measure": "$measure", "k": 3, "histograms": [[1, 0, 1]]}""")) ~>
                addHeader(auth) ~> routes
        post("unknown") ~> check {
            val body = jsonError(422, "invalid_measure")
            assertEquals(array(body, "fields").map(f => str(f, "field")), Vector("measure"))
        }
        post("badx") ~> check { jsonError(400, "invalid_body") }
        post("down") ~> check { assert(str(jsonError(503, "unavailable"), "message").contains("did not answer")) }
        post("bug") ~> check {
            jsonError(500, "internal_error")
            assert(!responseAs[String].contains("secret"), responseAs[String])
        }
    }

    test("other methods → 405; other paths under /simulations unaffected") {
        Put("/simulations/polarization", json(validBody)) ~> addHeader(auth) ~> routes ~> check { jsonError(405, "method_not_allowed") }
        Post("/simulations/polarization/measures", json("{}")) ~> addHeader(auth) ~> routes ~> check { jsonError(405, "method_not_allowed") }
        Get("/simulations/polarization/nope") ~> addHeader(auth) ~> routes ~> check { jsonError(404, "not_found") }
        Get("/api/simulations/42") ~> routes ~> check { assertEquals(responseAs[String], "run 42") }
    }

    test("APP_POLARIZATION=false (no backend): 404 not_found on both paths, any method, with or without a token") {
        val off = routesWith(None)
        List("/simulations/polarization", "/api/simulations/polarization/measures", "/simulations/polarization/").foreach { path =>
            Get(path) ~> off ~> check {
                assert(str(jsonError(404, "not_found"), "message").contains("APP_POLARIZATION=false"))
            }
            Post(path, json(validBody)) ~> addHeader(auth) ~> off ~> check { jsonError(404, "not_found") }
        }
        Options("/simulations/polarization") ~> off ~> check { assertEquals(status, StatusCodes.OK) }
    }

    test("with the real PolarizationService and a fake worker: catalog and compute pass through") {
        val script = Files.createTempFile("fake-polarization-route-worker", ".sh")
        Files.writeString(script,
            """#!/bin/sh
              |while IFS= read -r line; do
              |  id=$(printf '%s\n' "$line" | sed -n 's/^{"id":\([0-9]*\).*/\1/p')
              |  case "$line" in
              |    *'"op":"version"'*) printf '{"id":%s,"ok":true,"result":{"package":{"version":"f","commit":"c"}}}\n' "$id" ;;
              |    *'"op":"catalog"'*) printf '{"id":%s,"ok":true,"result":{"measures":[]}}\n' "$id" ;;
              |    *) printf '{"id":%s,"ok":true,"result":{"values":[0.5,null],"levels":["low",null],"package":{"ref":"c","version":"f"}}}\n' "$id" ;;
              |  esac
              |done
              |""".stripMargin, UTF_8)
        script.toFile.deleteOnExit()
        val service = new PolarizationService(Seq("/bin/sh", script.toString), 10.seconds, stderrLine = (_, _) => ())
        try {
            val real = routesWith(Some(service))
            Get("/api/simulations/polarization/measures") ~> addHeader(auth) ~> real ~> check {
                assertEquals(status, StatusCodes.OK, responseAs[String])
                val body = parseAsClient(responseAs[String])
                assertEquals(int(field(body, "limits"), "maxRounds"), PolarizationRequest.MaxRounds)
            }
            Post("/simulations/polarization", json(validBody)) ~> addHeader(auth) ~> real ~> check {
                assertEquals(status, StatusCodes.OK, responseAs[String])
                assertEquals(array(parseAsClient(responseAs[String]), "levels"), Vector(JsString("low"), JsNull))
            }
        } finally service.close()
    }
}
