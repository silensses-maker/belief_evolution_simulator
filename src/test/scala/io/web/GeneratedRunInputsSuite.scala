package io.web

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import utils.datastructures.FenwickTree

import scala.util.Random

/**
 * Seeds and canonical rows of generated runs (#127). The stream tests rebuild the simulator's RNGs
 * with the exact expressions of core (not touched by #127):
 *  - topology:  `new FenwickTree(n, m, degreeDistribution - 2, runMetadata.seed + runMetadata.runID + networkNumber)`
 *    (Network.scala, network actor "N<k>");
 *  - beliefs, weights, bias assignment, initial confidence:
 *    `new Random(runMetadata.seed + runMetadata.runID + startsAt + networkNumber)` (AgentProcessor.scala),
 * where `runMetadata.seed` is what the server sends: `GeneratedRunInputs.engineSeed(userSeed, runID)`.
 */
class GeneratedRunInputsSuite extends munit.FunSuite {

    // Snowflake ids of two launches a few seconds apart, plus the extremes.
    private val runA = 375_000_000_000_000_001L
    private val runB = 375_000_123_456_789_013L
    private val runIds = List(runA, runB, 1L, 0L, Long.MaxValue, Long.MinValue)

    private def topologyPicks(engineSeed: Long, runId: Long, network: Long): Vector[Int] = {
        val tree = new FenwickTree(200, 3, 2.5 - 2, engineSeed + runId + network)
        Vector.fill(50)(tree.pickRandoms().toVector).flatten
    }

    private def agentDraws(engineSeed: Long, runId: Long, startsAt: Long, network: Long): Vector[Float] = {
        val random = new Random(engineSeed + runId + startsAt + network)
        Vector.fill(64)(random.nextFloat())
    }

    // ── Seeds ───────────────────────────────────────────────────────────────────────────────────

    test("resolveSeed keeps a requested seed (any int64 except -1) and draws one otherwise") {
        val draw = () => 77L
        assertEquals(GeneratedRunInputs.resolveSeed(Some(42L), draw), 42L)
        assertEquals(GeneratedRunInputs.resolveSeed(Some(0L), draw), 0L)
        assertEquals(GeneratedRunInputs.resolveSeed(Some(Long.MinValue), draw), Long.MinValue)
        assertEquals(GeneratedRunInputs.resolveSeed(Some(Long.MaxValue), draw), Long.MaxValue)
        assertEquals(GeneratedRunInputs.resolveSeed(None, draw), 77L)
        assertEquals(GeneratedRunInputs.resolveSeed(Some(-1L), draw), 77L)
    }

    test("auto seeds are drawn in [0, 2^53 - 1], exact as JavaScript numbers") {
        assertEquals(GeneratedRunInputs.MaxAutoSeed, 9_007_199_254_740_991L)
        val seeds = Vector.fill(100_000)(GeneratedRunInputs.drawAutoSeed())
        assert(seeds.forall(s => s >= 0L && s <= GeneratedRunInputs.MaxAutoSeed))
        // Uniform over the whole range, not a clock: both halves and (almost) no repeats.
        val half = GeneratedRunInputs.MaxAutoSeed / 2
        assert(seeds.count(_ > half) > 45_000 && seeds.count(_ <= half) > 45_000)
        assert(seeds.distinct.size > 99_990)
        assert(seeds.exists(_ > (1L << 52)), "the top bit of the 53-bit range is used")
        // The default draw is what resolveSeed uses
        assert(GeneratedRunInputs.resolveSeed(None) <= GeneratedRunInputs.MaxAutoSeed)
    }

    test("engineSeed + runID == userSeed for every value (64-bit wrap-around)") {
        val userSeeds = List(0L, 42L, -1L, GeneratedRunInputs.MaxAutoSeed, Long.MaxValue, Long.MinValue)
        for (user <- userSeeds; run <- runIds)
            assertEquals(GeneratedRunInputs.engineSeed(user, run) + run, user, s"user=$user run=$run")
    }

    test("same user seed, different run ids: the simulator's streams are identical") {
        for (user <- List(42L, 0L, GeneratedRunInputs.MaxAutoSeed, Long.MinValue); network <- List(1L, 2L, 7L)) {
            val engineA = GeneratedRunInputs.engineSeed(user, runA)
            val engineB = GeneratedRunInputs.engineSeed(user, runB)
            assertEquals(topologyPicks(engineA, runA, network), topologyPicks(engineB, runB, network))
            for (startsAt <- List(0L, 32L, 96L))
                assertEquals(agentDraws(engineA, runA, startsAt, network), agentDraws(engineB, runB, startsAt, network))
        }
    }

    test("different user seeds give different streams") {
        val (a, b) = (GeneratedRunInputs.engineSeed(42L, runA), GeneratedRunInputs.engineSeed(43L, runB))
        assertNotEquals(topologyPicks(a, runA, 1L), topologyPicks(b, runB, 1L))
        assertNotEquals(agentDraws(a, runA, 0L, 1L), agentDraws(b, runB, 0L, 1L))
    }

    test("before #127 (seed passed as is) the same seed gave different streams on each launch") {
        assertNotEquals(topologyPicks(42L, runA, 1L), topologyPicks(42L, runB, 1L))
        assertNotEquals(agentDraws(42L, runA, 0L, 1L), agentDraws(42L, runB, 0L, 1L))
        // ... and such a run is replayed by the user seed `stored + id` (what the engine saw), not `stored`
        val stored = 1_729_000_000_000L
        assertEquals(agentDraws(GeneratedRunInputs.engineSeed(stored + runA, runB), runB, 0L, 1L),
                     agentDraws(stored, runA, 0L, 1L))
    }

    // ── Canonical rows ──────────────────────────────────────────────────────────────────────────

    test("canonicalAgentTypes merges repeated (strategy, effect) pairs and sorts by code") {
        val rows = Seq((3, 0, 2), (0, 1, 5), (0, 0, 8), (0, 1, 1), (2, 2, 4))
        assertEquals(GeneratedRunInputs.canonicalAgentTypes(rows),
            Vector((0, 0, 8), (0, 1, 6), (2, 2, 4), (3, 0, 2)))
        // Order of the request does not matter; totals are preserved
        assertEquals(GeneratedRunInputs.canonicalAgentTypes(rows.reverse), GeneratedRunInputs.canonicalAgentTypes(rows))
        assertEquals(GeneratedRunInputs.canonicalAgentTypes(rows).map(_._3).sum, rows.map(_._3).sum)
        assertEquals(GeneratedRunInputs.canonicalAgentTypes(Nil), Vector.empty)
    }

    test("canonicalBiasTypes keeps the LAST count of a repeated type (the simulator's HashMap) and sorts") {
        assertEquals(GeneratedRunInputs.canonicalBiasTypes(Seq((4, 10), (0, 34), (4, 3), (1, 7))),
            Vector((0, 34), (1, 7), (4, 3)))
        assertEquals(GeneratedRunInputs.canonicalBiasTypes(Seq((2, 5))), Vector((2, 5)))
    }
}
