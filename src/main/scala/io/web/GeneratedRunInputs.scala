package io.web

import java.util.concurrent.ThreadLocalRandom
// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

/**
 * What a generated run hands the simulator, so that the same configuration and the same seed
 * give the same run (#127). Pure functions: `Server.executeGeneratedRun` applies them to both
 * the JSON route and the legacy binary `POST /run`.
 *
 * '''Seeds.''' The simulator seeds every random stream of a generated run with
 * `runMetadata.seed + runMetadata.runID + …` (Network: the topology's FenwickTree, `+ network
 * number`; AgentProcessor: initial beliefs, influence weights, bias assignment and the initial
 * confidence of CONFIDENCE agents, `+ startsAt + network number`). The run id is new on every
 * launch, so the seed alone never reproduced a run. The server therefore passes the simulator
 * [[engineSeed]] `= userSeed − runID`: each stream then starts from `userSeed + …` on every launch
 * (64-bit addition wraps, so `(userSeed − runID) + runID == userSeed` for every value). The USER
 * seed is what is stored (`generated_runs.seed`), returned and exported; the engine's value stays
 * derivable as `seed − id`.
 *
 * '''Agent types.''' The simulator assigns agent types to agent indexes in the order of the rows,
 * while `agent_type_distributions` keeps no order. Rows are therefore merged by
 * (strategy, effect) and sorted by code ([[canonicalAgentTypes]]), so the stored rows rebuild the
 * exact input of the run and row order in a request no longer matters.
 */
object GeneratedRunInputs {

    /** Largest seed the server draws: 2^53 − 1 (`Number.MAX_SAFE_INTEGER`), exact in JavaScript. */
    final val MaxAutoSeed: Long = (1L << 53) - 1

    /** Legacy "draw one for me" value of the binary `POST /run` (also accepted in the JSON body). */
    final val RandomSeedSentinel: Long = -1L

    /** A seed drawn uniformly in `[0, MaxAutoSeed]`. */
    def drawAutoSeed(): Long = ThreadLocalRandom.current().nextLong(MaxAutoSeed + 1)

    /** The run's seed: the requested one, or a freshly drawn one when absent or `-1`. */
    def resolveSeed(requested: Option[Long], draw: () => Long = () => drawAutoSeed()): Long =
        requested match {
            case Some(seed) if seed != RandomSeedSentinel => seed
            case _                                        => draw()
        }

    /** The value handed to the simulator for run `runId` (see the object comment). */
    def engineSeed(userSeed: Long, runId: Long): Long = userSeed - runId

    /**
     * Agent-type rows `(silenceStrategy, silenceEffect, count)` with one row per
     * (strategy, effect) — counts of repeated pairs added up (the table is keyed by the pair, so a
     * repeated pair used to make the whole run row fail to save) — sorted by strategy, then effect.
     */
    def canonicalAgentTypes(rows: Seq[(Int, Int, Int)]): Vector[(Int, Int, Int)] =
        rows.groupMapReduce { case (strategy, effect, _) => (strategy, effect) }(_._3)(_ + _)
            .iterator
            .map { case ((strategy, effect), count) => (strategy, effect, count) }
            .toVector
            .sortBy { case (strategy, effect, _) => (strategy, effect) }

    /**
     * Bias rows `(biasType, count)` with one row per bias type, sorted by code. A repeated type
     * keeps its LAST count, which is what the simulator (a HashMap filled in order) and
     * `validateBiasCoverage` already used, so the run itself does not change.
     */
    def canonicalBiasTypes(rows: Seq[(Int, Int)]): Vector[(Int, Int)] =
        rows.foldLeft(Map.empty[Int, Int]) { case (acc, (bias, count)) => acc.updated(bias, count) }
            .toVector
            .sortBy(_._1)
}
