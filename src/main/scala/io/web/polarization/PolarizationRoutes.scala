package io.web.polarization

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive1, Route}
import io.web.auth.AuthenticatedUser
import io.web.auth.ErrorResponses.errorJson
import io.web.auth.FieldIssue
import spray.json.*
import utils.logging.Logger

/**
 * Polarization measures endpoints (#129), mounted by Server like every route (both `/` and `/api`,
 * inside the CORS directive and the global JSON error handlers):
 *
 *  - `GET  /simulations/polarization/measures` (authenticated): the pol_measures catalog — measures,
 *    parameters with the package's defaults and ranges, supported k, classification thresholds,
 *    f(d) options, limits — plus the package / numpy / SciPy / Python versions. Cached in memory.
 *  - `POST /simulations/polarization` (authenticated), body [[PolarizationRequest]]: one value per
 *    histogram, `{values, levels, errors?, classification, unique, package: {ref, version}}`.
 *
 * Errors ({error, message[, fields]}): 400 `invalid_body` (shape, k, sizes, weights), 413
 * `payload_too_large` (body over [[PolarizationRequest.MaxBodyBytes]]), 422 `invalid_measure`
 * (measure, parameters or k rejected by the package), 503 `unavailable` (worker not available).
 * With APP_POLARIZATION=false (`backend` = None) both paths answer 404 `not_found` to any method,
 * before authentication, and nothing is started.
 *
 * The routes only take the authentication directive and the backend, so the tests build them with
 * a stub authenticator and a stub or fake-worker backend.
 */
object PolarizationRoutes {

    final val DisabledMessage =
        "Polarization measures are disabled on this server (APP_POLARIZATION=false)"

    def apply(authenticate: Directive1[AuthenticatedUser], backend: Option[PolarizationBackend],
              maxBodyBytes: Long = PolarizationRequest.MaxBodyBytes): Route =
        pathPrefix("simulations" / "polarization") {
            backend match {
                case None          => (pathEndOrSingleSlash | path("measures"))(disabled)
                case Some(service) => enabledRoutes(authenticate, service, maxBodyBytes)
            }
        }

    private def enabledRoutes(authenticate: Directive1[AuthenticatedUser], service: PolarizationBackend,
                              maxBodyBytes: Long): Route =
        path("measures") {
            get {
                authenticate { _ =>
                    onSuccess(service.catalog()) {
                        case Right(catalog) => complete(jsonOk(catalog))
                        case Left(failure)  => complete(failureResponse(failure))
                    }
                }
            }
        } ~
        pathEndOrSingleSlash {
            post {
                authenticate { _ =>
                    withSizeLimit(maxBodyBytes) {
                        entity(as[JsValue]) { json =>
                            PolarizationRequest.parse(json) match {
                                case Left((message, fields)) =>
                                    complete(error(StatusCodes.BadRequest, "invalid_body", message, fields))
                                case Right(request) =>
                                    onSuccess(service.compute(request)) {
                                        case Right(result) => complete(jsonOk(result))
                                        case Left(failure) => complete(failureResponse(failure))
                                    }
                            }
                        }
                    }
                }
            }
        }

    private val disabled: Route =
        extractRequest { request =>
            extractMaterializer { implicit materializer =>
                request.discardEntityBytes()
                complete(error(StatusCodes.NotFound, "not_found", DisabledMessage))
            }
        }

    private def jsonOk(value: JsValue): HttpResponse =
        HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, value.compactPrint))

    private def error(status: StatusCode, code: String, message: String, fields: Seq[FieldIssue] = Nil): HttpResponse =
        HttpResponse(status, entity = errorJson(code, message, fields))

    /** The HTTP answer of a worker failure. */
    def failureResponse(failure: PolarizationFailure): HttpResponse = failure match {
        case PolarizationFailure.Unavailable(message) =>
            error(StatusCodes.ServiceUnavailable, "unavailable", message)
        case PolarizationFailure.Rejected("invalid_measure", message, fields) =>
            error(StatusCodes.UnprocessableContent, "invalid_measure", message, fields)
        case PolarizationFailure.Rejected("invalid_request", message, fields) =>
            error(StatusCodes.BadRequest, "invalid_body", message, fields)
        case PolarizationFailure.Rejected(code, message, _) =>
            Logger.logError(s"Polarization worker error $code: $message")
            error(StatusCodes.InternalServerError, "internal_error", "Internal server error")
    }
}
