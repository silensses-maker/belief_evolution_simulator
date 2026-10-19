package io.web.ws

import akka.actor.{Actor, ActorRef}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.pattern.pipe
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import io.db.DatabaseManager
import io.web.{MemoryBudget, SimulationCache}
import io.web.auth.{AuthenticatedUser, TokenFailure, TokenRejection, TokenVerifier}
import spray.json.*
import DefaultJsonProtocol.*
import utils.logging.Logger

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * Outcome of one `auth` message. `failureReason` (only with `user = None`) is the `reason` of the
 * `auth_failed` event: `token_revoked` / `account_deactivated` for a valid token that is refused,
 * None for an invalid or expired token (or an unexpected failure).
 */
private case class AuthResult(user: Option[AuthenticatedUser], failureReason: Option[String] = None)
case object ConnectionClosed

object WsConnectionActor {
    /** Sent back by the outbound `Source.actorRefWithBackpressure` once it emitted an element. */
    case object OutboundAck
    /** Fails the outbound source: the socket is closed with 1011 and the client reconnects. */
    final case class FailOutbound(reason: String)
    /**
     * Completes the outbound source once its last element was acknowledged: Akka HTTP then closes
     * the socket with a normal closure (1000). Sent after `auth_failed` (#123). Akka HTTP 10.5 has
     * no public API to choose the close code of a server-side WebSocket (only 1000 on completion,
     * 1011 on failure), so the policy-violation code 1008 cannot be sent; the frontend treats 1000
     * and 1008 alike (no reconnect, `auth_failed`).
     */
    case object CompleteOutbound

    // ── Client → server messages (#119) ──────────────────────────────────────

    /** A well-formed client message (see [[parseClientMessage]]). */
    sealed trait ClientMessage
    object ClientMessage {
        final case class Auth(token: String)      extends ClientMessage
        final case class Subscribe(runId: Long)   extends ClientMessage
        final case class Unsubscribe(runId: Long) extends ClientMessage
    }

    /**
     * A client message that cannot be read as text: a binary message (the protocol has none
     * client → server) or a streamed text above [[MaxClientMessageChars]]. Answered with
     * `invalid_message`, like any other malformed message.
     */
    final case class UnreadableClientMessage(reason: String)

    /** Longest client text message accepted (an `auth` with a Firebase ID token is ~1-2 KB). */
    final val MaxClientMessageChars: Int = 64 * 1024

    /**
     * Total parser of one client text message: never throws. Left(reason) for anything malformed —
     * too long, not JSON, not an object, `type` missing / not a string / unknown, `token` missing
     * or not a string, `runId` missing, not a string or not a decimal int64. Client input must
     * never throw in the receive loop: a throw restarts the actor, which silently drops its
     * authenticated state and subscriptions (and, with the ack protocol, closes the socket 1011).
     */
    def parseClientMessage(text: String): Either[String, ClientMessage] = {
        def runIdOf(fields: Map[String, JsValue], tpe: String): Either[String, Long] =
            fields.get("runId") match {
                case Some(JsString(s)) => s.toLongOption.toRight(s"$tpe: \"runId\" must be a decimal int64 string")
                case Some(_)           => Left(s"$tpe: \"runId\" must be a string")
                case None              => Left(s"$tpe: \"runId\" is missing")
            }
        if (text.length > MaxClientMessageChars) Left(s"message longer than $MaxClientMessageChars characters")
        else Try(text.parseJson) match {
            case Failure(_) => Left("not valid JSON")
            case Success(JsObject(fields)) =>
                fields.get("type") match {
                    case Some(JsString("auth")) =>
                        fields.get("token") match {
                            case Some(JsString(token)) => Right(ClientMessage.Auth(token))
                            case Some(_)               => Left("auth: \"token\" must be a string")
                            case None                  => Left("auth: \"token\" is missing")
                        }
                    case Some(JsString("subscribe"))   => runIdOf(fields, "subscribe").map(ClientMessage.Subscribe(_))
                    case Some(JsString("unsubscribe")) => runIdOf(fields, "unsubscribe").map(ClientMessage.Unsubscribe(_))
                    case Some(JsString(other))         => Left(s"unknown type '${other.take(64)}'")
                    case Some(_)                       => Left("\"type\" must be a string")
                    case None                          => Left("\"type\" is missing")
                }
            case Success(_) => Left("not a JSON object")
        }
    }

