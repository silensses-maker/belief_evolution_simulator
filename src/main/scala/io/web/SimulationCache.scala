package io.web

import utils.logging.Logger

import scala.collection.mutable

case class TopologySnapshot(
    agentCount: Int,
    edgeCount: Int,
    names: Array[String],           // null for generated runs
    initialBeliefs: Array[Float],
    tolRadius: Array[Float],
    tolOffset: Array[Float],
    silenceStrategies: Array[Byte],
    silenceEffects: Array[Byte],
    indexOffset: Array[Int],
    neighborsRefs: Array[Int],
    neighborsWeights: Array[Float],
    neighborBiases: Array[Byte]
)

case class ResultsSnapshot(
    finalRound: Int,
    consensus: Boolean,
    agentCount: Int,
    names: Array[String],
    finalBeliefs: Array[Float],
    publicBeliefs: Array[Float]
)

/**
 * Byte budgets of the in-memory run stores (#124), read once from the environment. A missing,
 * blank or invalid value falls back to `min(defaultMb, max heap / heapDivisor)`.
 */
private[web] object MemoryBudget {
    def fromEnv(envVar: String, defaultMb: Long, heapDivisor: Long): Long = {
        val fallback = math.min(defaultMb * 1024 * 1024, Runtime.getRuntime.maxMemory() / heapDivisor)
        sys.env.get(envVar).map(_.trim).filter(_.nonEmpty) match {
            case None => fallback
            case Some(raw) =>
                raw.toLongOption.filter(_ > 0) match {
                    case Some(mb) => mb * 1024 * 1024
                    case None =>
                        Logger.logWarning(s"$envVar=$raw is not a positive integer (MB); using ${fallback / (1024 * 1024)} MB")
                        fallback
                }
        }
    }
}

/**
 * In-memory topology / results snapshots and network ids per run, served by
 * `/simulations/{runId}/networks[/{networkId}/topology|results]`. They are never persisted (production
 * runs use save mode DEBUG), so a backend restart loses them while the run's frames stay in
 * `network_frames` until their own cleanup.
 *
 * Retention is driven by the run state (#124), not by the time a snapshot was stored:
 *  - running (not yet [[markTerminal]]): always kept, never evicted by the budget;
 *  - terminal, `ephemeral` frames: kept [[Server.EphemeralSnapshotRetentionMinutes]] after the run
 *    ended, which outlasts the ephemeral frames (see the constant);
 *  - terminal, `persistent` frames: kept while the process lives.
 * On top of that, `APP_SIM_CACHE_BUDGET_MB` caps the total size: when it is exceeded the oldest
 * TERMINAL runs are evicted first; running runs are never evicted (the budget may then be exceeded).
 */
object SimulationCache {
    private final class RunEntry {
        val topologies: mutable.HashMap[String, TopologySnapshot] = mutable.HashMap.empty
        val results: mutable.HashMap[String, ResultsSnapshot]     = mutable.HashMap.empty
        var bytes: Long = 0L
        var persistent: Boolean = false
        var terminalAt: Option[Long] = None // epoch ms when the run reached a terminal state
    }

    /** Total byte budget (`APP_SIM_CACHE_BUDGET_MB`, default min(512 MB, max heap / 8)). */
    val budgetBytes: Long = MemoryBudget.fromEnv("APP_SIM_CACHE_BUDGET_MB", 512, 8)

    private def ephemeralRetentionMs: Long = Server.EphemeralSnapshotRetentionMinutes.toLong * 60 * 1000

    private val lock = new AnyRef
    private val runs = mutable.HashMap[Long, RunEntry]()
    private var totalBytes: Long = 0L
    private var overBudgetWarned = false

    /** Declares a run's frame retention; called by the Server when the run is created. */
    def registerRun(runId: Long, persistent: Boolean): Unit = lock.synchronized {
        runs.getOrElseUpdate(runId, new RunEntry).persistent = persistent
    }

    /**
     * Starts the post-run retention window (completed, cancelled or error). Creates the entry when
     * absent so that a snapshot stored late (e.g. by a network still stopping after a cancel) attaches
     * to a terminal entry and expires instead of being kept as "running" forever.
     */
    def markTerminal(runId: Long): Unit = lock.synchronized {
        val entry = runs.getOrElseUpdate(runId, new RunEntry)
        if (entry.terminalAt.isEmpty) entry.terminalAt = Some(System.currentTimeMillis())
        enforceBudgetLocked()
    }

