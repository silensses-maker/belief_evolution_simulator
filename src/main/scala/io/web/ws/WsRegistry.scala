package io.web.ws

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.util.ByteString
import io.web.MemoryBudget
import utils.logging.Logger

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class Dispatch(msg: Message)

object WsRegistry {
    sealed trait BufferedEvent
    case class TextEvent(json: String) extends BufferedEvent
    case class BinaryEvent(bytes: ByteString) extends BufferedEvent

    // A buffer is created on the first event of a run even when nobody is subscribed yet, on
    // purpose: the run starts as soon as POST /simulations/* returns, before the client's subscribe
    // arrives, and page refreshes / reconnects / opening a running run from the history all
    // subscribe late. For a RUNNING run there is no other source of the events already emitted (the
    // DB replay only serves terminal runs), so skipping the buffer would lose `topology_ready`,
    // `network_started` and the first frames for every late subscriber.
    //
    // Control (text) events and data (binary) frames are kept in separate queues so that the byte
    // caps only ever drop DATA frames — control events are never dropped while the buffer lives.
    // Every event carries its emission sequence number; the snapshot merges both queues back into
    // the original wire order.
    private class RunBuffer {
        val createdAt: Long = System.currentTimeMillis()
        val controls: ArrayBuffer[(Long, TextEvent)]        = ArrayBuffer.empty
        val data: mutable.ArrayDeque[(Long, BinaryEvent)]   = mutable.ArrayDeque.empty
        var controlBytes: Long = 0L
        var dataBytes: Long = 0L
        var nextSeq: Long = 0L
        var terminalAt: Option[Long] = None
        var droppingLogged: Boolean = false
        def bytes: Long = controlBytes + dataBytes
    }

    // Single lock guarding both subscribers and buffers so that subscribe+snapshot is atomic
    // with respect to dispatch: an event is either visible in the snapshot or delivered live,
    // never both, never neither.
    private val lock = new AnyRef
    private val subscribers = mutable.Map[Long, mutable.Set[ActorRef]]()
    private val buffers     = mutable.Map[Long, RunBuffer]()
    private var totalBytes: Long = 0L

    // Per-run byte cap. Oldest DATA frames are dropped (drop-head) once exceeded.
    private val maxBytesPerRun: Long = 200L * 1024 * 1024
    /**
     * Byte budget across ALL runs' buffers (`APP_WS_BUFFER_BUDGET_MB`, default
     * min(1024 MB, max heap / 4)). When exceeded: whole buffers of terminal runs are evicted first
     * (oldest terminal first — their subscribers fall back to the DB replay), then the oldest DATA
     * frames of the largest running buffer are dropped. Control events are never dropped.
     */
    val globalBudgetBytes: Long = MemoryBudget.fromEnv("APP_WS_BUFFER_BUDGET_MB", 1024, 4)
    // Retention after run reaches terminal state (matches ephemeral frames TTL).
    private val retentionMs: Long = 60L * 60 * 1000
    // Hard upper bound for runs that never reach terminal (crash, abandoned).
    private val maxAgeMs: Long = 4L * 60 * 60 * 1000

    // Atomically register the actor as a live subscriber AND return the current event snapshot.
    // The actor must drain the snapshot synchronously (before returning to its receive loop) so
    // that buffered events are written to the WS sink ahead of any queued Dispatch messages.
    def subscribeAndSnapshot(runId: Long, actor: ActorRef): Seq[BufferedEvent] = lock.synchronized {
        subscribers.getOrElseUpdate(runId, mutable.Set.empty) += actor
        buffers.get(runId).map(snapshotOf).getOrElse(Nil)
    }

    def unsubscribe(runId: Long, actor: ActorRef): Unit = lock.synchronized {
        subscribers.get(runId).foreach { set =>
            set -= actor
            if (set.isEmpty) subscribers -= runId
        }
    }

