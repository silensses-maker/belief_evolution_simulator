package io.web

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.{Allow, RawHeader}
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.testkit.{RouteTest, TestFrameworkInterface, WSProbe}
import akka.stream.scaladsl.Flow
import io.web.auth.ErrorResponses.{errorJson, usageLimitJson}
import io.web.testing.JsonTestSupport.*
import spray.json.*

import scala.concurrent.Future

final case class ItemInner(count: Int)
final case class Item(name: String, inner: ItemInner)

object ItemProtocol extends DefaultJsonProtocol {
    implicit val innerFormat: RootJsonFormat[ItemInner] = jsonFormat1(ItemInner.apply)
    implicit val itemFormat: RootJsonFormat[Item]       = jsonFormat2(Item.apply)
}

/**
 * ErrorHandling (#123) end to end on a route tree shaped like Server's: the global CORS preflight
 * first, every route inside `Cors.addCorsHeaders`, the dual mount (`/` and `/api`) and the global
 * handlers around both. Akka produces the real rejections (method, path, entity unmarshalling,
 * size limit, query parameters, WebSocket upgrade), so this checks what the client receives.
 */
class ErrorHandlingRouteSuite extends munit.FunSuite with RouteTest with TestFrameworkInterface {
    import ItemProtocol.*

    override def failTest(msg: String): Nothing = throw new AssertionError(msg)
    override def testExceptionHandler: ExceptionHandler = ExceptionHandler { case e: AssertionError => throw e }
    override def afterAll(): Unit = { cleanUp(); super.afterAll() }

    private val base: Route = Cors.preflight ~ Cors.addCorsHeaders {
        path("items") {
            post { entity(as[Item]) { item => complete(item) } } ~
            get { complete(HttpEntity(ContentTypes.`application/json`, "[]")) }
        } ~
        path("small") { post { withSizeLimit(64) { entity(as[Item]) { item => complete(item) } } } } ~
        path("page") { get { parameters("limit".as[Int].withDefault(20)) { limit => complete(limit.toString) } } } ~
        path("boom") { get { (throw new IllegalStateException("secret internal detail")): Route } } ~
        path("boom-async") { get { complete(Future.failed[String](new RuntimeException("async secret"))) } } ~
        path("ws") { get { handleWebSocketMessages(Flow[Message]) } } ~
        path("gone") {
            complete(HttpResponse(StatusCodes.Gone, headers = List(RawHeader("Link", "<x>; rel=\"successor-version\"")),
                entity = errorJson("gone", "This legacy endpoint was removed")))
        } ~
        path("limited") { complete(HttpResponse(StatusCodes.UnprocessableContent, entity = usageLimitJson("Agent count exceeds your limit", 10, 50))) } ~
        path("deactivated") { complete(StatusCodes.Unauthorized -> errorJson("unauthorized", "Account is deactivated")) }
    }

    private val routes: Route = ErrorHandling.handleErrors { base ~ pathPrefix("api")(base) }

    private def json(entity: String): HttpEntity.Strict = HttpEntity(ContentTypes.`application/json`, entity)

    /** The answer must be our JSON error with exactly one CORS origin header; returns its body. */
    private def jsonError(expectedStatus: Int, expectedCode: String): JsValue = {
        assertEquals(status.intValue, expectedStatus, responseAs[String])
        assertEquals(contentType, ContentTypes.`application/json`)
        assertEquals(headers.count(_.is("access-control-allow-origin")), 1, s"CORS origin header once: $headers")
        val body = parseAsClient(responseAs[String])
        assertEquals(str(body, "error"), expectedCode)
        assert(str(body, "message").nonEmpty)
        body
    }

    test("unknown path → 404 not_found JSON with CORS, on both mounts (was 405 'supported methods: OPTIONS')") {
        List("/nope", "/api/nope", "/items/extra", "/api/api/items").foreach { path =>
            Get(path) ~> routes ~> check { jsonError(404, "not_found") }
            Post(path, json("{}")) ~> routes ~> check { jsonError(404, "not_found") }
        }
    }

    test("CORS preflight unchanged: OPTIONS on any path → 200 with the CORS headers") {
        List("/items", "/nope", "/api/ws").foreach { path =>
            Options(path) ~> routes ~> check {
                assertEquals(status, StatusCodes.OK)
                assertEquals(header("Access-Control-Allow-Origin").map(_.value), Some("*"))
                assert(header("Access-Control-Allow-Methods").exists(_.value.contains("DELETE")))
            }
        }
    }

