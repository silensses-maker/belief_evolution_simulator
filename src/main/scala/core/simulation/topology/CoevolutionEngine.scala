package core.simulation.topology

import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/**
 * Coevolution engine: one round of adaptive rewiring of the influence matrix (thesis Ch. 3, Algorithm 3.1).
 *
 * The engine is a pure function over the Network actor's CSR arrays; it is only called when the run has a
 * `CoevolutionConfig`, so static (original SOM) runs never reach it.
 *
 * CSR convention: the incoming influences of agent i are stored in positions
 * `[indexOffset(i-1), indexOffset(i))` of `neighborsRefs` / `neighborsWeights` (edge j -> i with weight I_ji).
 * Self-influence is implicit: I_ii = 1 - sum of the explicit weights of row i.
 *
 * For each agent i:
 *  1. Rupture. Each visible neighbour j is compared with i: a speaking neighbour by its belief, a silent SOM+
 *     neighbour by its last public opinion; a silent SOM- neighbour is not evaluated. If the distance d exceeds
 *     tau_i, the edge breaks with probability pBreak * g, where g = 1 - exp(1 - d / tau_i).
 *  2. Bounded creation. Every non-neighbour k with |B_k - B_i| <= tau_i becomes a new contact with probability
 *     pCreate (full O(N) scan).
 *  3. Redistribution, preserving the row-stochastic invariant:
 *     - weight lost and no new contact: isolation rule, the lost weight returns to the implicit self-influence;
 *     - weight lost and new contacts: Propuesta A (rewiringStrategy = 0) splits it equally,
 *       Propuesta B (rewiringStrategy = 1) in proportion to the similarity 1 - |B_k - B_i|;
 *     - nothing lost but new contacts: Propuesta C renormalizes the whole neighbourhood, including the implicit
 *       self-influence with similarity 1.
 *
 * Tested in `CoevolutionEngineSuite` (src/test/scala).
 *
 * @param tolRadius the symmetric tolerance radius tau_i of each agent (see `toleranceRadii`)
 * @param random seeded per round by the caller (`seed + round`) so runs are reproducible
 * @return the new (indexOffset, neighborsRefs, neighborsWeights, neighborBiases)
 */
object CoevolutionEngine {

    /** SplitMix64 finalizer (Steele, Lea and Flood, 2014). */
    private def mix64(x: Long): Long = {
        var z = x + 0x9E3779B97F4A7C15L
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL
        z ^ (z >>> 31)
    }

    /**
     * Per-round RNG seed that mixes the network seed and the round.
     *
     * `java.util.Random` returns almost the same first draw for consecutive seeds (about 0.730 for seeds 1 to 12), so
     * seeding round t with `seed + t` makes the first Bernoulli trial of every round nearly constant across rounds and
     * seeds. In small networks, where a round makes only a few draws, that trial decides the outcome. Mixing both
     * values gives independent first draws. Enabled with `CoevolutionConfig.mixedRoundSeed`.
     */
    def roundSeed(seed: Long, round: Int): Long = mix64(mix64(seed) ^ round.toLong)

    /**
     * Tolerance radius tau_i for the coevolution rules, recovered from SiLEnSeSS's acceptance window.
     *
     * Before the first round, `AgentProcessor` turns each agent's (radius r, offset o) into the bounds of its silence
     * window, stored in the shared arrays as upper = o + r (in `tolRadius`) and lower = o - r (in `tolOffset`). The
     * coevolution rules use the symmetric radius r = (upper - lower) / 2, which is exact for any offset and leaves the
     * SiLEnSeSS window untouched. (Passing `upper` directly would use r + o: 0.15 instead of 0.10 with the default
     * offset of 0.05 for generated networks.)
     */
    def toleranceRadii(upper: Array[Float], lower: Array[Float], numAgents: Int): Array[Float] =
        Array.tabulate(numAgents)(i => (upper(i) - lower(i)) / 2f)

