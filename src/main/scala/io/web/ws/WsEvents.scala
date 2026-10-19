package io.web.ws

import io.web.json.ApiJson.{jsId, obj}
import spray.json.*

/**
 * JSON text messages of the `/ws` protocol (#123), built with spray-json: same members, member
 * order and types the frontend reads (`front_BES/src/shared/lib/ws-manager/`): ids as strings,
 * `finalRound` an integer, `consensus` a boolean.
 *
 * The live control events of a run are emitted by the simulation actors (package `core`, out of the
 * scope of #123) with the same bytes as [[topologyReady]], [[networkStarted]], [[networkConverged]]
 * and [[runCompleted]] (Long / UUID / Int / Boolean values only, no user text); the DB replay of
 * [[WsConnectionActor]] uses these builders.
 */
object WsEvents {

    // ── Connection replies ──────────────────────────────────────────────────

    def authOk(userId: String): String =
        obj("type" -> JsString("auth_ok"), "userId" -> JsString(userId)).compactPrint

    /**
     * Auth failure. The connection is closed right after it (normal closure). `reason`, when
     * present, says why a token with a VALID signature was refused: `token_revoked` (revoked
     * session, disabled or deleted Firebase account) or `account_deactivated` (deactivated in the
     * database). An invalid or expired token gets no `reason`: `{"type":"error","code":"auth_failed"}`
     * as before #123.
     */
    def authFailed(reason: Option[String] = None): String = {
        val base = Seq[(String, JsValue)]("type" -> JsString("error"), "code" -> JsString("auth_failed"))
        obj((base ++ reason.map(r => "reason" -> JsString(r)))*).compactPrint
    }

    val authRequired: String = errorCode("auth_required")

    val internalError: String = errorCode("internal_error")

    /** `invalid_message`: same shape as `auth_failed` / `run_not_found`, plus `message`. */
    def invalidMessage(reason: String): String =
        obj("type" -> JsString("error"), "code" -> JsString("invalid_message"),
            "message" -> JsString(reason)).compactPrint

    def runNotFound(runId: Long): String =
        obj("type" -> JsString("error"), "code" -> JsString("run_not_found"), "runId" -> jsId(runId)).compactPrint

    private def errorCode(code: String): String =
        obj("type" -> JsString("error"), "code" -> JsString(code)).compactPrint

    // ── Run control events ──────────────────────────────────────────────────

    def topologyReady(runId: Long, networkId: String): String =
        obj("event" -> JsString("topology_ready"), "runId" -> jsId(runId), "networkId" -> JsString(networkId)).compactPrint

    def networkStarted(runId: Long, networkId: String): String =
        obj("event" -> JsString("network_started"), "runId" -> jsId(runId), "networkId" -> JsString(networkId)).compactPrint

    def networkConverged(runId: Long, networkId: String, finalRound: Int, consensus: Boolean): String =
        obj(
            "event"      -> JsString("network_converged"),
            "runId"      -> jsId(runId),
            "networkId"  -> JsString(networkId),
            "finalRound" -> JsNumber(finalRound),
            "consensus"  -> JsBoolean(consensus)
        ).compactPrint

    def runCompleted(runId: Long): String =
        obj("event" -> JsString("run_completed"), "runId" -> jsId(runId)).compactPrint

    /**
     * Control event of a run that ended in status `error` (#124). Same `event: "error"` shape the
     * frontend handles; `runId` routes it to the run's subscription. Built with spray-json since
     * #124 (`JsObject(...)`, members sorted by name): kept as is, byte for byte.
     */
    def runError(runId: Long, message: String): String =
        JsObject(
            "event"   -> JsString("error"),
            "runId"   -> jsId(runId),
            "code"    -> JsString("run_failed"),
            "message" -> JsString(message)
        ).compactPrint
}