    /** The `invalid_message` error event: same shape as `auth_failed` / `run_not_found`, plus `message`. */
    def invalidMessageJson(reason: String): String = WsEvents.invalidMessage(reason)

    /**
     * Reads one inbound WS message as what the connection actor receives (#119): the same
     * `TextMessage.Strict`, or an [[UnreadableClientMessage]]. Never fails. A text split across
     * TCP chunks arrives as `TextMessage.Streamed` — before #119 it was drained and ignored, so e.g.
     * a long `auth` could go unanswered; it is now read to the end, keeping at most
     * [[MaxClientMessageChars]]. Binary messages are drained (an unconsumed streamed one would
     * stall the socket's inbound side). The `/ws` route runs it with `mapAsync(1)`, so the actor
     * still gets the client's messages in order.
     */
    def readClientMessage(msg: Message)(implicit mat: Materializer): Future[Any] = {
        implicit val ec: ExecutionContext = ExecutionContext.parasitic
        val read: Future[Any] = msg match {
            case tm: TextMessage.Strict => Future.successful(tm)
            case tm: TextMessage =>
                tm.textStream
                    .runFold((new java.lang.StringBuilder, 0L)) { case ((sb, total), chunk) =>
                        if (total + chunk.length <= MaxClientMessageChars) sb.append(chunk)
                        (sb, total + chunk.length)
                    }
                    .map { case (sb, total) =>
                        if (total > MaxClientMessageChars)
                            UnreadableClientMessage(s"message longer than $MaxClientMessageChars characters")
                        else TextMessage.Strict(sb.toString)
                    }
            case _: BinaryMessage.Strict =>
                Future.successful(UnreadableClientMessage("binary messages are not accepted"))
            case bm: BinaryMessage =>
                bm.dataStream.runWith(Sink.ignore).map(_ => UnreadableClientMessage("binary messages are not accepted"))
        }
        read.recover { case NonFatal(_) => UnreadableClientMessage("the message could not be read") }
    }

    /**
     * Data (binary frame) budget of ONE connection's outbound queue (`APP_WS_CONNECTION_BUFFER_MB`,
     * default min(32 MB, max heap / 64)). Past it the oldest queued frames are dropped.
     */
    val dataBudgetBytes: Long = MemoryBudget.fromEnv("APP_WS_CONNECTION_BUFFER_MB", 32, 64)
    /**
     * Control events queued on one connection before it is closed instead (never dropped). Far
     * above any run's control event count (3 per network + 1), so only a client that stopped
     * reading — or resubscribes in a loop without reading — reaches it.
     */
    final val MaxQueuedControls: Int = 1_000_000

    // DB replay pacing: the next page of frames is read only when the queued data falls to the
    // low watermark, and a page targets ReplayPageTargetBytes (row count adapted to the row size).
    private val ReplayLowWatermarkBytes: Long = dataBudgetBytes / 2
    private val ReplayPageTargetBytes: Long   = math.max(64L * 1024, math.min(4L * 1024 * 1024, dataBudgetBytes / 4))
    private final val InitialReplayPageRows = 4
    private final val MaxReplayPageRows     = 4096

    private final val DropLogIntervalNanos: Long = 10L * 1000 * 1000 * 1000

    private enum ReplayStage { case NetworkStart, NetworkFrames, NetworkEnd }

