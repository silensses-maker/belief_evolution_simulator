package experiments.coevolution

import core.simulation.actors.CoevolutionConfig
import utils.rng.distributions.{BiModal, Distribution, RadicalMinority, Uniform}

/**
 * The experiments of the thesis "Extensión de los Modelos de Evolución de la Opinión SOM+ y SOM− a Redes
 * Coevolutivas" (J. E. Puyo Mora, Universidad del Valle, 2026), defined once, exactly as they were run.
 *
 * Every campaign lists its runs with the run id they had in the thesis database (`promueva_legacy`, runs 160-218).
 * Network seeds are `BaseSeed + runId + k` (k = 1, 2, 3), so `RunThesisCampaign` pins each run id on a fresh
 * database and rebuilds the same networks. The thesis numbers its experimental phases 1-4; phase 3 (small-world
 * evolution) is an analysis of the phase 1 and 2 networks and has no simulation of its own
 * (`src/scripts/thesis/small_world.py`).
 *
 * Frozen: these campaigns reproduce a published thesis (tag `thesis-2026-10`). Add new coevolution experiments in a
 * separate object instead of editing them (see `src/main/scala/experiments/README.md`).
 */
final case class Scenario(
    name: String,                         // as written in the campaign CSV
    distribution: Distribution,           // initial beliefs
    dbDistribution: String,               // runs.initial_distribution
    coevolution: Option[CoevolutionConfig] // None = static SOM network
)

/** One run of a campaign: `NetworksPerRun` networks of `populationSize` agents. */
final case class PlannedRun(thesisRunId: Long, populationSize: Int, scenario: Scenario, iterationLimit: Int)

final case class Campaign(
    key: String,           // command-line name
    alias: Option[String], // thesis phase, also accepted on the command line
    execution: Int,        // 1 unless the campaign was repeated (tolerance-fluidity: 1-3)
    thesisSection: String,
    description: String,
    csvFile: String,
    runs: Seq[PlannedRun]
) {
    def label: String = if (execution > 1 || key == "tolerance-fluidity") s"$key:$execution" else key
}

object ThesisCampaigns {
    val NetworksPerRun: Int = 3
    val Density: Int = 4              // Barabási-Albert m
    val DegreeDistribution: Float = 2.5f
    val StopThreshold: Float = 0.001f // epsilon
    val BaseSeed: Long = 42L
    val PopulationSizes: Seq[Int] = Seq(64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384)

    private def coevolution(pBreak: Float, pCreate: Float, strategy: Int,
                            tolerance: Option[(Float, Float, Float)] = None): Option[CoevolutionConfig] =
        Some(CoevolutionConfig(pBreak = pBreak, pCreate = pCreate, rewiringStrategy = strategy,
            assortivityStopThreshold = 1.1f, fragmentationStop = false, asymmetricTolerance = tolerance))

    private val bimodal = BiModal(peak1 = 0.05, peak2 = 0.95, lower = 0.0, upper = 1.0, stdDev = Some(0.02))
    private val radical = RadicalMinority(majorityBelief = 0.5, radicalBelief = 1.0, radicalFraction = 0.1, stdDev = 0.02)

    private def sequential(firstId: Long, n: Int, scenarios: Seq[Scenario], limit: Int): Seq[PlannedRun] =
        scenarios.zipWithIndex.map { case (s, i) => PlannedRun(firstId + i, n, s, limit) }

    /** Sec. 5.3-5.4: four regimes x nine sizes, size-major order (runs 160-195). */
    val benchmark: Campaign = {
        val scenarios = Seq(
            Scenario("Baseline (Static)", Uniform, "uniform", None),
            Scenario("Scenario A (Pure Creation, Prop C)", Uniform, "uniform", coevolution(0.0f, 0.02f, 0)),
            Scenario("Scenario B (Uniform, Prop A)", Uniform, "uniform", coevolution(0.2f, 0.02f, 0)),
            Scenario("Scenario B (Homophilic, Prop B)", Uniform, "uniform", coevolution(0.2f, 0.02f, 1))
        )
        val runs = for {
            (n, i) <- PopulationSizes.zipWithIndex
            (s, j) <- scenarios.zipWithIndex
        } yield PlannedRun(160L + i * scenarios.length + j, n, s, 500)
        Campaign("benchmark", None, 1, "5.3-5.4", "Parametric benchmark, N = 64 ... 16 384, four regimes",
            "parametric_results.csv", runs)
    }