    def dispatchBinary(runId: Long, bytes: ByteString): Unit = {
        val targets = lock.synchronized {
            appendUnlocked(runId, BinaryEvent(bytes))
            subscribers.get(runId).map(_.toList).getOrElse(Nil)
        }
        if (targets.nonEmpty) {
            val msg = BinaryMessage(bytes)
            targets.foreach(_ ! Dispatch(msg))
        }
    }

    def dispatchText(runId: Long, json: String): Unit = {
        val targets = lock.synchronized {
            appendUnlocked(runId, TextEvent(json))
            subscribers.get(runId).map(_.toList).getOrElse(Nil)
        }
        if (targets.nonEmpty) {
            val msg = TextMessage(json)
            targets.foreach(_ ! Dispatch(msg))
        }
    }

    // Mark a run terminal so its buffer becomes eligible for cleanup after retention.
    def markTerminal(runId: Long): Unit = lock.synchronized {
        val buf = buffers.getOrElseUpdate(runId, new RunBuffer)
        if (buf.terminalAt.isEmpty) buf.terminalAt = Some(System.currentTimeMillis())
    }

    def cleanup(): Int = lock.synchronized {
        val now = System.currentTimeMillis()
        val toRemove = buffers.collect {
            case (runId, b) if b.terminalAt.exists(_ + retentionMs < now) || (b.createdAt + maxAgeMs < now) => runId
        }.toList
        toRemove.foreach(removeUnlocked)
        toRemove.size
    }

    private def snapshotOf(buf: RunBuffer): List[BufferedEvent] = {
        val out      = List.newBuilder[BufferedEvent]
        val controls = buf.controls.iterator.buffered
        val data     = buf.data.iterator.buffered
        while (controls.hasNext || data.hasNext) {
            if (!data.hasNext || (controls.hasNext && controls.head._1 < data.head._1)) out += controls.next()._2
            else out += data.next()._2
        }
        out.result()
    }

    private def appendUnlocked(runId: Long, event: BufferedEvent): Unit = {
        val buf = buffers.getOrElseUpdate(runId, new RunBuffer)
        val seq = buf.nextSeq
        buf.nextSeq += 1
        event match {
            case t: TextEvent =>
                val size = t.json.length.toLong
                buf.controls += ((seq, t))
                buf.controlBytes += size
                totalBytes += size
            case b: BinaryEvent =>
                val size = b.bytes.length.toLong
                buf.data.append((seq, b))
                buf.dataBytes += size
                totalBytes += size
        }
        while (buf.bytes > maxBytesPerRun && buf.data.nonEmpty) dropOldestData(runId, buf)
        enforceGlobalBudgetUnlocked()
    }

    private def enforceGlobalBudgetUnlocked(): Unit = {
        while (totalBytes > globalBudgetBytes) {
            val oldestTerminal = buffers.iterator
                .collect { case (runId, b) if b.terminalAt.isDefined => (runId, b) }
                .minByOption { case (_, b) => b.terminalAt.get }
            oldestTerminal match {
                case Some((runId, b)) =>
                    Logger.log(f"WsRegistry: global budget exceeded, evicting buffer of terminal run $runId " +
                        f"(${b.bytes / 1048576.0}%.1f MB)")
                    removeUnlocked(runId)
                case None =>
                    val largestRunning = buffers.iterator
                        .filter { case (_, b) => b.data.nonEmpty }
                        .maxByOption { case (_, b) => b.dataBytes }
                    largestRunning match {
                        case Some((runId, b)) => dropOldestData(runId, b)
                        case None             => return // only control events left: never dropped
                    }
            }
        }
    }

    private def dropOldestData(runId: Long, buf: RunBuffer): Unit = {
        val (_, oldest) = buf.data.removeHead()
        val size = oldest.bytes.length.toLong
        buf.dataBytes -= size
        totalBytes -= size
        if (!buf.droppingLogged) {
            buf.droppingLogged = true
            Logger.log(s"WsRegistry: dropping the oldest data frames of run $runId's buffer " +
                "(per-run cap or global budget reached; control events are kept)")
        }
    }

    private def removeUnlocked(runId: Long): Unit =
        buffers.remove(runId).foreach(b => totalBytes -= b.bytes)
}