    /** Position of one DB replay (one subscribed terminal run) between two pages. */
    private final case class ReplayJob(
        id: Long,
        runId: Long,
        status: String,
        networkIds: Option[IndexedSeq[String]] = None,
        netIdx: Int = 0,
        stage: ReplayStage = ReplayStage.NetworkStart,
        afterRound: Int = -1,
        afterStartsAt: Int = -1,
        pageRows: Int = InitialReplayPageRows
    )

    /** Messages produced by one replay step, in wire order; `next` is None once the run is done. */
    private final case class ReplayStep(jobId: Long, messages: IndexedSeq[Message], next: Option[ReplayJob])

    private def terminalEventJson(runId: Long, status: String): String =
        // A run that failed ends with the same `error` event live subscribers got (#124).
        if (status == "error") WsEvents.runError(runId, "The simulation ended with an error")
        else WsEvents.runCompleted(runId)

    private def nextPageRows(maxRowBytes: Int): Int =
        math.max(1L, math.min(MaxReplayPageRows.toLong, ReplayPageTargetBytes / math.max(1, maxRowBytes))).toInt

    /**
     * Runs one DB replay step off the actor (blocking JDBC): emits control events and at most one
     * page of frames — ONE message per persisted frame partition (`network_frames` row), the same
     * protocol as live frames (#119) — then yields so the connection paces the next page.
     * Per network: `topology_ready` → `network_started` → frames in (round, startsAt) order →
     * `network_converged` (only while the results snapshot is cached); then `run_completed` / `error`.
     * Network ids come from the persisted frames (`network_frames.run_id`, #124), not from
     * SimulationCache, which may no longer hold the run while its frames still exist.
     */
    private def replayStep(job: ReplayJob): ReplayStep = {
        val ids = job.networkIds.getOrElse(DatabaseManager.getFrameNetworkIds(job.runId).toIndexedSeq)
        var j = job.copy(networkIds = Some(ids))
        val out = IndexedSeq.newBuilder[Message]
        var next: Option[ReplayJob] = None
        var done = false
        while (!done) {
            if (j.netIdx >= ids.length) {
                out += TextMessage(terminalEventJson(j.runId, j.status))
                done = true
            } else {
                val networkId = ids(j.netIdx)
                j.stage match {
                    case ReplayStage.NetworkStart =>
                        out += TextMessage(WsEvents.topologyReady(j.runId, networkId))
                        out += TextMessage(WsEvents.networkStarted(j.runId, networkId))
                        val stage = if (Try(UUID.fromString(networkId)).isSuccess) ReplayStage.NetworkFrames else ReplayStage.NetworkEnd
                        j = j.copy(stage = stage, afterRound = -1, afterStartsAt = -1)
                    case ReplayStage.NetworkFrames =>
                        val rows = DatabaseManager.getFramePage(UUID.fromString(networkId), j.afterRound, j.afterStartsAt, j.pageRows)
                        rows.foreach(row => out += BinaryMessage(ByteString.fromArrayUnsafe(row.frame)))
                        j =
                            if (rows.length < j.pageRows) j.copy(stage = ReplayStage.NetworkEnd)
                            else {
                                val last = rows.last
                                j.copy(afterRound = last.round, afterStartsAt = last.startsAt,
                                    pageRows = nextPageRows(rows.iterator.map(_.frame.length).max))
                            }
                        if (rows.nonEmpty) {
                            next = Some(j)
                            done = true
                        }
                    case ReplayStage.NetworkEnd =>
                        SimulationCache.getResults(j.runId, networkId).foreach { r =>
                            out += TextMessage(WsEvents.networkConverged(j.runId, networkId, r.finalRound, r.consensus))
                        }
                        j = j.copy(netIdx = j.netIdx + 1, stage = ReplayStage.NetworkStart)
                }
            }
        }
        ReplayStep(job.id, out.result(), next)
    }
}

