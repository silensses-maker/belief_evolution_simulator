package core.simulation.topology

/**
 * Pure structural convergence metrics for the co-evolutionary simulation.
 *
 * Two metrics are provided, each acting as an independent stop condition that
 * detects polarisation states that the global-consensus threshold cannot capture:
 *
 *   1. Opinion Assortativity r^t — weighted Pearson correlation of source/target
 *      beliefs on the directed influence edges (self-influence excluded).
 *      r^t → 1 signals perfect echo-chamber segregation
 *      (Section: Asortatividad Dirigida de Opinión).
 *
 *   2. Fragmentation Index Φ_t — normalised SCC count.  Φ_t > 0 means the
 *      network has split into several strongly connected components; global
 *      consensus is still possible only if exactly one of them receives no arcs
 *      from the others (Section: Índice de Fragmentación).
 *
 * All methods are allocation-light and operate directly on the Network actor's
 * CSR arrays so no data is copied from the hot path.
 *
 * CSR convention used throughout:
 *   neighborsRefs[ indexOffset(i-1) .. indexOffset(i) )  = in-neighbors of i
 *   i.e. agent j at position k influences agent i with weight neighborsWeights(k).
 *   In graph notation: directed edge j → i with weight I_ji.
 */
object ConvergenceMetrics {

    /**
     * Weighted Pearson opinion assortativity r^t ∈ [-1, 1] over the influence edges j → i with j ≠ i
     * (self-influence is not an edge between two agents, so it is excluded, as in Newman's definition).
     *
     *   W     = Σ_i Σ_{j≠i} I_ji
     *   μ_out = (1/W) Σ_i Σ_{j≠i} I_ji · B_j      (weighted mean belief of emitters)
     *   μ_in  = (1/W) Σ_i Σ_{j≠i} I_ji · B_i      (weighted mean belief of receivers)
     *
     *   r^t = Σ_i Σ_{j≠i} I_ji (B_j − μ_out)(B_i − μ_in)
     *         ───────────────────────────────────────────────────────────────────
     *         √[Σ_i Σ_{j≠i} I_ji (B_j − μ_out)²] · √[Σ_i Σ_{j≠i} I_ji (B_i − μ_in)²]
     *
     * Accumulated in double precision. Returns Float.NaN when the denominator is zero
     * (uniform beliefs, e.g. at consensus, or no edges).
     *
     * @param beliefs  The current public belief buffer (most-recently written buffer).
     */
    def computeAssortativity(
        numAgents: Int,
        indexOffset: Array[Int],
        neighborsRefs: Array[Int],
        neighborsWeights: Array[Float],
        beliefs: Array[Float]
    ): Float = {
        // --- Pass 1: weighted means over edges j != i ---
        var totalW = 0.0
        var muIn   = 0.0
        var muOut  = 0.0
        var i = 0
        while (i < numAgents) {
            val start = if (i == 0) 0 else indexOffset(i - 1)
            val end   = indexOffset(i)
            var k = start
            while (k < end) {
                val j = neighborsRefs(k)
                if (j != i) {
                    val w = neighborsWeights(k).toDouble
                    totalW += w
                    muOut  += w * beliefs(j)
                    muIn   += w * beliefs(i)
                }
                k += 1
            }
            i += 1
        }
        if (totalW == 0.0) return Float.NaN
        muIn  /= totalW
        muOut /= totalW

        // --- Pass 2: Pearson numerator and denominator terms ---
        var num    = 0.0
        var denom1 = 0.0 // Σ w (B_source − μ_out)²
        var denom2 = 0.0 // Σ w (B_target − μ_in)²
        i = 0
        while (i < numAgents) {
            val bTarget = beliefs(i) - muIn
            val start = if (i == 0) 0 else indexOffset(i - 1)
            val end   = indexOffset(i)
            var k = start
            while (k < end) {
                val j = neighborsRefs(k)
                if (j != i) {
                    val w       = neighborsWeights(k).toDouble
                    val bSource = beliefs(j) - muOut
                    num    += w * bSource * bTarget
                    denom1 += w * bSource * bSource
                    denom2 += w * bTarget * bTarget
                }
                k += 1
            }
            i += 1
        }

        val denom = math.sqrt(denom1 * denom2)
        if (denom == 0.0) Float.NaN else (num / denom).toFloat
    }

