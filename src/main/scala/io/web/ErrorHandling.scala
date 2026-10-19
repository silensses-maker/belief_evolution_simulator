package io.web

import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.Allow
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.Directives.*
import io.web.auth.ErrorResponses.errorJson
import io.web.auth.FieldIssue
import spray.json.{DeserializationException, JsonParser}
import utils.logging.Logger

import scala.util.control.NonFatal

/**
 * Global error answers of the HTTP API (#123). [[handleErrors]] wraps the whole route tree (both
 * mounts, `/` and `/api`), so every rejection and every uncaught exception becomes
 * `{"error": code, "message": message[, "fields": [{"field", "issue"}]]}` with the CORS headers,
 * instead of Akka's plain-text defaults without CORS. Answers that routes complete themselves
 * (401 `unauthorized` / `token_revoked`, 403 `forbidden`, 410 `gone`, 422 `usage_limit_exceeded`,
 * ...) are untouched: handlers only see rejections and exceptions.
 *
 * | Rejection / exception                                       | Status | `error`                  |
 * |-------------------------------------------------------------|--------|--------------------------|
 * | no route matched (only the preflight's OPTIONS rejection)    | 404    | `not_found`              |
 * | `MethodRejection` (path exists, other method)               | 405    | `method_not_allowed` (+ `Allow`) |
 * | malformed / invalid JSON body, missing body, `ValidationRejection` | 400 | `invalid_body` (+ `fields` when the member is known) |
 * | malformed / missing / invalid query parameter               | 400    | `invalid_body` + `fields` |
 * | body over `max-content-length` (`EntityStreamSizeException`) | 413   | `payload_too_large`      |
 * | `Content-Type` the route does not read                      | 415    | `unsupported_media_type` |
 * | `Accept` / `Accept-Encoding` the route cannot produce       | 406    | `not_acceptable`         |
 * | `GET /ws` without a WebSocket upgrade, bad header, other    | 400    | `bad_request`            |
 * | `AuthenticationFailedRejection` / `AuthorizationFailedRejection` | 401 / 403 | `unauthorized` / `forbidden` |
 * | any other exception                                         | 500    | `internal_error` (logged; no exception detail in the body) |
 */
object ErrorHandling {

    /** What the CORS preflight route leaves on every non-OPTIONS request (see [[Cors.preflight]]). */
    private val PreflightRejection: Rejection = MethodRejection(HttpMethods.OPTIONS)

    def errorResponse(status: StatusCode, code: String, message: String,
                      fields: Seq[FieldIssue] = Nil, headers: List[HttpHeader] = Nil): HttpResponse =
        HttpResponse(status, headers = headers ++ Cors.responseHeaders, entity = errorJson(code, message, fields))

    val notFoundResponse: HttpResponse =
        errorResponse(StatusCodes.NotFound, "not_found", "The requested resource could not be found")

    val internalErrorResponse: HttpResponse =
        errorResponse(StatusCodes.InternalServerError, "internal_error", "Internal server error")

    /** Answer of `akka.http.server.request-timeout` (Akka's default is a plain-text 503 without CORS). */
    val requestTimeoutResponse: HttpResponse =
        errorResponse(StatusCodes.ServiceUnavailable, "request_timeout",
            "The server did not produce a response in time; try again later")

    private def invalidBody(message: String, fields: Seq[FieldIssue] = Nil): HttpResponse =
        errorResponse(StatusCodes.BadRequest, "invalid_body", message, fields)

    private def badRequest(message: String): HttpResponse =
        errorResponse(StatusCodes.BadRequest, "bad_request", message)

    private def payloadTooLarge(e: EntityStreamSizeException): HttpResponse =
        errorResponse(StatusCodes.ContentTooLarge, "payload_too_large",
            s"The request body exceeds the maximum allowed size of ${e.limit} bytes")

