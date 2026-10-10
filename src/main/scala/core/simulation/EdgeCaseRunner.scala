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

case class EdgeCaseScenarioConfig(
    name: String,
    distribution: Distribution,
    dbDistributionStr: String,
    coevolutionConfig: Option[CoevolutionConfig]
)

case class EdgeCaseResult(
    populationSize: Int,
    scenarioName: String,
    runId: Long,
    totalDurationMs: Long,
    consensusCount: Int,
    avgRounds: Float
)

class EdgeCaseOrchestrator(
    populationSize: Int,
    scenarios: Seq[EdgeCaseScenarioConfig],
    networksPerConfig: Int,
    promise: Promise[List[EdgeCaseResult]]
) extends Actor {
    
    var results = List.empty[EdgeCaseResult]
    var currentScenarioIdx = 0
    var currentRunActor: ActorRef = null
    var currentRunID: Long = 0L
    var startTime: Long = 0L
    
    def startNextRun(): Unit = {
        if (currentScenarioIdx >= scenarios.length) {
            promise.success(results.reverse)
            context.stop(self)
            return
        }
        
        val scenario = scenarios(currentScenarioIdx)
        val N = populationSize
        
        println(s"\n>>> [EdgeCaseRunner-Phase2] Running: '${scenario.name}' | N = $N ($networksPerConfig networks)")
        
        val densityParam = 4
        
        if (!GlobalState.APP_MODE.skipDatabase) {
            val idOpt = DatabaseManager.createRun(
                runMode = RunMode.GENERATED,
                saveMode = 0.toByte, // FULL saveMode
                numberOfNetworks = networksPerConfig,
                density = Some(densityParam),
                degreeDistribution = Some(2.5f),
                stopThreshold = 0.001f,
                iterationLimit = 200,
                initialDistribution = scenario.dbDistributionStr
            )
            currentRunID = idOpt.getOrElse(SnowflakeID.generateId())
        } else {
            currentRunID = SnowflakeID.generateId()
        }
        startTime = System.currentTimeMillis()
        
        val majorityCount = N / 2
        val degrootCount = N - majorityCount
        val agentTypeCount = Array(
            (SilenceStrategies.MAJORITY, SilenceEffects.MEMORYLESS, majorityCount),
            (SilenceStrategies.DEGROOT, SilenceEffects.DEGROOT, degrootCount)
        )
        
        val edgeCount = (densityParam * (densityParam - 1)) + (N - densityParam) * (2 * densityParam)
        val agentBiases = Array(
            (CognitiveBiases.DEGROOT, edgeCount)
        )
        
        val metadata = RunMetadata(
            runID = currentRunID,
            runMode = RunMode.GENERATED,
            saveMode = if (GlobalState.APP_MODE.skipDatabase) SaveModes.DEBUG else SaveModes.FULL,
            distribution = scenario.distribution,
            startTime = startTime,
            optionalMetaData = Some(OptionalMetadata(Some(densityParam), Some(2.5f))),
            agentLimit = 16777216,
            numberOfNetworks = networksPerConfig,
            agentsPerNetwork = N,
            iterationLimit = 200,
            seed = 42L,
            stopThreshold = 0.001f,
            coevolutionConfig = scenario.coevolutionConfig
        )
        
        if (!GlobalState.APP_MODE.skipDatabase) {
            DatabaseManager.saveGeneratedRun(
                id = currentRunID,
                seed = 42L,
                density = densityParam,
                iterationLimit = 200,
                totalNetworks = networksPerConfig,
                agentsPerNetwork = N,
                stopThreshold = 0.001f,
                agentTypeDistributions = agentTypeCount,
                cognitiveBiasDistributions = agentBiases
            )
        }
        
        currentRunActor = context.actorOf(
            Props(new Run(metadata, agentTypeCount, agentBiases)),
            s"Run_Edge2_${currentRunID}"
        )
        
        currentRunActor ! StartRun
    }
    
    override def preStart(): Unit = {
        io.persistence.RoundRouter.setSavers(context, 100000)
        startNextRun()
    }
    
    var lastConsensusCount: Int = 0
    var lastAvgRounds: Float = 0.0f
    
    def receive: Receive = {
        case RunCompleteWithStats(cCount, aRnd) =>
            lastConsensusCount = cCount
            lastAvgRounds = aRnd

        case RunComplete =>
            val duration = System.currentTimeMillis() - startTime
            val scenario = scenarios(currentScenarioIdx)
            println(s"<<< [EdgeCaseRunner-Phase2] Completed '${scenario.name}' in ${duration} ms (Run ID: $currentRunID)")
            
            val consensusCount = if (!GlobalState.APP_MODE.skipDatabase) {
                DatabaseManager.getConsensusCount(currentRunID)
            } else lastConsensusCount
            
            val avgRounds = if (!GlobalState.APP_MODE.skipDatabase) {
                DatabaseManager.getAvgRounds(currentRunID)
            } else lastAvgRounds
            
            results = EdgeCaseResult(
                populationSize = populationSize,
                scenarioName = scenario.name,
                runId = currentRunID,
                totalDurationMs = duration,
                consensusCount = consensusCount,
                avgRounds = avgRounds
            ) :: results
            
            currentScenarioIdx += 1
            startNextRun()
    }
}