    /**
     * Counts Strongly Connected Components (SCCs) via iterative Tarjan's algorithm.
     *
     * Because SCCs(G) = SCCs(G^T), we treat the in-adjacency CSR as if it were
     * the out-adjacency list.  This gives the correct SCC count without building
     * an explicit transposed graph.
     *
     * The iterative formulation avoids JVM stack-overflow on large networks.
     * Two parallel ArrayDeques (dfsNode / dfsEdge) act as the explicit DFS call
     * stack, one storing the current node and the other its next-edge iterator.
     *
     * Time O(N + E), space O(N).
     *
     * @return κ(G_t) — total number of SCCs in the directed graph.
     */
    def countSCC(
        numAgents: Int,
        indexOffset: Array[Int],
        neighborsRefs: Array[Int]
    ): Int = {
        val disc    = Array.fill(numAgents)(-1)
        val low     = new Array[Int](numAgents)
        val onStack = new Array[Boolean](numAgents)

        // SCC membership stack (Tarjan's standard stack)
        val sccStack = new java.util.ArrayDeque[Int]()
        // Explicit DFS call stack: parallel arrays for (node, nextEdgeIdx)
        val dfsNode  = new java.util.ArrayDeque[Int]()
        val dfsEdge  = new java.util.ArrayDeque[Int]()

        var timer    = 0
        var sccCount = 0

        var root = 0
        while (root < numAgents) {
            if (disc(root) == -1) {
                // Push root onto DFS stack
                disc(root) = timer; low(root) = timer; timer += 1
                onStack(root) = true
                sccStack.push(root)
                dfsNode.push(root)
                dfsEdge.push(if (root == 0) 0 else indexOffset(root - 1))

                while (!dfsNode.isEmpty) {
                    val v      = dfsNode.peek()
                    val eIdx   = dfsEdge.peek()
                    val endIdx = indexOffset(v)

                    if (eIdx < endIdx) {
                        val w = neighborsRefs(eIdx)
                        // Advance this node's edge iterator in-place
                        dfsEdge.pop(); dfsEdge.push(eIdx + 1)

                        if (disc(w) == -1) {
                            // Tree edge — descend into w
                            disc(w) = timer; low(w) = timer; timer += 1
                            onStack(w) = true
                            sccStack.push(w)
                            dfsNode.push(w)
                            dfsEdge.push(if (w == 0) 0 else indexOffset(w - 1))
                        } else if (onStack(w)) {
                            // Back edge — tighten low-link of v
                            if (disc(w) < low(v)) low(v) = disc(w)
                        }
                        // Forward / cross edges: no action needed for SCCs

                    } else {
                        // All edges of v processed — ascend
                        dfsNode.pop(); dfsEdge.pop()

                        if (!dfsNode.isEmpty) {
                            val parent = dfsNode.peek()
                            if (low(v) < low(parent)) low(parent) = low(v)
                        }

                        // If v is the root of an SCC, pop the SCC stack
                        if (low(v) == disc(v)) {
                            sccCount += 1
                            var u = sccStack.pop(); onStack(u) = false
                            while (u != v) {
                                u = sccStack.pop(); onStack(u) = false
                            }
                        }
                    }
                }
            }
            root += 1
        }
        sccCount
    }

    /**
     * Fragmentation index Φ_t = (κ(G_t) − 1) / (N − 1) ∈ [0, 1].
     *
     *   Φ_t = 0  → single giant SCC; global consensus is reachable.
     *   Φ_t > 0  → network has fractured; global consensus is impossible.
     *
     * @param sccCount κ(G_t) returned by countSCC.
     */
    def computeFragmentation(sccCount: Int, numAgents: Int): Float =
        if (numAgents <= 1) 0f
        else (sccCount - 1).toFloat / (numAgents - 1).toFloat
}