    def evolveTopology(
        numAgents: Int,
        indexOffset: Array[Int],
        neighborsRefs: Array[Int],
        neighborsWeights: Array[Float],
        neighborBiases: Array[Bias],
        beliefs: Array[Float],
        publicBeliefs: Array[Float],
        speaking: Array[Byte],
        hasMemory: Array[Byte],
        tolRadius: Array[Float],
        pBreak: Float,
        pCreate: Float,
        rewiringStrategy: Int,
        random: Random
    ): (Array[Int], Array[Int], Array[Float], Array[Bias]) = {

        val estimatedNewEdges = neighborsRefs.length
        val newIndexOffset = new Array[Int](numAgents)
        val newNeighborsRefs = new ArrayBuffer[Int](estimatedNewEdges)
        val newNeighborsWeights = new ArrayBuffer[Float](estimatedNewEdges)
        val newNeighborBiases = new ArrayBuffer[Bias](estimatedNewEdges)

        // Pre-allocate tracking structures to avoid GC overhead inside the loop
        val isNeighbor = new java.util.BitSet(numAgents)
        val keptNeighbors = new ArrayBuffer[(Int, Float, Bias)]()
        val newCandidates = new ArrayBuffer[Int]()

        var i = 0
        while (i < numAgents) {
            val startIdx = if (i == 0) 0 else indexOffset(i - 1)
            val endIdx = indexOffset(i)

            var keptWeightSum = 0f
            var lostWeightSum = 0f

            isNeighbor.clear()
            keptNeighbors.clear()
            newCandidates.clear()

            // --- CONTINUOUS RUPTURE ---
            var j = startIdx
            while (j < endIdx) {
                val neighborId = neighborsRefs(j)
                val weight = neighborsWeights(j)
                val bias = neighborBiases(j)

                isNeighbor.set(neighborId)

                val isSpeaking = speaking(neighborId) == 1
                val isSOMPlus = hasMemory(neighborId) == 1
                var edgeBroken = false

                if (isSpeaking || isSOMPlus) {
                    val perceivedBelief = if (isSpeaking) beliefs(neighborId) else publicBeliefs(neighborId)
                    val distance = math.abs(perceivedBelief - beliefs(i))
                    val tau = tolRadius(i)

                    if (distance > tau) {
                        val g = 1f - math.exp(1f - (distance / tau)).toFloat
                        if (random.nextFloat() < (pBreak * g)) {
                            edgeBroken = true
                        }
                    }
                }

                if (edgeBroken) {
                    lostWeightSum += weight
                } else {
                    keptWeightSum += weight
                    keptNeighbors.addOne((neighborId, weight, bias))
                }
                j += 1
            }

            // --- BOUNDED CREATION ---
            val beliefI = beliefs(i)
            val tauI = tolRadius(i)
            var k = 0
            while (k < numAgents) {
                if (k != i && !isNeighbor.get(k)) {
                    val distance = math.abs(beliefs(k) - beliefI)
                    if (distance <= tauI) {
                        if (random.nextFloat() < pCreate) {
                            newCandidates.addOne(k)
                        }
                    }
                }
                k += 1
            }

            // --- REWIRING & INFLUENCE CONSERVATION ---
            var m = 0
            val startOfNewAgentIdx = newNeighborsRefs.length
            while (m < keptNeighbors.length) {
                newNeighborsRefs.addOne(keptNeighbors(m)._1)
                newNeighborsWeights.addOne(keptNeighbors(m)._2)
                newNeighborBiases.addOne(keptNeighbors(m)._3)
                m += 1
            }

            if (lostWeightSum > 0f) {
                if (newCandidates.isEmpty) {
                    // Fallback: Isolation (eq:aislamiento) — the lost weight returns to self-influence.
                    // Self-influence is implicit (1 - sum of explicit weights), so dropping the broken edges
                    // already gives I_ii^{t+1} = I_ii^t + W_lost. Only an explicit self-edge, if the network
                    // has one (custom runs), receives the weight; no new self-edge is created, so the agent
                    // never counts itself in the silence majority test.
                    var idx = startOfNewAgentIdx
                    while (idx < newNeighborsRefs.length) {
                        if (newNeighborsRefs(idx) == i) newNeighborsWeights(idx) += lostWeightSum
                        idx += 1
                    }
                } else {
                    if (rewiringStrategy == 0) {
                        // Proposal A: Uniform redistribution
                        val weightPerNew = lostWeightSum / newCandidates.length
                        var c = 0
                        while (c < newCandidates.length) {
                            newNeighborsRefs.addOne(newCandidates(c))
                            newNeighborsWeights.addOne(weightPerNew)
                            newNeighborBiases.addOne(CognitiveBiases.DEGROOT)
                            c += 1
                        }
                    } else {
                        // Proposal B: Homophily-weighted redistribution
                        var similaritySum = 0f
                        val similarities = new Array[Float](newCandidates.length)
                        var c = 0
                        while (c < newCandidates.length) {
                            val sim = 1f - math.abs(beliefs(newCandidates(c)) - beliefs(i))
                            similarities(c) = sim
                            similaritySum += sim
                            c += 1
                        }

                        c = 0
                        while (c < newCandidates.length) {
                            val weight = if (similaritySum > 0) lostWeightSum * (similarities(c) / similaritySum) else lostWeightSum / newCandidates.length
                            newNeighborsRefs.addOne(newCandidates(c))
                            newNeighborsWeights.addOne(weight)
                            newNeighborBiases.addOne(CognitiveBiases.DEGROOT)
                            c += 1
                        }
                    }
                }
            } else if (newCandidates.nonEmpty) {
                // Proposal C: Global homophilic renormalization (pure-creation scenario, pBreak=0).
                // No weight was lost, so Proposals A/B would assign zero weight to new edges.
                // Instead, treat attention as a zero-sum resource: add new neighbors with placeholder
                // weight 0 then renormalize the ENTIRE local neighborhood (kept + new) by homophily
                // similarity, preserving the stochastic row-sum invariant (Σ I_ji = 1).
                var c = 0
                while (c < newCandidates.length) {
                    newNeighborsRefs.addOne(newCandidates(c))
                    newNeighborsWeights.addOne(0f)
                    newNeighborBiases.addOne(CognitiveBiases.DEGROOT)
                    c += 1
                }

                // Compute similarity for every neighbor in [startOfNewAgentIdx, end)
                val endIdx = newNeighborsRefs.length
                val similarities = new Array[Float](endIdx - startOfNewAgentIdx)
                var similaritySum = 0f
                var hasExplicitSelf = false
                var idx = startOfNewAgentIdx
                while (idx < endIdx) {
                    val sim = 1f - math.abs(beliefs(newNeighborsRefs(idx)) - beliefs(i))
                    similarities(idx - startOfNewAgentIdx) = sim
                    similaritySum += sim
                    if (newNeighborsRefs(idx) == i) hasExplicitSelf = true
                    idx += 1
                }
                // eq:propuesta_c normalizes over N_i^{t+1} ∪ {i} with ω_ii = 1 (self-similarity). When self-influence is implicit,
                // add its similarity to the denominator so it keeps I_ii^{t+1} = 1 / Σ S instead of dropping to 0.
                if (!hasExplicitSelf) similaritySum += 1f

                // Assign renormalized weights; fall back to uniform if all similarities are zero
                idx = startOfNewAgentIdx
                while (idx < endIdx) {
                    newNeighborsWeights(idx) =
                        if (similaritySum > 0f) similarities(idx - startOfNewAgentIdx) / similaritySum
                        else 1f / (endIdx - startOfNewAgentIdx).toFloat
                    idx += 1
                }
            }
            newIndexOffset(i) = newNeighborsRefs.length
            i += 1
        }
        (newIndexOffset, newNeighborsRefs.toArray, newNeighborsWeights.toArray, newNeighborBiases.toArray)
    }
}
