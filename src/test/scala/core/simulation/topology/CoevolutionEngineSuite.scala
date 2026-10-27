package core.simulation.topology

import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import scala.util.Random

/**
 * Unit tests for the coevolution engine (thesis Ch. 3): rupture rule, silence handling, Propuestas A/B/C,
 * isolation rule and the row-stochastic invariant. Networks are written as receiver -> (emitter, weight) rows and
 * converted to the simulator's CSR layout; self-influence is implicit (1 - sum of explicit weights).
 */
class CoevolutionEngineSuite extends munit.FunSuite {

    private final case class Net(off: Array[Int], refs: Array[Int], w: Array[Float], b: Array[Bias])

    private def csr(n: Int, rows: Map[Int, Seq[(Int, Float)]]): Net = {
        val off = new Array[Int](n)
        val refs = scala.collection.mutable.ArrayBuffer[Int]()
        val ws = scala.collection.mutable.ArrayBuffer[Float]()
        for (i <- 0 until n) {
            rows.getOrElse(i, Nil).foreach { case (j, w) => refs += j; ws += w }
            off(i) = refs.length
        }
        Net(off, refs.toArray, ws.toArray, Array.fill(refs.length)(CognitiveBiases.DEGROOT))
    }

    private def row(r: Net, i: Int): Seq[(Int, Float)] = {
        val s = if (i == 0) 0 else r.off(i - 1)
        (s until r.off(i)).map(k => (r.refs(k), r.w(k)))
    }

    private def step(net: Net, beliefs: Array[Float], tol: Float, pBreak: Float, pCreate: Float, strategy: Int,
                     speaking: Array[Byte] = null, memory: Array[Byte] = null, public: Array[Float] = null,
                     seed: Long = 7L): Net = {
        val n = beliefs.length
        val (o, r, w, b) = CoevolutionEngine.evolveTopology(
            n, net.off, net.refs, net.w, net.b, beliefs,
            if (public == null) beliefs.clone() else public,
            if (speaking == null) Array.fill[Byte](n)(1) else speaking,
            if (memory == null) Array.fill[Byte](n)(0) else memory,
            Array.fill(n)(tol), pBreak, pCreate, strategy, new Random(seed))
        Net(o, r, w, b)
    }

    private val eps = 1e-5f

    test("tolerance radius: recovered exactly from SiLEnSeSS's window bounds, for any offset") {
        // AgentProcessor.FirstUpdate stores upper = offset + radius and lower = offset - radius
        val radius = Array(0.10f, 0.01f, 0.50f, 0.20f, 0.02f, 0.15f)
        val offset = Array(0.05f, 0.005f, 0.25f, 0.10f, 0.01f, 0f)
        val upper = radius.indices.map(i => offset(i) + radius(i)).toArray
        val lower = radius.indices.map(i => offset(i) - radius(i)).toArray
        val tau = CoevolutionEngine.toleranceRadii(upper, lower, radius.length)
        radius.indices.foreach(i => assertEqualsFloat(tau(i), radius(i), 1e-6f))
        assertNotEquals(upper(0), radius(0))                            // the raw upper bound would be 0.15, not 0.10
    }

    test("isolation: lost weight returns to the implicit self-influence, no self-edge is created") {
        // agent 0 listens to agent 1 (far away); nobody is within its tolerance
        val net = csr(3, Map(0 -> Seq((1, 0.4f)), 1 -> Seq((0, 0.5f)), 2 -> Seq((1, 0.5f))))
        val out = step(net, Array(0.0f, 0.95f, 0.6f), tol = 0.05f, pBreak = 1f, pCreate = 1f, strategy = 1)
        assertEquals(row(out, 0), Seq.empty[(Int, Float)])            // edge broken, nothing created
        assert(!row(out, 0).exists(_._1 == 0), "no explicit self-edge")
        // implicit self-influence of agent 0 is now 1 - 0 = 1 (was 0.6)
    }

    test("Propuesta A: the lost weight is split equally among the new within-tolerance contacts") {
        // agent 0 (B=0.5) listens to 3 (B=1.0, far); 1 and 2 are within tau and become candidates
        val net = csr(4, Map(0 -> Seq((3, 0.3f))))
        val out = step(net, Array(0.5f, 0.52f, 0.47f, 1.0f), tol = 0.1f, pBreak = 1f, pCreate = 1f, strategy = 0)
        val r = row(out, 0).toMap
        assertEquals(r.keySet, Set(1, 2))
        assertEqualsFloat(r(1), 0.15f, eps)
        assertEqualsFloat(r(2), 0.15f, eps)
    }

