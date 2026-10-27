package experiments.coevolution

import akka.actor.{Actor, ActorRef, Props}
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.silence.{SilenceEffects, SilenceStrategies}
import core.simulation.actors.*
import core.simulation.config.*
import io.db.DatabaseManager

import scala.concurrent.Promise

/** One line of a campaign CSV. */
final case class CampaignResult(
    populationSize: Int,
    scenario: String,
    runId: Long,
    totalDurationMs: Long,
    consensusCount: Int,
    avgRounds: Float
)

/**
 * Executes the planned runs of a campaign one after the other, with the same run configuration as the thesis
 * experiments: generated Barabási-Albert networks (m = 4, degree parameter 2.5), half MAJORITY/MEMORYLESS (SOM-) and
 * half DeGroot agents, DeGroot cognitive bias, epsilon = 0.001, base seed 42.
 *
 * With a database, each run is created in the legacy database; with `pinRunIds` it first receives its thesis run id,
 * so its networks (seed 42 + runId + k) are the ones of the thesis. Without a database (APP_SKIP_DATABASE=true) the
 * thesis run id is used directly, which also reproduces the networks.
 */
class CampaignOrchestrator(
    runs: Seq[PlannedRun],
    pinRunIds: Boolean,
    promise: Promise[List[CampaignResult]]
) extends Actor {
    import ThesisCampaigns.*

    private var results = List.empty[CampaignResult]
    private var next = 0
    private var currentRunID = 0L
    private var startTime = 0L
    private var lastConsensusCount = 0
    private var lastAvgRounds = 0.0f

    override def preStart(): Unit = {
        io.persistence.RoundRouter.setSavers(context, 100000)
        startNextRun()
    }

    private def startNextRun(): Unit = {
        if (next >= runs.length) {
            promise.success(results.reverse)
            context.stop(self)
            return
        }
        val run = runs(next)
        val n = run.populationSize
        val usesDatabase = !GlobalState.APP_MODE.skipDatabase
        println(s"\n>>> Run ${run.thesisRunId}: '${run.scenario.name}' | N = $n ($NetworksPerRun networks)")

        currentRunID =
            if (usesDatabase) {
                if (pinRunIds) DatabaseManager.pinNextLegacyRunId(run.thesisRunId)
                val id = DatabaseManager.createRun(
                    runMode = RunMode.GENERATED,
                    saveMode = 0.toByte, // FULL
                    numberOfNetworks = NetworksPerRun,
                    density = Some(Density),
                    degreeDistribution = Some(DegreeDistribution),
                    stopThreshold = StopThreshold,
                    iterationLimit = run.iterationLimit,
                    initialDistribution = run.scenario.dbDistribution
                ).getOrElse(throw new IllegalStateException(s"Could not create run ${run.thesisRunId} in the database"))
                if (pinRunIds && id != run.thesisRunId)
                    throw new IllegalStateException(s"Expected run id ${run.thesisRunId}, the database assigned $id")
                id
            } else run.thesisRunId
        startTime = System.currentTimeMillis()

        val majorityCount = n / 2
        val agentTypeCount = Array(
            (SilenceStrategies.MAJORITY, SilenceEffects.MEMORYLESS, majorityCount),
            (SilenceStrategies.DEGROOT, SilenceEffects.DEGROOT, n - majorityCount)
        )
        val edgeCount = (Density * (Density - 1)) + (n - Density) * (2 * Density)
        val agentBiases = Array((CognitiveBiases.DEGROOT, edgeCount))

        val metadata = RunMetadata(
            runID = currentRunID,
            runMode = RunMode.GENERATED,
            saveMode = if (usesDatabase) SaveModes.FULL else SaveModes.DEBUG,
            distribution = run.scenario.distribution,
            startTime = startTime,
            optionalMetaData = Some(OptionalMetadata(Some(Density), Some(DegreeDistribution))),
            agentLimit = 16777216,
            numberOfNetworks = NetworksPerRun,
            agentsPerNetwork = n,
            iterationLimit = run.iterationLimit,
            seed = BaseSeed,
            stopThreshold = StopThreshold,
            coevolutionConfig = run.scenario.coevolution
        )
        if (usesDatabase) {
            DatabaseManager.saveGeneratedRun(
                id = currentRunID,
                seed = BaseSeed,
                density = Density,
                iterationLimit = run.iterationLimit,
                totalNetworks = NetworksPerRun,
                agentsPerNetwork = n,
                stopThreshold = StopThreshold,
                agentTypeDistributions = agentTypeCount,
                cognitiveBiasDistributions = agentBiases
            )
        }
        val runActor: ActorRef = context.actorOf(Props(new Run(metadata, agentTypeCount, agentBiases)), s"Run_$currentRunID")
        runActor ! StartRun
    }

    def receive: Receive = {
        case RunCompleteWithStats(consensus, rounds) =>
            lastConsensusCount = consensus
            lastAvgRounds = rounds

        case RunComplete =>
            val run = runs(next)
            val duration = System.currentTimeMillis() - startTime
            val usesDatabase = !GlobalState.APP_MODE.skipDatabase
            println(s"<<< Run $currentRunID completed in $duration ms")
            results = CampaignResult(
                populationSize = run.populationSize,
                scenario = run.scenario.name,
                runId = currentRunID,
                totalDurationMs = duration,
                consensusCount = if (usesDatabase) DatabaseManager.getConsensusCount(currentRunID) else lastConsensusCount,
                avgRounds = if (usesDatabase) DatabaseManager.getAvgRounds(currentRunID) else lastAvgRounds
            ) :: results
            next += 1
            startNextRun()
    }
}