    /**
     * The answer to a list of rejections, already transformed (`handleRejections` applies the
     * `TransformationRejection`s; applied again here, which is idempotent, so the function can be
     * called with any list). Rules are tried in Akka's default priority order.
     */
    def rejectionResponse(rejections: Seq[Rejection]): HttpResponse = {
        val relevant = RejectionHandler.applyTransformations(rejections.toList).filterNot(_ == PreflightRejection)
        if (relevant.isEmpty) notFoundResponse
        else {
            def first[A](pf: PartialFunction[Rejection, A]): Option[A] = relevant.collectFirst(pf)
            val methods = relevant.collect { case MethodRejection(method) => method }.distinct
            if (methods.nonEmpty) {
                val names = methods.map(_.value)
                errorResponse(StatusCodes.MethodNotAllowed, "method_not_allowed",
                    s"HTTP method not allowed, supported methods: ${names.mkString(", ")}",
                    headers = List(Allow((methods :+ HttpMethods.OPTIONS).distinct)))
            } else first {
                case AuthorizationFailedRejection =>
                    errorResponse(StatusCodes.Forbidden, "forbidden", "The supplied authentication is not authorized to access this resource")
                case r: MalformedQueryParamRejection =>
                    invalidBody(s"Query parameter '${r.parameterName}' is malformed", List(FieldIssue(r.parameterName, r.errorMsg)))
                case r: MalformedRequestContentRejection => malformedContent(r.cause)
                case r: MalformedHeaderRejection =>
                    badRequest(s"The value of HTTP header '${r.headerName}' is malformed: ${r.errorMsg}")
                case r: MissingHeaderRejection =>
                    badRequest(s"Request is missing required HTTP header '${r.headerName}'")
                case r: MissingQueryParamRejection =>
                    invalidBody(s"Query parameter '${r.parameterName}' is required", List(FieldIssue(r.parameterName, "is required")))
                case r: InvalidRequiredValueForQueryParamRejection =>
                    invalidBody(s"Query parameter '${r.parameterName}' has an invalid value",
                        List(FieldIssue(r.parameterName, s"expected '${r.expectedValue}', got '${r.actualValue}'")))
                case RequestEntityExpectedRejection =>
                    invalidBody("A request body is required")
                case _: AuthenticationFailedRejection =>
                    errorResponse(StatusCodes.Unauthorized, "unauthorized", "Missing or invalid credentials")
            }.orElse {
                val unsupported = relevant.collect { case r: UnsupportedRequestContentTypeRejection => r }
                Option.when(unsupported.nonEmpty) {
                    val expected = unsupported.flatMap(_.supported).map(_.toString).distinct.mkString(", ")
                    val actual   = unsupported.flatMap(_.contentType).headOption.fold("no Content-Type")(ct => s"Content-Type '$ct'")
                    errorResponse(StatusCodes.UnsupportedMediaType, "unsupported_media_type",
                        s"The request has $actual; expected: $expected")
                }
            }.orElse(first {
                case _: UnacceptedResponseContentTypeRejection | _: UnacceptedResponseEncodingRejection =>
                    errorResponse(StatusCodes.NotAcceptable, "not_acceptable",
                        "The resource cannot be served in a representation the request accepts (Accept / Accept-Encoding)")
                case r: UnsupportedRequestEncodingRejection =>
                    badRequest(s"The request's Content-Encoding is not supported; expected: ${r.supported.value}")
                case ExpectedWebSocketRequestRejection =>
                    badRequest("Expected a WebSocket upgrade request")
                case _: UnsupportedWebSocketSubprotocolRejection =>
                    badRequest("Unsupported WebSocket subprotocol")
                case r: ValidationRejection =>
                    invalidBody(r.message)
                case r: SchemeRejection =>
                    badRequest(s"Unsupported URI scheme; expected: ${r.supported}")
            }).getOrElse {
                Logger.logWarning(s"Unmapped rejection(s) answered with 400 bad_request: ${relevant.map(_.getClass.getSimpleName).mkString(", ")}")
                badRequest("The request was rejected")
            }
        }
    }

    /**
     * A body that could not be unmarshalled. spray-json's DeserializationException carries the JSON
     * path of the member that failed (`fieldNames`, outermost first, e.g. `agentTypes.count`), so
     * it becomes a `fields` entry; a JSON syntax error only keeps the parser's summary (position),
     * not its snippet of the input.
     */
    private def malformedContent(cause: Throwable): HttpResponse = cause match {
        case e: EntityStreamSizeException => payloadTooLarge(e)
        case e: DeserializationException =>
            val issue  = Option(e.msg).filter(_.nonEmpty).getOrElse("invalid value")
            val fields = if (e.fieldNames.isEmpty) Nil else List(FieldIssue(e.fieldNames.mkString("."), issue))
            invalidBody(s"Invalid request body: $issue", fields)
        case e: JsonParser.ParsingException =>
            invalidBody(s"Malformed JSON: ${Option(e.summary).getOrElse("syntax error")}")
        case _ =>
            invalidBody("The request body could not be read")
    }

    /** The answer to an exception that escaped a route. Never carries the exception's message or trace. */
    def exceptionResponse(error: Throwable): HttpResponse = error match {
        case e: EntityStreamSizeException => payloadTooLarge(e)
        case e: IllegalRequestException =>
            val code = e.status.intValue match {
                case 404 => "not_found"
                case 413 => "payload_too_large"
                case _   => "bad_request"
            }
            errorResponse(e.status, code, Option(e.info.summary).filter(_.nonEmpty).getOrElse(e.status.reason))
        case _ => internalErrorResponse
    }

    // Akka's default handlers discard the unread request entity before answering a rejection, so a
    // pending upload does not stall the connection; same here.
    private def discardingEntity(response: HttpResponse): Route =
        extractRequest { request =>
            extractMaterializer { implicit mat =>
                request.discardEntityBytes()
                complete(response)
            }
        }

    val rejectionHandler: RejectionHandler =
        RejectionHandler.newBuilder()
            .handleAll[Rejection](rejections => discardingEntity(rejectionResponse(rejections)))
            .handleNotFound(discardingEntity(notFoundResponse))
            .result()

    val exceptionHandler: ExceptionHandler = ExceptionHandler {
        case NonFatal(error) =>
            extractRequest { request =>
                val response = exceptionResponse(error)
                val where    = s"${request.method.value} ${request.uri.path}"
                if (response.status.intValue >= 500)
                    Logger.logError(s"Unhandled ${error.getClass.getName} answering $where (500 internal_error)", error)
                else
                    Logger.logWarning(s"$where answered ${response.status.intValue}: ${error.getClass.getSimpleName}")
                complete(response)
            }
    }

    /** Exceptions outermost, so a failure while answering a rejection is still a JSON 500. */
    val handleErrors: Directive0 = handleExceptions(exceptionHandler) & handleRejections(rejectionHandler)
}