    /** Sec. 5.5.1, phase 1: extreme initial distributions, N = 512 (runs 196-200). */
    val extremeDistributions: Campaign = Campaign("extreme-distributions", Some("phase1"), 1, "5.5.1",
        "Uniform baseline, bimodal 50/50 polarization, 10% radical minority; Prop. A and B",
        "edge_case_results_phase1.csv",
        sequential(196L, 512, Seq(
            Scenario("Baseline Uniform (Homophilic)", Uniform, "uniform", coevolution(0.2f, 0.02f, 1)),
            Scenario("Case 1A: Extreme Polarization (Homophilic)", bimodal, "bimodal", coevolution(0.2f, 0.02f, 1)),
            Scenario("Case 1A: Extreme Polarization (Uniform)", bimodal, "bimodal", coevolution(0.2f, 0.02f, 0)),
            Scenario("Case 1B: Radical Minority (Homophilic)", radical, "custom", coevolution(0.2f, 0.02f, 1)),
            Scenario("Case 1B: Radical Minority (Uniform)", radical, "custom", coevolution(0.2f, 0.02f, 0))
        ), 200))

    private val toleranceFluidityScenarios = Seq(
        Scenario("Baseline Uniform (tau=0.10, Prop B)", Uniform, "uniform", coevolution(0.2f, 0.02f, 1)),
        Scenario("Case 2A: Intolerant Hubs (Homophilic, Prop B)", Uniform, "uniform",
            coevolution(0.2f, 0.02f, 1, Some((0.01f, 0.50f, 0.05f)))),
        Scenario("Case 2A: Intolerant Hubs (Uniform, Prop A)", Uniform, "uniform",
            coevolution(0.2f, 0.02f, 0, Some((0.01f, 0.50f, 0.05f)))),
        Scenario("Case 2B: Absolute Fluidity (Homophilic, Prop B)", Uniform, "uniform", coevolution(1.0f, 1.0f, 1)),
        Scenario("Case 2B: Absolute Fluidity (Uniform, Prop A)", Uniform, "uniform", coevolution(1.0f, 1.0f, 0))
    )

    /** Sec. 5.5.2, phase 2: intolerant hubs and absolute fluidity, N = 512, three executions (runs 201-215). */
    def toleranceFluidity(execution: Int): Campaign = {
        require(1 <= execution && execution <= 3, "tolerance-fluidity has executions 1, 2 and 3")
        Campaign("tolerance-fluidity", Some("phase2"), execution, "5.5.2",
            s"Intolerant hubs and absolute fluidity, execution $execution of 3",
            if (execution == 1) "edge_case_results_phase2.csv" else s"edge_case_results_phase2_exec$execution.csv",
            sequential(201L + 5 * (execution - 1), 512, toleranceFluidityScenarios, 200))
    }

    /** Sec. 5.5.4, phase 4: resistance to consensus on a bimodal population, N = 512 (runs 216-218). */
    val consensusResistance: Campaign = Campaign("consensus-resistance", Some("phase4"), 1, "5.5.4",
        "Bimodal baseline, ablation (P_create = 0) and sub-critical tolerance (tau = 0.02)",
        "edge_case_results_phase4.csv",
        sequential(216L, 512, Seq(
            Scenario("Baseline Bimodal (tau=0.20, pCreate=0.05, Prop B)", bimodal, "bimodal",
                coevolution(0.5f, 0.05f, 1, Some((0.20f, 0.20f, 1.0f)))),
            Scenario("Case A: Ablation (pCreate=0.00, tau=0.20, Prop B)", bimodal, "bimodal",
                coevolution(0.5f, 0.00f, 1, Some((0.20f, 0.20f, 1.0f)))),
            Scenario("Case B: Sub-Critical (tau=0.02, pCreate=0.05, Prop B)", bimodal, "bimodal",
                coevolution(0.5f, 0.05f, 1, Some((0.02f, 0.02f, 1.0f))))
        ), 200))

    /** Every simulated campaign, in the order they were run for the thesis. */
    val all: Seq[Campaign] =
        Seq(benchmark, extremeDistributions) ++ (1 to 3).map(toleranceFluidity) :+ consensusResistance

    /** Resolves a command-line name: key or alias, optionally `:execution` (e.g. `tolerance-fluidity:2`). */
    def resolve(name: String): Seq[Campaign] = {
        val (base, exec) = name.split(":", 2) match {
            case Array(b, e) => (b, Some(e.toInt))
            case Array(b) => (b, None)
        }
        val matching = all.filter(c => c.key == base || c.alias.contains(base))
        if (matching.isEmpty) throw new IllegalArgumentException(s"Unknown campaign '$name'")
        exec.fold(matching)(e => matching.filter(_.execution == e))
    }
}
