package io.web.ws

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import io.web.testing.JsonTestSupport.*
import spray.json.*

import java.util.UUID

/**
 * WS text messages built by WsEvents (#123): round trips with hostile text, and the exact bytes the
 * frontend received before — the replay events must stay byte-identical to the live ones the
 * simulation actors (package core) still build by interpolation from a Long, a UUID, an Int and a Boolean.
 */
class WsEventsSuite extends munit.FunSuite {

    private val runId     = 7_123_456_789_012_345_678L
    private val networkId = UUID.fromString("3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b")

    test("run control events: same bytes as the live events of core/simulation/actors") {
        // Literal formats of Network.scala (topology_ready, network_started, network_converged)
        // and Monitor.scala (run_completed).
        assertEquals(WsEvents.topologyReady(runId, networkId.toString),
            s"""{"event":"topology_ready","runId":"$runId","networkId":"$networkId"}""")
        assertEquals(WsEvents.networkStarted(runId, networkId.toString),
            s"""{"event":"network_started","runId":"$runId","networkId":"$networkId"}""")
        for (round <- List(0, 1, 9999); consensus <- List(true, false)) {
            assertEquals(WsEvents.networkConverged(runId, networkId.toString, round, consensus),
                s"""{"event":"network_converged","runId":"$runId","networkId":"$networkId","finalRound":$round,"consensus":$consensus}""")
        }
        assertEquals(WsEvents.runCompleted(runId), s"""{"event":"run_completed","runId":"$runId"}""")
    }

    test("run error event: unchanged bytes (#124: spray-json, members sorted), message escaped") {
        assertEquals(WsEvents.runError(5L, "Simulation failed: ArithmeticException"),
            """{"code":"run_failed","event":"error","message":"Simulation failed: ArithmeticException","runId":"5"}""")
        nastyStrings.foreach { s =>
            val parsed = parseAsClient(WsEvents.runError(5L, s))
            assertEquals(str(parsed, "message"), s)
            assertEquals(str(parsed, "runId"), "5")
        }
    }

    test("connection replies: same bytes as before #123 for plain values") {
        assertEquals(WsEvents.authOk("Xn3jK2abc"), """{"type":"auth_ok","userId":"Xn3jK2abc"}""")
        assertEquals(WsEvents.authFailed(), """{"type":"error","code":"auth_failed"}""")
        assertEquals(WsEvents.authRequired, """{"type":"error","code":"auth_required"}""")
        assertEquals(WsEvents.internalError, """{"type":"error","code":"internal_error"}""")
        assertEquals(WsEvents.runNotFound(42L), """{"type":"error","code":"run_not_found","runId":"42"}""")
        assertEquals(WsEvents.invalidMessage("not valid JSON"),
            """{"type":"error","code":"invalid_message","message":"not valid JSON"}""")
    }

    test("auth_failed keeps code auth_failed (frontend handling) and adds the reason of a refused valid token") {
        List("token_revoked", "account_deactivated").foreach { reason =>
            val parsed = parseAsClient(WsEvents.authFailed(Some(reason)))
            assertEquals(str(parsed, "type"), "error")
            assertEquals(str(parsed, "code"), "auth_failed")
            assertEquals(str(parsed, "reason"), reason)
        }
        assertEquals(WsEvents.authFailed(Some("token_revoked")),
            """{"type":"error","code":"auth_failed","reason":"token_revoked"}""")
    }

    test("hostile text in uids, reasons and network ids round-trips") {
        nastyStrings.foreach { s =>
            assertEquals(str(parseAsClient(WsEvents.authOk(s)), "userId"), s)
            assertEquals(str(parseAsClient(WsEvents.invalidMessage(s)), "message"), s)
            assertEquals(str(parseAsClient(WsEvents.topologyReady(1L, s)), "networkId"), s)
            val converged = parseAsClient(WsEvents.networkConverged(1L, s, 3, consensus = false))
            assertEquals(str(converged, "networkId"), s)
            assertEquals(int(converged, "finalRound"), 3)
            assertEquals(field(converged, "consensus"), JsFalse)
        }
    }

    test("invalidMessageJson (parser error replies) goes through WsEvents") {
        assertEquals(WsConnectionActor.invalidMessageJson("a \"quoted\"\nreason"),
            """{"type":"error","code":"invalid_message","message":"a \"quoted\"\nreason"}""")
    }
}
