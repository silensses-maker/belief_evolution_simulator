package core.simulation.topology

/**
 * Unit tests for the structural metrics (thesis Sec. "Métricas estructurales"): weighted directed assortativity r^t,
 * strongly connected components and the fragmentation index Phi_t.
 */
class ConvergenceMetricsSuite extends munit.FunSuite {

    /** CSR from receiver -> (emitter, weight) rows. */
    private def csr(n: Int, rows: Map[Int, Seq[(Int, Float)]]): (Array[Int], Array[Int], Array[Float]) = {
        val off = new Array[Int](n)
        val refs = scala.collection.mutable.ArrayBuffer[Int]()
        val ws = scala.collection.mutable.ArrayBuffer[Float]()
        for (i <- 0 until n) {
            rows.getOrElse(i, Nil).foreach { case (j, w) => refs += j; ws += w }
            off(i) = refs.length
        }
        (off, refs.toArray, ws.toArray)
    }

    test("assortativity is 1 for two internally connected, mutually disconnected opinion blocks") {
        val (off, refs, w) = csr(4, Map(0 -> Seq((1, 0.5f)), 1 -> Seq((0, 0.5f)), 2 -> Seq((3, 0.5f)), 3 -> Seq((2, 0.5f))))
        val r = ConvergenceMetrics.computeAssortativity(4, off, refs, w, Array(0.1f, 0.1f, 0.9f, 0.9f))
        assertEqualsFloat(r, 1f, 1e-5f)
    }

    test("assortativity is -1 when every edge joins opposite opinions") {
        val (off, refs, w) = csr(4, Map(0 -> Seq((2, 0.5f)), 2 -> Seq((0, 0.5f)), 1 -> Seq((3, 0.5f)), 3 -> Seq((1, 0.5f))))
        val r = ConvergenceMetrics.computeAssortativity(4, off, refs, w, Array(0.1f, 0.1f, 0.9f, 0.9f))
        assertEqualsFloat(r, -1f, 1e-5f)
    }

    test("with a symmetric influence matrix, r^t equals Djurdjevac Conrad et al. (2024), Eq. 6") {
        // undirected weighted graph: I_ji = I_ij
        val und = Seq((0, 1, 0.3f), (1, 2, 0.2f), (2, 3, 0.4f), (0, 3, 0.1f), (1, 3, 0.25f))
        val rows = und.flatMap { case (a, b, x) => Seq(b -> (a, x), a -> (b, x)) }.groupMap(_._1)(_._2)
        val (off, refs, w) = csr(4, rows)
        val th = Array(0.1f, 0.35f, 0.6f, 0.9f)
        val r = ConvergenceMetrics.computeAssortativity(4, off, refs, w, th)
        // Eq. 6 with the weight matrix in place of the adjacency matrix and weighted degree d_j
        val A = Array.ofDim[Double](4, 4); und.foreach { case (a, b, x) => A(a)(b) = x; A(b)(a) = x }
        val d = A.map(_.sum); val m2 = d.sum
        val mean = (0 until 4).map(j => d(j) * th(j)).sum / m2
        val num = (for (j <- 0 until 4; k <- 0 until 4) yield A(j)(k) * (th(j) - mean) * (th(k) - mean)).sum
        val den = (0 until 4).map(j => d(j) * math.pow(th(j) - mean, 2)).sum
        assertEqualsDouble(r.toDouble, num / den, 1e-5)
    }

    test("assortativity is undefined (NaN) at consensus and without edges") {
        val (off, refs, w) = csr(3, Map(0 -> Seq((1, 0.5f)), 1 -> Seq((2, 0.5f))))
        assert(ConvergenceMetrics.computeAssortativity(3, off, refs, w, Array.fill(3)(0.4f)).isNaN)
        val (o2, r2, w2) = csr(3, Map.empty)
        assert(ConvergenceMetrics.computeAssortativity(3, o2, r2, w2, Array(0.1f, 0.5f, 0.9f)).isNaN)
    }

    test("SCC count and fragmentation: two separate cycles give kappa = 2") {
        val (off, refs, _) = csr(6, Map(0 -> Seq((1, 0.5f)), 1 -> Seq((2, 0.5f)), 2 -> Seq((0, 0.5f)),
                                         3 -> Seq((4, 0.5f)), 4 -> Seq((5, 0.5f)), 5 -> Seq((3, 0.5f))))
        val k = ConvergenceMetrics.countSCC(6, off, refs)
        assertEquals(k, 2)
        assertEqualsFloat(ConvergenceMetrics.computeFragmentation(k, 6), 1f / 5f, 1e-6f)
    }

    test("a strongly connected graph has kappa = 1 and Phi = 0") {
        val (off, refs, _) = csr(4, Map(0 -> Seq((3, 0.5f)), 1 -> Seq((0, 0.5f)), 2 -> Seq((1, 0.5f)), 3 -> Seq((2, 0.5f))))
        val k = ConvergenceMetrics.countSCC(4, off, refs)
        assertEquals(k, 1)
        assertEqualsFloat(ConvergenceMetrics.computeFragmentation(k, 4), 0f, 1e-6f)
    }
}
