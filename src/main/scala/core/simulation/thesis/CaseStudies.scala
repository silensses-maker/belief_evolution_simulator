package core.simulation.thesis

import akka.actor.{ActorSystem, Props}
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.silence.{SilenceEffects, SilenceStrategies}
import core.simulation.actors.{CoevolutionConfig, Monitor, RunCustomNetwork}
import core.simulation.config.SaveModes
import io.web.{CustomRunInfo, SimulationCache}

import java.io.PrintWriter

/**
 * Chapter 5 case studies: four hand-built networks (exact B^0, tau and I^0 of the thesis), static and
 * coevolutionary, run through the SiLEnSeSS actors without a database. Seed of repetition k = k; the coevolutionary
 * runs mix seed and round (`CoevolutionConfig.mixedRoundSeed`), so the 20 repetitions are independent.
 *
 * The diagonal of I^0 is the implicit self-influence, as in generated networks; each off-diagonal entry I[i][j] > 0 is
 * an edge receiver i <- emitter j with that weight. A spec is `case:static` or `case:coev:pBreak:pCreate:strategy`.
 * Results go to stdout and, as `=== RESULT` / `=== TOPOLOGY` lines, to the results file. With
 * APP_SIMULATION_LOGS=true the per-round log (speaking states, beliefs) is printed too; `case_seeds.py` reads it.
 */
object CaseStudies {
    final case class Case(name: String, b0: Array[Float], tau: Array[Float], influence: Array[Array[Float]], memory: Boolean)

    private def f(xs: Double*): Array[Float] = xs.map(_.toFloat).toArray

    // Influence graphs of Aranda et al. (ICTAC 2025): Fig. 2 (case 1), Fig. 4 (case 2) and Fig. 3 (case 3). There an
    // arrow j -> i carries I_ji, the influence of j on i; here it is row i (receiver), column j (emitter).
    val cases: Map[String, Case] = Map(
        // Case 1 (SOM-): bridge node between two opinion blocks (ICTAC Fig. 2)
        "caso1" -> Case("caso1", f(1.0, 0.8, 0.5, 0.2, 0.0), f(0.2, 0.15, 0.1, 0.15, 0.2), Array(
            f(0.5, 0.4, 0.1, 0.0, 0.0),
            f(0.4, 0.5, 0.1, 0.0, 0.0),
            f(0.2, 0.2, 0.2, 0.2, 0.2),
            f(0.0, 0.0, 0.1, 0.5, 0.4),
            f(0.0, 0.0, 0.1, 0.4, 0.5)), memory = false),
        // Case 2 (SOM-): vocal minority (agents 3 and 4, numbered from 0) over a disconnected moderate majority (ICTAC Fig. 4)
        "caso2" -> Case("caso2", f(0.1, 0.2, 0.15, 1.0, 0.85, 0.25, 0.3, 0.05), f(0.1, 0.05, 0.1, 0.85, 0.6, 0.05, 0.1, 0.05), Array(
            f(0.80, 0.00, 0.00, 0.20, 0.00, 0.00, 0.00, 0.00),
            f(0.00, 0.80, 0.00, 0.20, 0.00, 0.00, 0.00, 0.00),
            f(0.00, 0.00, 0.80, 0.20, 0.00, 0.00, 0.00, 0.00),
            f(0.05, 0.10, 0.10, 0.50, 0.25, 0.00, 0.00, 0.00),
            f(0.00, 0.00, 0.00, 0.50, 0.25, 0.10, 0.10, 0.05),
            f(0.00, 0.00, 0.00, 0.00, 0.20, 0.80, 0.00, 0.00),
            f(0.00, 0.00, 0.00, 0.00, 0.20, 0.00, 0.80, 0.00),
            f(0.00, 0.00, 0.00, 0.00, 0.20, 0.00, 0.00, 0.80)), memory = false),
        // Case 3 (SOM+): polarized clique with memory, counter-example to consensus (ICTAC Fig. 3)
        "caso3" -> Case("caso3", f(1.0, 0.9, 0.1, 0.0), f(0.1, 0.1, 0.1, 0.1), Array(
            f(0.10, 0.35, 0.40, 0.15),
            f(0.15, 0.30, 0.40, 0.15),
            f(0.15, 0.40, 0.30, 0.15),
            f(0.15, 0.40, 0.35, 0.10)), memory = true),
        // Case 4 (SOM+): hidden consensus, private beliefs converge while everyone is silent (ICTAC Fig. 5)
        "caso4" -> Case("caso4", f(1.0, 0.9, 0.1, 0.0), f(0.2, 0.1, 0.05, 0.15), Array(
            f(0.24, 0.40, 0.21, 0.15),
            f(0.32, 0.32, 0.21, 0.15),
            f(0.15, 0.21, 0.32, 0.32),
            f(0.15, 0.21, 0.40, 0.24)), memory = true)
    )

