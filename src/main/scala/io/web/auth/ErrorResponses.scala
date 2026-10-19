package io.web.auth

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import spray.json.*

import scala.collection.immutable.ListMap

/**
 * One invalid input of a `400 invalid_body` (#81, #123): `field` is the JSON path of the request
 * body member (`agents.name`), or the name of the query parameter, that was rejected; `issue` is a
 * short human-readable reason.
 */
final case class FieldIssue(field: String, issue: String)

object ErrorResponses {

    /**
     * The error body of every non-2xx JSON answer: `{"error": code, "message": message}`, plus
     * `"fields": [{"field", "issue"}, ...]` when `fields` is not empty (OpenAPI `Error` schema).
     * Built with spray-json, so the message may carry any user input.
     */
    def errorJsValue(code: String, message: String, fields: Seq[FieldIssue] = Nil): JsObject = {
        val base = ListMap[String, JsValue]("error" -> JsString(code), "message" -> JsString(message))
        if (fields.isEmpty) JsObject(base)
        else JsObject(base.updated("fields", JsArray(fields.iterator.map(fieldIssueJson).toVector)))
    }

    def errorJson(code: String, message: String): HttpEntity.Strict = errorJson(code, message, Nil)

    def errorJson(code: String, message: String, fields: Seq[FieldIssue]): HttpEntity.Strict =
        HttpEntity(ContentTypes.`application/json`, errorJsValue(code, message, fields).compactPrint)

    def usageLimitJson(message: String, limit: Int, requested: Int): HttpEntity.Strict =
        HttpEntity(
            ContentTypes.`application/json`,
            JsObject(
                "error"     -> JsString("usage_limit_exceeded"),
                "message"   -> JsString(message),
                "limit"     -> JsNumber(limit),
                "requested" -> JsNumber(requested)
            ).compactPrint
        )

    private def fieldIssueJson(issue: FieldIssue): JsValue =
        JsObject(ListMap[String, JsValue]("field" -> JsString(issue.field), "issue" -> JsString(issue.issue)))
}