    def storeTopology(runId: Long, networkId: String, snap: TopologySnapshot): Unit = lock.synchronized {
        val entry = runs.getOrElseUpdate(runId, new RunEntry)
        entry.topologies.put(networkId, snap).foreach(old => addBytes(entry, -topologyBytes(old)))
        addBytes(entry, topologyBytes(snap))
        enforceBudgetLocked()
    }

    def getTopology(runId: Long, networkId: String): Option[TopologySnapshot] = lock.synchronized {
        runs.get(runId).flatMap(_.topologies.get(networkId))
    }

    def storeResults(runId: Long, networkId: String, snap: ResultsSnapshot): Unit = lock.synchronized {
        val entry = runs.getOrElseUpdate(runId, new RunEntry)
        entry.results.put(networkId, snap).foreach(old => addBytes(entry, -resultsBytes(old)))
        addBytes(entry, resultsBytes(snap))
        enforceBudgetLocked()
    }

    def getResults(runId: Long, networkId: String): Option[ResultsSnapshot] = lock.synchronized {
        runs.get(runId).flatMap(_.results.get(networkId))
    }

    /**
     * Network ids whose topology is cached, sorted (UUID v7 → creation order at ms granularity, and
     * the same order as the `network_frames` fallback of `/networks`).
     */
    def getNetworkIds(runId: Long): Seq[String] = lock.synchronized {
        runs.get(runId).map(_.topologies.keys.toSeq.sorted).getOrElse(Seq.empty)
    }

    /** Periodic (60 s, from Server): expires terminal ephemeral runs and re-checks the budget. */
    def cleanup(): Unit = lock.synchronized {
        val cutoff = System.currentTimeMillis() - ephemeralRetentionMs
        val expired = runs.collect {
            case (runId, e) if !e.persistent && e.terminalAt.exists(_ < cutoff) => runId
        }.toList
        expired.foreach(removeLocked)
        enforceBudgetLocked()
    }

    private def addBytes(entry: RunEntry, delta: Long): Unit = {
        entry.bytes += delta
        totalBytes += delta
    }

    private def removeLocked(runId: Long): Unit =
        runs.remove(runId).foreach(e => totalBytes -= e.bytes)

    // Oldest terminal runs first; running runs are never evicted.
    private def enforceBudgetLocked(): Unit = {
        while (totalBytes > budgetBytes) {
            val oldestTerminal = runs.iterator
                .collect { case (runId, e) if e.terminalAt.isDefined => (runId, e) }
                .minByOption { case (_, e) => e.terminalAt.get }
            oldestTerminal match {
                case Some((runId, e)) =>
                    val retention = if (e.persistent) "persistent" else "ephemeral"
                    Logger.log(f"SimulationCache: budget exceeded, evicting terminal run $runId " +
                        f"(${e.bytes / 1048576.0}%.1f MB, $retention)")
                    removeLocked(runId)
                case None =>
                    if (!overBudgetWarned) {
                        Logger.logWarning(f"SimulationCache: ${totalBytes / 1048576.0}%.1f MB held by running runs " +
                            f"exceeds the ${budgetBytes / 1048576.0}%.1f MB budget (running runs are never evicted)")
                        overBudgetWarned = true
                    }
                    return
            }
        }
        overBudgetWarned = false
    }

    // Approximate heap footprint (array payloads + headers) — only used for the budget.
    private val ArrayHeader = 16L

    private def namesBytes(names: Array[String]): Long =
        if (names == null) 0L
        else names.foldLeft(ArrayHeader + 4L * names.length) { (acc, n) =>
            acc + 40L + (if (n == null) 0L else 2L * n.length)
        }

    private def topologyBytes(s: TopologySnapshot): Long =
        9 * ArrayHeader + namesBytes(s.names) +
            4L * (s.initialBeliefs.length + s.tolRadius.length + s.tolOffset.length + s.indexOffset.length +
                s.neighborsRefs.length + s.neighborsWeights.length) +
            s.silenceStrategies.length + s.silenceEffects.length + s.neighborBiases.length

    private def resultsBytes(s: ResultsSnapshot): Long =
        2 * ArrayHeader + namesBytes(s.names) + 4L * (s.finalBeliefs.length + s.publicBeliefs.length)
}