    /** The specs and repetitions of the thesis (Sec. 5.1-5.3 and Annex A). */
    val ThesisRepetitions: Int = 20
    val ThesisSpecs: Seq[String] = Seq(
        "caso1:static", "caso2:static", "caso3:static",
        "caso1:coev:1.0:1.0:1", "caso1:coev:0.5:0.05:1",
        "caso2:coev:1.0:1.0:1", "caso2:coev:0.5:0.05:1",
        "caso3:coev:1.0:1.0:1", "caso3:coev:0.5:0.05:1",
        "caso4:static", "caso4:coev:1.0:1.0:1", "caso4:coev:0.5:0.05:1")

    private def runInfo(c: Case, runId: Long, coev: Option[CoevolutionConfig]): CustomRunInfo = {
        val n = c.b0.length
        val targets = scala.collection.mutable.ArrayBuffer[Int]()
        val weights = scala.collection.mutable.ArrayBuffer[Float]()
        val offsets = new Array[Int](n)
        for (i <- 0 until n) {
            for (j <- 0 until n if j != i && c.influence(i)(j) > 0f) { targets += j; weights += c.influence(i)(j) }
            offsets(i) = targets.length
        }
        CustomRunInfo(
            runID = runId, stopThreshold = 0.001f, iterationLimit = 200, saveMode = SaveModes.DEBUG,
            networkName = s"${c.name}-$runId", agentBeliefs = c.b0.clone(), agentToleranceRadii = c.tau.clone(),
            agentToleranceOffsets = Array.fill(n)(0f),
            agentSilenceStrategy = Array.fill(n)(SilenceStrategies.MAJORITY),
            agentSilenceEffect = Array.fill(n)(if (c.memory) SilenceEffects.MEMORY else SilenceEffects.MEMORYLESS),
            agentNames = Array.tabulate(n)(i => s"A$i"), indexOffset = offsets, target = targets.toArray,
            influences = weights.toArray, bias = Array.fill(targets.length)(CognitiveBiases.DEGROOT),
            coevolutionConfig = coev, persistFrames = false)
    }

    /** Runs every spec `repetitions` times; returns the number of runs that did not finish within a minute. */
    def run(system: ActorSystem, repetitions: Int, specs: Seq[String], resultsFile: java.io.File): Int = {
        val monitor = system.actorOf(Props(new Monitor), "Monitor")
        val out = new PrintWriter(resultsFile, "UTF-8")
        var runId = 7_000_000_000L
        var timeouts = 0
        def emit(line: String): Unit = { println(line); out.println(line) }
        try {
            for (spec <- specs; rep <- 1 to repetitions) {
                runId += 1
                val p = spec.split(":")
                val c = cases.getOrElse(p(0), throw new IllegalArgumentException(s"Unknown case '${p(0)}'"))
                val coev = if (p(1) == "static") None
                    else Some(CoevolutionConfig(p(2).toFloat, p(3).toFloat, p(4).toInt, mixedRoundSeed = true))
                println(s"\n=== RUN $runId spec=$spec rep=$rep seed=$rep")
                monitor ! RunCustomNetwork(runInfo(c, runId, coev).copy(seed = rep.toLong))
                var waited = 0
                var done = false
                while (!done && waited < 60000) {
                    Thread.sleep(50); waited += 50
                    done = SimulationCache.getNetworkIds(runId).exists(id =>
                        SimulationCache.getResults(runId, id).isDefined && SimulationCache.getFinalTopology(runId, id).isDefined)
                }
                Thread.sleep(100)
                SimulationCache.getNetworkIds(runId).foreach { id =>
                    SimulationCache.getResults(runId, id).foreach { r =>
                        emit(f"=== RESULT spec=$spec rep=$rep finalRound=${r.finalRound} consensus=${r.consensus} " +
                            s"private=${r.finalBeliefs.map(x => f"$x%.4f").mkString("[", ",", "]")} " +
                            s"public=${r.publicBeliefs.map(x => f"$x%.4f").mkString("[", ",", "]")}")
                        SimulationCache.getFinalTopology(runId, id).foreach { t =>
                            val edges = (0 until t.agentCount).flatMap { i =>
                                val s = if (i == 0) 0 else t.indexOffset(i - 1)
                                (s until t.indexOffset(i)).map(k => f"$i<-${t.neighborsRefs(k)}:${t.neighborsWeights(k)}%.3f")
                            }
                            emit(s"=== TOPOLOGY spec=$spec rep=$rep edges=${edges.mkString(" ")}")
                        }
                    }
                }
                if (!done) { timeouts += 1; println(s"=== TIMEOUT spec=$spec rep=$rep (network failed or did not finish)") }
            }
        } finally out.close()
        timeouts
    }
}