object EdgeCaseRunner {
    def main(args: Array[String]): Unit = {
        println("=========================================================================")
        println("         Belief Evolution Simulator: Edge Case Tests (Phase 2)           ")
        println("=========================================================================")
        
        val system = ActorSystem("EdgeCaseSystem2", ConfigFactory.load().getConfig("app-dispatcher"))
        val promise = Promise[List[EdgeCaseResult]]()
        
        val populationSize = 512
        val networksPerConfig = 3
        
        // Define Scenarios for Phase 2: Asymmetric Tolerance and Absolute Fluidity
        val scenarios = Seq(
            // Baseline: Uniform Tolerance tau = 0.10, Prop B
            EdgeCaseScenarioConfig(
                name = "Baseline Uniform (tau=0.10, Prop B)",
                distribution = Uniform,
                dbDistributionStr = "uniform",
                coevolutionConfig = Some(CoevolutionConfig(pBreak = 0.2f, pCreate = 0.02f, rewiringStrategy = 1, assortivityStopThreshold = 1.1f, fragmentationStop = false))
            ),
            // Case 2A: Intolerant Hubs (tau_hub=0.01, tau_periph=0.50, 5% hubs, Homophilic Prop B)
            EdgeCaseScenarioConfig(
                name = "Case 2A: Intolerant Hubs (Homophilic, Prop B)",
                distribution = Uniform,
                dbDistributionStr = "uniform",
                coevolutionConfig = Some(CoevolutionConfig(
                    pBreak = 0.2f, 
                    pCreate = 0.02f, 
                    rewiringStrategy = 1, 
                    assortivityStopThreshold = 1.1f, 
                    fragmentationStop = false,
                    asymmetricTolerance = Some((0.01f, 0.50f, 0.05f))
                ))
            ),
            // Case 2A: Intolerant Hubs (Uniform Rewiring, Prop A)
            EdgeCaseScenarioConfig(
                name = "Case 2A: Intolerant Hubs (Uniform, Prop A)",
                distribution = Uniform,
                dbDistributionStr = "uniform",
                coevolutionConfig = Some(CoevolutionConfig(
                    pBreak = 0.2f, 
                    pCreate = 0.02f, 
                    rewiringStrategy = 0, 
                    assortivityStopThreshold = 1.1f, 
                    fragmentationStop = false,
                    asymmetricTolerance = Some((0.01f, 0.50f, 0.05f))
                ))
            ),
            // Case 2B: Absolute Fluidity (pBreak = 1.0, pCreate = 1.0, Homophilic Prop B)
            EdgeCaseScenarioConfig(
                name = "Case 2B: Absolute Fluidity (Homophilic, Prop B)",
                distribution = Uniform,
                dbDistributionStr = "uniform",
                coevolutionConfig = Some(CoevolutionConfig(
                    pBreak = 1.0f, 
                    pCreate = 1.0f, 
                    rewiringStrategy = 1, 
                    assortivityStopThreshold = 1.1f, 
                    fragmentationStop = false
                ))
            ),
            // Case 2B: Absolute Fluidity (pBreak = 1.0, pCreate = 1.0, Uniform Prop A)
            EdgeCaseScenarioConfig(
                name = "Case 2B: Absolute Fluidity (Uniform, Prop A)",
                distribution = Uniform,
                dbDistributionStr = "uniform",
                coevolutionConfig = Some(CoevolutionConfig(
                    pBreak = 1.0f, 
                    pCreate = 1.0f, 
                    rewiringStrategy = 0, 
                    assortivityStopThreshold = 1.1f, 
                    fragmentationStop = false
                ))
            )
        )
        
        val orchestrator = system.actorOf(
            Props(new EdgeCaseOrchestrator(populationSize, scenarios, networksPerConfig, promise)),
            "EdgeCaseOrchestrator2"
        )
        
        val results = Await.result(promise.future, Duration.Inf)
        
        val csvFile = "edge_case_results_phase2.csv"
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
        println("                       EDGE CASE RESULTS (PHASE 2)                       ")
        println("=========================================================================")
        println(f"| ${"Size"}%-6s | ${"Scenario"}%-45s | ${"Run ID"}%-20s | ${"Time (ms)"}%-9s | ${"Consensus"}%-9s | ${"Avg Rnd"}%-7s |")
        println("+--------+-----------------------------------------------+----------------------+-----------+-----------+---------+")
        results.foreach { r =>
            println(f"| ${r.populationSize}%-6d | ${r.scenarioName}%-45s | ${r.runId}%-20d | ${r.totalDurationMs}%-9d | ${s"${r.consensusCount}/$networksPerConfig"}%-9s | ${r.avgRounds}%-7.1f |")
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