    test("Propuesta B: the lost weight is split in proportion to the similarity 1 - |B_k - B_i|") {
        val net = csr(4, Map(0 -> Seq((3, 0.3f))))
        val beliefs = Array(0.5f, 0.52f, 0.45f, 1.0f)
        val out = step(net, beliefs, tol = 0.1f, pBreak = 1f, pCreate = 1f, strategy = 1)
        val r = row(out, 0).toMap
        val s1 = 1f - 0.02f; val s2 = 1f - 0.05f
        assertEqualsFloat(r(1), 0.3f * s1 / (s1 + s2), eps)
        assertEqualsFloat(r(2), 0.3f * s2 / (s1 + s2), eps)
    }

    test("Propuesta C: with nothing lost, the neighbourhood is renormalized including self (similarity 1)") {
        // agent 0 keeps neighbour 1 and gains 2; pBreak = 0 so nothing is lost
        val net = csr(3, Map(0 -> Seq((1, 0.4f))))
        val beliefs = Array(0.5f, 0.55f, 0.45f)
        val out = step(net, beliefs, tol = 0.1f, pBreak = 0f, pCreate = 1f, strategy = 1)
        val r = row(out, 0).toMap
        val sum = 1f + 0.95f + 0.95f                                    // S_00 + S_01 + S_02
        assertEqualsFloat(r(1), 0.95f / sum, eps)
        assertEqualsFloat(r(2), 0.95f / sum, eps)
        assertEqualsFloat(1f - r.values.sum, 1f / sum, eps)             // implicit self-influence keeps 1/sum S
    }

    test("silence: a silent SOM- neighbour is not evaluated, so its edge cannot break") {
        val net = csr(2, Map(0 -> Seq((1, 0.5f))))
        val out = step(net, Array(0.0f, 1.0f), tol = 0.05f, pBreak = 1f, pCreate = 0f, strategy = 1,
                       speaking = Array[Byte](1, 0), memory = Array[Byte](0, 0))
        assertEquals(row(out, 0).map(_._1), Seq(1))
    }

    test("silence: a silent SOM+ neighbour is judged by its last public opinion") {
        val net = csr(2, Map(0 -> Seq((1, 0.5f))))
        // private belief of 1 is far, but its remembered public opinion is close -> the edge is kept
        val kept = step(net, Array(0.0f, 1.0f), tol = 0.05f, pBreak = 1f, pCreate = 0f, strategy = 1,
                        speaking = Array[Byte](1, 0), memory = Array[Byte](0, 1), public = Array(0.0f, 0.02f))
        assertEquals(row(kept, 0).map(_._1), Seq(1))
        // remembered public opinion far away -> the edge breaks
        val broken = step(net, Array(0.0f, 0.02f), tol = 0.05f, pBreak = 1f, pCreate = 0f, strategy = 1,
                          speaking = Array[Byte](1, 0), memory = Array[Byte](0, 1), public = Array(0.0f, 1.0f))
        assertEquals(row(broken, 0), Seq.empty[(Int, Float)])
    }

    test("invariant: explicit row sums never exceed 1, for all strategies, over many rounds") {
        val n = 300; val k = 6; val rnd = new Random(42)
        for (strategy <- Seq(0, 1); (pB, pC) <- Seq((0.2f, 0.02f), (0f, 0.05f), (1f, 1f))) {
            var net = csr(n, (0 until n).map { i =>
                val nb = rnd.shuffle((0 until n).filter(_ != i).toList).take(k)
                val raw = Array.fill(k + 1)(rnd.nextFloat()); val s = raw.sum
                i -> nb.zipWithIndex.map { case (j, idx) => (j, raw(idx) / s) }
            }.toMap)
            val beliefs = Array.fill(n)(rnd.nextFloat())
            for (t <- 1 to 15) {
                net = step(net, beliefs, tol = 0.1f, pBreak = pB, pCreate = pC, strategy = strategy, seed = t)
                for (i <- 0 until n) {
                    val s = row(net, i).map(_._2.toDouble).sum
                    assert(s <= 1.0 + 1e-5, s"row $i sums to $s (strategy $strategy, pBreak $pB, pCreate $pC)")
                    assert(row(net, i).forall(_._2 >= 0f), s"negative weight in row $i")
                }
                // one DeGroot step so beliefs move between rounds
                val next = Array.tabulate(n) { i =>
                    beliefs(i) + row(net, i).map { case (j, w) => w * (beliefs(j) - beliefs(i)) }.sum.toFloat
                }
                Array.copy(next, 0, beliefs, 0, n)
            }
        }
    }
}
