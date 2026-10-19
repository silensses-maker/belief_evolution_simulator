package io.web

import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, OPTIONS, POST, PUT}
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive0, Route}

/** CORS of every answer, success or error (#123: moved here from Server so ErrorHandling shares it). */
object Cors {

    val responseHeaders: List[HttpHeader] = List(
        `Access-Control-Allow-Origin`.*,
        `Access-Control-Allow-Methods`(POST, GET, PUT, DELETE, OPTIONS),
        `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin"),
        `Access-Control-Max-Age`(86400),
        `Access-Control-Allow-Credentials`(false)
    )

    def addCorsHeaders: Directive0 = respondWithHeaders(responseHeaders)

    /**
     * The CORS preflight: `OPTIONS` on ANY path answers 200 with the CORS headers. Because it
     * accepts every path, the `MethodRejection(OPTIONS)` it leaves on every other request says
     * nothing about the requested resource: [[ErrorHandling]] ignores it (unknown path → 404, not
     * 405 "supported methods: OPTIONS").
     */
    val preflight: Route = addCorsHeaders {
        options {
            complete(HttpResponse(StatusCodes.OK))
        }
    }
}
