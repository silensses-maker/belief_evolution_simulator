package io.web.ws

import scala.collection.mutable

/**
 * Outbound queue of ONE WebSocket connection (#119). Plain data structure (no Akka, not
 * thread-safe): [[WsConnectionActor]] owns it and hands one element at a time to the
 * backpressured WS source, polling the next one when the stream acknowledges the previous.
 *
 *  - Control elements (JSON text events) are never dropped. Past `maxControls` queued controls
 *    [[offerControl]] refuses the element and the owner must close the connection instead (the
 *    client reconnects, resubscribes and gets the run's buffer again), so a client that stops
 *    reading cannot grow it without bound.
 *  - Data elements (binary frames) are bounded by `maxDataBytes`, counting payload +
 *    [[WsOutbox.EntryOverheadBytes]] per element: the OLDEST queued data elements are dropped to
 *    make room (same drop-head policy as [[WsRegistry]]; the live view needs the newest rounds and
 *    the client can fetch older ones from REST `/frames`). The element just offered is always
 *    kept, even when it alone exceeds the budget.
 *
 * [[poll]] returns the elements in offer order: a drop removes a data element but never reorders
 * the others, so a network's `network_converged` still follows its last delivered frame.
 */
final class WsOutbox[A](val maxDataBytes: Long, val maxControls: Int) {
    private val controls = mutable.ArrayDeque.empty[(Long, A)]
    private val data     = mutable.ArrayDeque.empty[(Long, A, Long)] // (seq, element, cost)
    private var nextSeq: Long    = 0L
    private var queuedCost: Long = 0L
    private var dropped: Long      = 0L
    private var droppedPayload: Long = 0L

    /** Queues a control element; false (nothing queued) when `maxControls` are already queued. */
    def offerControl(element: A): Boolean = {
        if (controls.size >= maxControls) return false
        controls.append((nextSeq, element))
        nextSeq += 1
        true
    }

    /** Queues a data element of `payloadBytes`, dropping the oldest queued data past the budget. */
    def offerData(element: A, payloadBytes: Long): Unit = {
        val cost = math.max(0L, payloadBytes) + WsOutbox.EntryOverheadBytes
        data.append((nextSeq, element, cost))
        nextSeq += 1
        queuedCost += cost
        while (queuedCost > maxDataBytes && data.size > 1) {
            val (_, _, oldestCost) = data.removeHead()
            queuedCost -= oldestCost
            dropped += 1
            droppedPayload += oldestCost - WsOutbox.EntryOverheadBytes
        }
    }

    /** Next element in offer order, if any. */
    def poll(): Option[A] = {
        val takeControl = controls.nonEmpty && (data.isEmpty || controls.head._1 < data.head._1)
        if (takeControl) Some(controls.removeHead()._2)
        else if (data.nonEmpty) {
            val (_, element, cost) = data.removeHead()
            queuedCost -= cost
            Some(element)
        } else None
    }

    def isEmpty: Boolean   = controls.isEmpty && data.isEmpty
    def controlCount: Int  = controls.size
    def dataCount: Int     = data.size
    /** Queued data cost (payload + per-element overhead), the quantity bounded by `maxDataBytes`. */
    def dataBytes: Long    = queuedCost
    /** Data elements dropped since this outbox was created. */
    def droppedFrames: Long = dropped
    /** Payload bytes of the dropped data elements since this outbox was created. */
    def droppedBytes: Long  = droppedPayload
}

object WsOutbox {
    /** Approximate heap cost of one queued element besides its payload (message, tuple, slot). */
    final val EntryOverheadBytes: Long = 64L
}