/**
 * One WebSocket connection: auth, run subscriptions and the outbound path (#119).
 *
 * Outbound: the socket's source is a `Source.actorRefWithBackpressure` (see Server `/ws`). This
 * actor hands it ONE message at a time and sends the next one on [[WsConnectionActor.OutboundAck]];
 * everything waiting meanwhile sits in a [[WsOutbox]], which never drops control (text) events
 * and drops the oldest binary frames past [[WsConnectionActor.dataBudgetBytes]] (drops are logged,
 * rate-limited). If the client stops reading for good, the controls cap closes the connection
 * instead of dropping a control event. Every outbound message goes through this actor — futures
 * pipe their results back to it — so the one-in-flight ack protocol always holds.
 */
class WsConnectionActor(outRef: ActorRef)(implicit mat: Materializer) extends Actor {
    import WsConnectionActor.*

    implicit val ec: ExecutionContext = context.dispatcher

    private var user: Option[AuthenticatedUser] = None
    private val subscribedRuns = scala.collection.mutable.Set[Long]()

    private val outbox = new WsOutbox[Message](dataBudgetBytes, MaxQueuedControls)
    private var awaitingAck = false
    private var stopWhenDrained = false
    private var failed = false

    private var dropLogged = false
    private var lastDropLogNanos = 0L
    private var loggedDroppedFrames = 0L
    private var loggedDroppedBytes = 0L

    private val replays = mutable.Queue[ReplayJob]()
    private var replayInFlight = false
    private var nextReplayId = 0L

    override def postStop(): Unit = {
        subscribedRuns.foreach(WsRegistry.unsubscribe(_, self))
        logDrops(force = true)
    }

    def receive: Receive = unauthenticated

    // Client input arrives as `TextMessage.Strict` or [[UnreadableClientMessage]] (the `/ws` route
    // runs [[readClientMessage]] first); any other `Message` is read here the same way. Malformed
    // input is answered with `invalid_message` and never changes the connection's state.
    private val unauthenticated: Receive = {
        case OutboundAck                       => onAck()
        case tm: TextMessage.Strict            => doAuth(tm.text)
        case UnreadableClientMessage(reason)   => sendControl(invalidMessageJson(reason))
        case m: Message                        => readClientMessage(m).pipeTo(self)
        case AuthResult(Some(u), _)   =>
            user = Some(u)
            context.become(authenticated)
            sendControl(WsEvents.authOk(u.uid))
        case AuthResult(None, reason) =>
            // Close once the error has been handed to the socket (it may be queued behind an
            // earlier reply): the outbound source is completed (normal closure, see
            // CompleteOutbound) and the actor stops; input is ignored meanwhile.
            context.become(closing)
            stopWhenDrained = true
            sendControl(WsEvents.authFailed(reason))
        case ConnectionClosed         => context.stop(self)
    }

    private val authenticated: Receive = {
        case OutboundAck                       => onAck()
        case tm: TextMessage.Strict            => handleControl(tm.text)
        case UnreadableClientMessage(reason)   => sendControl(invalidMessageJson(reason))
        case m: Message                        => readClientMessage(m).pipeTo(self)
        case Dispatch(msg)            =>
            enqueue(msg)
            afterEnqueue()
        case step: ReplayStep         => onReplayStep(step)
        case ConnectionClosed         => context.stop(self)
    }

    private val closing: Receive = {
        case OutboundAck              => onAck()
        case tm: TextMessage.Streamed => tm.textStream.runWith(Sink.ignore)
        case ConnectionClosed         => context.stop(self)
        case _                        =>
    }

    // ── Outbound ─────────────────────────────────────────────────────────────

    private def sendControl(json: String): Unit = {
        enqueue(TextMessage(json))
        afterEnqueue()
    }