    test("wrong method on a known path → 405 method_not_allowed with Allow") {
        Delete("/items") ~> routes ~> check {
            jsonError(405, "method_not_allowed")
            assertEquals(header[Allow].map(_.methods.toSet), Some(Set(HttpMethods.GET, HttpMethods.POST, HttpMethods.OPTIONS)))
        }
        Put("/api/page") ~> routes ~> check { jsonError(405, "method_not_allowed") }
    }

    test("malformed JSON → 400 invalid_body") {
        Post("/items", json("""{"name": "x", """)) ~> routes ~> check {
            val body = jsonError(400, "invalid_body")
            assert(str(body, "message").startsWith("Malformed JSON"), body.compactPrint)
            assert(!body.asJsObject.fields.contains("fields"))
        }
    }

    test("invalid JSON body → 400 invalid_body with the failing member in fields") {
        Post("/items", json("""{"name": "x"}""")) ~> routes ~> check {
            val body = jsonError(400, "invalid_body")
            assertEquals(array(body, "fields").map(f => str(f, "field")), Vector("inner"))
            assert(str(array(body, "fields").head, "issue").contains("missing required member 'inner'"))
        }
        Post("/api/items", json("""{"name": "x", "inner": {"count": "many"}}""")) ~> routes ~> check {
            val body = jsonError(400, "invalid_body")
            assertEquals(array(body, "fields").map(f => str(f, "field")), Vector("inner.count"))
        }
    }

    test("missing body → 400 invalid_body; other Content-Type → 415 unsupported_media_type") {
        Post("/items", json("")) ~> routes ~> check { jsonError(400, "invalid_body") }
        Post("/items", HttpEntity(ContentTypes.`text/plain(UTF-8)`, """{"name":"x","inner":{"count":1}}""")) ~> routes ~> check {
            val body = jsonError(415, "unsupported_media_type")
            assert(str(body, "message").contains("application/json"), body.compactPrint)
        }
    }

    test("body over the size limit → 413 payload_too_large") {
        val big = s"""{"name": "${"x".repeat(500)}", "inner": {"count": 1}}"""
        Post("/small", json(big)) ~> routes ~> check { jsonError(413, "payload_too_large") }
    }

    test("malformed query parameter → 400 invalid_body naming it") {
        Get("/page?limit=abc") ~> routes ~> check {
            val body = jsonError(400, "invalid_body")
            assertEquals(array(body, "fields").map(f => str(f, "field")), Vector("limit"))
        }
        Get("/page?limit=7") ~> routes ~> check { assertEquals(responseAs[String], "7") }
    }

    test("uncaught exception (thrown or failed future) → 500 internal_error, nothing leaked") {
        List("/boom", "/boom-async", "/api/boom").foreach { path =>
            Get(path) ~> routes ~> check {
                jsonError(500, "internal_error")
                val text = responseAs[String]
                List("secret", "IllegalStateException", "RuntimeException", "at ").foreach { leak =>
                    assert(!text.contains(leak), s"leaks '$leak': $text")
                }
            }
        }
    }

    test("GET /ws without an upgrade → 400 bad_request; the WebSocket upgrade still works on both mounts") {
        Get("/ws") ~> routes ~> check { jsonError(400, "bad_request") }
        List("/ws", "/api/ws").foreach { path =>
            val client = WSProbe()
            WS(path, client.flow) ~> routes ~> check {
                assert(isWebSocketUpgrade, s"$path upgraded")
                client.sendMessage("ping")
                client.expectMessage("ping")
                client.sendCompletion()
                client.expectCompletion()
            }
        }
    }

    test("answers completed by routes pass through untouched (410 gone, 422 usage limit, 401 deactivated, 200)") {
        Get("/gone") ~> routes ~> check {
            assertEquals(status, StatusCodes.Gone)
            assertEquals(responseAs[String], """{"error":"gone","message":"This legacy endpoint was removed"}""")
            assert(header("Link").isDefined)
            assertEquals(headers.count(_.is("access-control-allow-origin")), 1)
        }
        Post("/api/limited") ~> routes ~> check {
            assertEquals(status, StatusCodes.UnprocessableContent)
            assertEquals(responseAs[String],
                """{"error":"usage_limit_exceeded","limit":10,"message":"Agent count exceeds your limit","requested":50}""")
        }
        Get("/deactivated") ~> routes ~> check {
            assertEquals(status, StatusCodes.Unauthorized)
            assertEquals(responseAs[String], """{"error":"unauthorized","message":"Account is deactivated"}""")
        }
        Post("/items", json("""{"name": "a \"q\" \\ b", "inner": {"count": 2}}""")) ~> routes ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(headers.count(_.is("access-control-allow-origin")), 1, "no duplicated CORS on success")
            assertEquals(str(parseAsClient(responseAs[String]), "name"), "a \"q\" \\ b")
        }
    }
}
