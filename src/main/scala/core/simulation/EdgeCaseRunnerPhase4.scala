package core.simulation

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.silence.{SilenceEffects, SilenceStrategies}
import core.simulation.actors.*
import core.simulation.config.*
import io.db.DatabaseManager
import utils.datastructures.SnowflakeID
import utils.rng.distributions.*
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*
import java.io.{FileWriter, PrintWriter}

object EdgeCaseRunnerPhase4 {
    def main(args: Array[String]): Unit = {
        println("=========================================================================")
        println("   Belief Evolution Simulator: Resistance to Consensus (Phase 4)         ")
        println("=========================================================================")
        
        val system = ActorSystem("EdgeCaseSystemPhase4", ConfigFactory.load().getConfig("app-dispatcher"))
        val promise = Promise[List[EdgeCaseResult]]()
        
        val populationSize = 512
        val networksPerConfig = 3
        
        // Define Scenarios for Phase 4: Resistance to Consensus & Boundary Conditions
        val scenarios = Seq(
            // Baseline Reference: Bimodal (tau = 0.20, pCreate = 0.05, Prop B)
            EdgeCaseScenarioConfig(
                name = "Baseline Bimodal (tau=0.20, pCreate=0.05, Prop B)",
                distribution = BiModal(0.05, 0.95, 0, 1, Some(0.02)),
                dbDistributionStr = "bimodal",
                coevolutionConfig = Some(CoevolutionConfig(
                    pBreak = 0.5f, 
                    pCreate = 0.05f, 
                    rewiringStrategy = 1, 
                    assortivityStopThreshold = 1.1f, 
                    fragmentationStop = false,
                    asymmetricTolerance = Some((0.20f, 0.20f, 1.0f)) // Uniform tau = 0.20
                ))
            ),
            // Experiment A: Ablation Study (pCreate = 0.00, tau = 0.20, Prop B) -> Structural Fracture & Isolation
            EdgeCaseScenarioConfig(
                name = "Case A: Ablation (pCreate=0.00, tau=0.20, Prop B)",
                distribution = BiModal(0.05, 0.95, 0, 1, Some(0.02)),
                dbDistributionStr = "bimodal",
                coevolutionConfig = Some(CoevolutionConfig(
                    pBreak = 0.5f, 
                    pCreate = 0.00f, // Total ablation of healing/creation
                    rewiringStrategy = 1, 
                    assortivityStopThreshold = 1.1f, 
                    fragmentationStop = false,
                    asymmetricTolerance = Some((0.20f, 0.20f, 1.0f))
                ))
            ),
            // Experiment B: Sub-Critical Tolerance (tau = 0.02, pCreate = 0.05, Prop B) -> Metastability / Bimodality
            EdgeCaseScenarioConfig(
                name = "Case B: Sub-Critical (tau=0.02, pCreate=0.05, Prop B)",
                distribution = BiModal(0.05, 0.95, 0, 1, Some(0.02)),
                dbDistributionStr = "bimodal",
                coevolutionConfig = Some(CoevolutionConfig(
                    pBreak = 0.5f, 
                    pCreate = 0.05f, // Active rewiring
                    rewiringStrategy = 1, 
                    assortivityStopThreshold = 1.1f, 
                    fragmentationStop = false,
                    asymmetricTolerance = Some((0.02f, 0.02f, 1.0f)) // Ultra-strict tau = 0.02
                ))
            )
        )
        
        val orchestrator = system.actorOf(
            Props(new EdgeCaseOrchestrator(populationSize, scenarios, networksPerConfig, promise)),
            "EdgeCaseOrchestratorPhase4"
        )
        
        val results = Await.result(promise.future, Duration.Inf)
        
        val csvFile = "edge_case_results_phase4.csv"
        val writer = new PrintWriter(new FileWriter(csvFile))
        try {
            writer.println("PopulationSize,Scenario,RunID,TotalDurationMs,ConsensusCount,AvgRounds")
            results.foreach { r =>
                writer.println(s"${r.populationSize},${r.scenarioName},${r.runId},${r.totalDurationMs},${r.consensusCount},${r.avgRounds}")
            }
        } finally {
            writer.close()
        }
        
        println("\n=========================================================================")
        println("                       EDGE CASE RESULTS (PHASE 4)                       ")
        println("=========================================================================")
        println(f"| ${"Size"}%-6s | ${"Scenario"}%-48s | ${"Run ID"}%-20s | ${"Time (ms)"}%-9s | ${"Consensus"}%-9s | ${"Avg Rnd"}%-7s |")
        println("+--------+--------------------------------------------------+----------------------+-----------+-----------+---------+")
        results.foreach { r =>
            println(f"| ${r.populationSize}%-6d | ${r.scenarioName}%-48s | ${r.runId}%-20d | ${r.totalDurationMs}%-9d | ${s"${r.consensusCount}/$networksPerConfig"}%-9s | ${r.avgRounds}%-7.1f |")
        }
        println("=========================================================================")
        println(s"Results saved to '$csvFile'")
        
        try {
            Await.ready(system.terminate(), 10.seconds)
        } catch {
            case _: Exception => println("Shutdown timed out.")
        }
    }
}