    /** Queues without flushing; callers batch several enqueues, then call [[afterEnqueue]]. */
    private def enqueue(msg: Message): Unit = {
        if (failed) return
        msg match {
            case t: TextMessage =>
                if (!outbox.offerControl(t))
                    failConnection(s"${outbox.controlCount} control events queued and the client is not reading")
            case b: BinaryMessage.Strict => outbox.offerData(b, b.data.length.toLong)
            case b: BinaryMessage        => outbox.offerData(b, 0L)
        }
    }

    private def afterEnqueue(): Unit = {
        logDrops(force = false)
        flush()
    }

    private def onAck(): Unit = {
        awaitingAck = false
        flush()
    }

    private def flush(): Unit = {
        if (failed) return
        if (!awaitingAck) outbox.poll().foreach { msg =>
            awaitingAck = true
            outRef ! msg
        }
        if (stopWhenDrained && !awaitingAck && outbox.isEmpty) {
            outRef ! CompleteOutbound
            context.stop(self)
        } else maybeFetchReplay()
    }

    private def failConnection(reason: String): Unit = {
        failed = true
        Logger.logWarning(s"WS connection of ${who}: closing it — $reason (control events are never " +
            "dropped; the client reconnects and resubscribes)")
        outRef ! FailOutbound(reason)
        context.stop(self)
    }

    /** Logs the frames dropped since the last report: the first drop at once, then every 10 s at most. */
    private def logDrops(force: Boolean): Unit = {
        val frames = outbox.droppedFrames - loggedDroppedFrames
        if (frames <= 0) return
        val now = System.nanoTime()
        if (!force && dropLogged && now - lastDropLogNanos < DropLogIntervalNanos) return
        val bytes = outbox.droppedBytes - loggedDroppedBytes
        Logger.logWarning(f"WS connection of $who: dropped $frames%d data frame(s) " +
            f"(${bytes / 1048576.0}%.1f MB) since the last report — the client reads slower than frames " +
            f"arrive (outbound cap ${dataBudgetBytes / 1048576.0}%.0f MB); control events are kept")
        dropLogged = true
        lastDropLogNanos = now
        loggedDroppedFrames = outbox.droppedFrames
        loggedDroppedBytes = outbox.droppedBytes
    }

    private def who: String = user.map(_.uid).getOrElse("an unauthenticated client")

    // ── Auth & subscriptions ─────────────────────────────────────────────────

    /**
     * Parses one client text ([[parseClientMessage]], total) and hands a well-formed message to
     * `handle`; a malformed one gets `invalid_message`. Nothing thrown while handling client input
     * escapes to the receive loop (#119): a restart would silently drop the authenticated state
     * and subscriptions; an unexpected failure (e.g. no DB connection) gets `internal_error`.
     */
    private def onClientText(text: String)(handle: ClientMessage => Unit): Unit =
        parseClientMessage(text) match {
            case Left(reason) => sendControl(invalidMessageJson(reason))
            case Right(msg) =>
                try handle(msg)
                catch {
                    case NonFatal(ex) =>
                        Logger.logError(s"WS connection of $who: handling a client message failed: ${ex.getMessage}")
                        sendControl(WsEvents.internalError)
                }
        }

    private def doAuth(text: String): Unit = onClientText(text) {
        case ClientMessage.Auth(token) =>
            Future {
                // Same verification as REST (#123): signature first, then the fail-open revocation check.
                TokenVerifier.verify(token) match {
                    case Right(t) =>
                        DatabaseManager.createOrUpdateUser(t.uid, t.email, t.name, t.photo) match {
                            case Some(u) if u.deactivated =>
                                // REST answers 401 "Account is deactivated" to such an account.
                                Logger.log(s"WS auth refused: account ${t.uid} is deactivated")
                                AuthResult(None, Some("account_deactivated"))
                            case Some(u) =>
                                AuthResult(Some(AuthenticatedUser(t.uid, t.email, t.name, t.photo, u.roles, u.id)))
                            case None =>
                                AuthResult(None)
                        }
                    case Left(TokenFailure(TokenRejection.Revoked, detail)) =>
                        Logger.log(s"WS auth token rejected (revoked session or disabled account): $detail")
                        AuthResult(None, Some("token_revoked"))
                    case Left(TokenFailure(TokenRejection.Invalid, detail)) =>
                        Logger.log(s"WS auth token verification failed: $detail")
                        AuthResult(None)
                }
            }.recover { case NonFatal(ex) =>
                // e.g. no DB connection in createOrUpdateUser: answer instead of leaving the client waiting.
                Logger.logError(s"WS auth failed: ${ex.getMessage}")
                AuthResult(None)
            }.pipeTo(self)
        case _ =>
            // A well-formed subscribe / unsubscribe before auth.
            sendControl(WsEvents.authRequired)
    }

