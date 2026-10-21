package core.simulation.thesis

import java.io.File
import scala.io.Source

/**
 * The campaign catalogue must describe exactly the runs of the thesis dataset (results/puyo/thesis_2026-10/):
 * same run ids 160-218, same scenarios in the same order and the same population sizes.
 */
class ThesisCampaignsSuite extends munit.FunSuite {

    private val dataset = new File("results/puyo/thesis_2026-10")

    /** (PopulationSize, Scenario, RunID) of a campaign CSV; scenario names contain unquoted commas. */
    private def readCsv(name: String): Seq[(Int, String, Long)] = {
        val src = Source.fromFile(new File(dataset, name), "UTF-8")
        try src.getLines().drop(1).filter(_.trim.nonEmpty).map { line =>
            val fields = line.split(",", -1)
            val size = fields.head.toInt
            val runId = fields(fields.length - 4).toLong
            val scenario = fields.slice(1, fields.length - 4).mkString(",")
            (size, scenario, runId)
        }.toList
        finally src.close()
    }

    test("run ids cover 160-218 once, in thesis order") {
        val ids = ThesisCampaigns.all.flatMap(_.runs.map(_.thesisRunId))
        assertEquals(ids, (160L to 218L).toSeq)
    }

    test("campaign sizes") {
        assertEquals(ThesisCampaigns.all.map(c => (c.label, c.runs.size)), Seq(
            "benchmark" -> 36, "extreme-distributions" -> 5, "tolerance-fluidity:1" -> 5,
            "tolerance-fluidity:2" -> 5, "tolerance-fluidity:3" -> 5, "consensus-resistance" -> 3))
    }

    test("every campaign matches its CSV in the thesis dataset") {
        assume(dataset.isDirectory, s"dataset not found at ${dataset.getAbsolutePath}")
        ThesisCampaigns.all.foreach { c =>
            val expected = readCsv(c.csvFile)
            val planned = c.runs.map(r => (r.populationSize, r.scenario.name, r.thesisRunId))
            assertEquals(planned, expected, s"${c.label} vs ${c.csvFile}")
        }
    }

    test("aliases and executions resolve") {
        assertEquals(ThesisCampaigns.resolve("phase1"), Seq(ThesisCampaigns.extremeDistributions))
        assertEquals(ThesisCampaigns.resolve("phase2").map(_.execution), Seq(1, 2, 3))
        assertEquals(ThesisCampaigns.resolve("tolerance-fluidity:2").map(_.runs.head.thesisRunId), Seq(206L))
        assertEquals(ThesisCampaigns.resolve("phase4").head.runs.map(_.thesisRunId), Seq(216L, 217L, 218L))
        intercept[IllegalArgumentException](ThesisCampaigns.resolve("phase3"))
    }

    test("iteration limits: 500 in the benchmark, 200 elsewhere") {
        assert(ThesisCampaigns.benchmark.runs.forall(_.iterationLimit == 500))
        assert(ThesisCampaigns.all.tail.flatMap(_.runs).forall(_.iterationLimit == 200))
    }
}
