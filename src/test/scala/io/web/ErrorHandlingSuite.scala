package io.web

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.{Allow, `Access-Control-Allow-Origin`}
import akka.http.scaladsl.server.*
import io.web.testing.JsonTestSupport.*
import spray.json.*

/** The mapping functions of ErrorHandling (#123), without routes: rejection / exception → answer. */
class ErrorHandlingSuite extends munit.FunSuite {

    private def body(response: HttpResponse): JsValue = response.entity match {
        case strict: HttpEntity.Strict =>
            assertEquals(strict.contentType, ContentTypes.`application/json`)
            parseAsClient(strict.data.utf8String)
        case other => fail(s"not a strict entity: $other")
    }

    /** Status, `error` code and `fields` of an answer; checks the shape and the CORS headers too. */
    private def answer(response: HttpResponse): (Int, String, Option[Vector[JsValue]]) = {
        val json = body(response)
        assert(json.asJsObject.fields.keySet.subsetOf(Set("error", "message", "fields")), json.compactPrint)
        assert(str(json, "message").nonEmpty)
        assertEquals(response.headers.count(_.is("access-control-allow-origin")), 1, "CORS header once")
        assert(response.headers.exists(_.is("access-control-allow-methods")))
        (response.status.intValue, str(json, "error"), json.asJsObject.fields.get("fields").map {
            case JsArray(items) => items
            case other          => fail(s"fields is not an array: $other")
        })
    }

    private def fieldNames(fields: Option[Vector[JsValue]]): Vector[String] =
        fields.getOrElse(Vector.empty).map(f => str(f, "field"))

    private val preflight = MethodRejection(HttpMethods.OPTIONS)

    test("no rejection, or only the global preflight's OPTIONS rejection: 404 not_found (was 405)") {
        assertEquals(answer(ErrorHandling.rejectionResponse(Nil)).take2, (404, "not_found"))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(preflight))).take2, (404, "not_found"))
        assertEquals(answer(ErrorHandling.notFoundResponse).take2, (404, "not_found"))
    }

    test("a real method mismatch: 405 method_not_allowed with Allow (OPTIONS ignored as a reason)") {
        val response = ErrorHandling.rejectionResponse(List(preflight, MethodRejection(HttpMethods.GET), MethodRejection(HttpMethods.POST)))
        assertEquals(answer(response).take2, (405, "method_not_allowed"))
        assertEquals(response.header[Allow].map(_.methods.toSet), Some(Set(HttpMethods.GET, HttpMethods.POST, HttpMethods.OPTIONS)))
        assert(str(body(response), "message").contains("GET, POST"))
        assert(!str(body(response), "message").contains("OPTIONS"))
    }

    test("cancelled method rejections (a branch matched the method) do not produce a 405") {
        val cancel = TransformationRejection(_.filterNot(_.isInstanceOf[MethodRejection]))
        val response = ErrorHandling.rejectionResponse(List(preflight, cancel,
            MalformedQueryParamRejection("limit", "'x' is not a valid 32-bit signed integer value")))
        val (status, code, fields) = answer(response)
        assertEquals((status, code), (400, "invalid_body"))
        assertEquals(fieldNames(fields), Vector("limit"))
    }

    test("query parameters: malformed / missing / invalid → 400 invalid_body naming the parameter") {
        List(
            MalformedQueryParamRejection("agentOffset", "'-' is not a valid 32-bit signed integer value"),
            MissingQueryParamRejection("round"),
            InvalidRequiredValueForQueryParamRejection("mode", "a", "b")
        ).zip(List("agentOffset", "round", "mode")).foreach { case (rejection, name) =>
            val (status, code, fields) = answer(ErrorHandling.rejectionResponse(List(rejection)))
            assertEquals((status, code), (400, "invalid_body"))
            assertEquals(fieldNames(fields), Vector(name))
        }
    }

    test("request body: deserialization error with its JSON path, syntax error, missing body, validation") {
        val deser = DeserializationException("Expected Int as JsNumber, but got \"abc\"", null, List("agentTypes", "count"))
        val (s1, c1, f1) = answer(ErrorHandling.rejectionResponse(List(MalformedRequestContentRejection(deser.msg, deser))))
        assertEquals((s1, c1), (400, "invalid_body"))
        assertEquals(fieldNames(f1), Vector("agentTypes.count"))
        assertEquals(f1.get.map(f => str(f, "issue")), Vector("Expected Int as JsNumber, but got \"abc\""))

        val syntax = scala.util.Try(JsonParser("""{"name": """)).failed.get
        val response = ErrorHandling.rejectionResponse(List(MalformedRequestContentRejection(syntax.getMessage, syntax)))
        val (s2, c2, f2) = answer(response)
        assertEquals((s2, c2, f2), (400, "invalid_body", None))
        assert(str(body(response), "message").startsWith("Malformed JSON: "))
        assert(!str(body(response), "message").contains("\n"), "the parser's input snippet is not echoed")

        assertEquals(answer(ErrorHandling.rejectionResponse(List(RequestEntityExpectedRejection))).take2, (400, "invalid_body"))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(ValidationRejection("bad value")))).take2, (400, "invalid_body"))
        val whole = DeserializationException("Object expected", null, Nil)
        assertEquals(answer(ErrorHandling.rejectionResponse(List(MalformedRequestContentRejection("x", whole)))),
            (400, "invalid_body", None), "no member known: no fields")
    }

    test("body over the size limit: 413 payload_too_large, as a rejection or as an exception") {
        val tooLarge = EntityStreamSizeException(limit = 1024, actualSize = Some(4096))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(MalformedRequestContentRejection(tooLarge.getMessage, tooLarge)))).take2,
            (413, "payload_too_large"))
        assertEquals(answer(ErrorHandling.exceptionResponse(tooLarge)).take2, (413, "payload_too_large"))
    }

    test("content negotiation and protocol rejections") {
        val unsupported = UnsupportedRequestContentTypeRejection(Set(ContentTypeRange(ContentTypes.`application/json`)),
            Some(ContentTypes.`text/plain(UTF-8)`))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(unsupported))).take2, (415, "unsupported_media_type"))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(UnacceptedResponseContentTypeRejection(Set.empty)))).take2,
            (406, "not_acceptable"))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(ExpectedWebSocketRequestRejection))).take2, (400, "bad_request"))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(MissingHeaderRejection("X-Foo")))).take2, (400, "bad_request"))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(AuthorizationFailedRejection))).take2, (403, "forbidden"))
        assertEquals(answer(ErrorHandling.rejectionResponse(List(SchemeRejection("https")))).take2, (400, "bad_request"))
    }

    test("uncaught exceptions: 500 internal_error without the exception's message or class") {
        val secret = new IllegalStateException("password=hunter2 at io.db.DatabaseManager")
        val response = ErrorHandling.exceptionResponse(secret)
        assertEquals(answer(response).take2, (500, "internal_error"))
        val text = response.entity.asInstanceOf[HttpEntity.Strict].data.utf8String
        List("hunter2", "IllegalStateException", "DatabaseManager", "at ").foreach { leak =>
            assert(!text.contains(leak), s"leaks '$leak': $text")
        }
    }

    test("the request-timeout answer is JSON with CORS too") {
        assertEquals(answer(ErrorHandling.requestTimeoutResponse).take2, (503, "request_timeout"))
    }

    extension (a: (Int, String, Option[Vector[JsValue]])) def take2: (Int, String) = (a._1, a._2)
}