    private def handleControl(text: String): Unit = onClientText(text) {
        case ClientMessage.Subscribe(runId) => doSubscribe(runId)
        case ClientMessage.Unsubscribe(runId) =>
            WsRegistry.unsubscribe(runId, self)
            subscribedRuns -= runId
            // A pending DB replay of that run stops too (an in-flight step is discarded).
            replays.filterInPlace(_.runId != runId)
        case ClientMessage.Auth(_) =>
            // Already authenticated: a repeated auth is ignored, as before #119.
    }

    private def doSubscribe(runId: Long): Unit = {
        // Atomically register as subscriber and capture the current event snapshot. Any event
        // dispatched after this call reaches us via Dispatch; any event dispatched before is in
        // the snapshot. Queuing it synchronously here keeps the original order at the socket.
        val snapshot = WsRegistry.subscribeAndSnapshot(runId, self)
        subscribedRuns += runId

        if (snapshot.nonEmpty) {
            snapshot.foreach {
                case WsRegistry.TextEvent(json)    => enqueue(TextMessage(json))
                case WsRegistry.BinaryEvent(bytes) => enqueue(BinaryMessage(bytes))
            }
            afterEnqueue()
            return
        }

        // No in-memory buffer: either the run is brand-new (no events emitted yet) or its
        // buffer has been evicted. Decide based on DB state.
        DatabaseManager.getRunSummary(runId) match {
            case None =>
                sendControl(WsEvents.runNotFound(runId))
                WsRegistry.unsubscribe(runId, self)
                subscribedRuns -= runId

            case Some(summary) if isTerminalStatus(summary.status) =>
                // Buffer evicted (run is old); fall back to DB-backed replay.
                nextReplayId += 1
                replays.enqueue(ReplayJob(nextReplayId, runId, summary.status))
                maybeFetchReplay()

            case Some(_) =>
                // Running but nothing emitted yet — live events will arrive via Dispatch.
        }
    }

    private def isTerminalStatus(status: String): Boolean =
        status == "completed" || status == "cancelled" || status == "error"

    // ── DB replay (pull-driven, one page at a time) ──────────────────────────

    private def maybeFetchReplay(): Unit = {
        if (replayInFlight || failed || replays.isEmpty) return
        if (outbox.dataBytes > ReplayLowWatermarkBytes) return
        val job = replays.head
        replayInFlight = true
        Future(replayStep(job))
            .recover { case NonFatal(ex) =>
                Logger.logError(s"WS DB replay of run ${job.runId} failed: ${ex.getMessage}")
                // Still end the run for the client, so it does not wait for an event that never comes.
                ReplayStep(job.id, IndexedSeq(TextMessage(terminalEventJson(job.runId, job.status))), None)
            }
            .pipeTo(self)
    }

    private def onReplayStep(step: ReplayStep): Unit = {
        replayInFlight = false
        if (replays.headOption.exists(_.id == step.jobId)) {
            step.next match {
                case Some(next) => replays(0) = next
                case None       => replays.dequeue()
            }
            step.messages.foreach(enqueue)
            afterEnqueue()
        } else maybeFetchReplay() // the run was unsubscribed meanwhile: drop the step
    }
}
