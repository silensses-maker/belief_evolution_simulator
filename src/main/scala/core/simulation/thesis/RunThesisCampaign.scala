package core.simulation.thesis

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import core.simulation.config.GlobalState
import io.db.DatabaseManager

import java.io.{File, PrintWriter}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Promise}

/**
 * Entry point for the thesis experiments.
 *
 * {{{
 * sbt "runMain core.simulation.thesis.RunThesisCampaign list"
 * sbt "runMain core.simulation.thesis.RunThesisCampaign all --out results/replica"
 * sbt "runMain core.simulation.thesis.RunThesisCampaign benchmark --sizes 64,128"
 * sbt "runMain core.simulation.thesis.RunThesisCampaign case-studies"     (APP_SKIP_DATABASE=true, APP_LEGACY_DB=false)
 * }}}
 *
 * Campaigns: benchmark, extreme-distributions (phase1), tolerance-fluidity[:1|2|3] (phase2),
 * consensus-resistance (phase4), all (the five simulated campaigns in thesis order), case-studies (Ch. 5, no
 * database). Phase 3 (small world) is an analysis: src/scripts/thesis/small_world.py.
 *
 * Database mode (APP_LEGACY_DB=true, APP_SKIP_DATABASE=false): each run is stored in the legacy database under its
 * thesis run id, so the networks are those of the thesis; the database must not already contain those ids (use a
 * fresh one, e.g. the Docker setup in src/scripts/thesis/README.md). `--no-pin` lets the database choose the ids
 * instead: the campaign is then a new sample, not a replica. Without a database the thesis ids are used directly.
 * Each campaign writes its CSV (same format and name as results/puyo/thesis_2026-10/) to --out.
 */
object RunThesisCampaign {
    private val Usage =
        """usage: RunThesisCampaign list
          |       RunThesisCampaign <campaign> [<campaign> ...] [--out DIR] [--sizes N1,N2,...] [--no-pin]
          |       RunThesisCampaign case-studies [--out DIR] [--repetitions K]
          |campaigns: benchmark, extreme-distributions (phase1), tolerance-fluidity[:1|2|3] (phase2),
          |           consensus-resistance (phase4), all""".stripMargin

    def main(args: Array[String]): Unit = {
        // sys.exit in every case: an exception alone would leave the actor system's threads keeping the JVM alive
        val code = try run(args.toList) catch {
            case e: IllegalArgumentException => System.err.println(s"error: ${e.getMessage}\n$Usage"); 2
            case e: IllegalStateException => System.err.println(s"error: ${e.getMessage}"); 1
            case scala.util.control.NonFatal(e) => e.printStackTrace(); 1
        }
        sys.exit(code)
    }

    private def run(args: List[String]): Int = {
        var out = "results/replica"
        var sizes: Option[Set[Int]] = None
        var pin = true
        var repetitions = CaseStudies.ThesisRepetitions
        val names = scala.collection.mutable.ArrayBuffer[String]()
        def loop(rest: List[String]): Unit = rest match {
            case "--out" :: dir :: tail => out = dir; loop(tail)
            case "--sizes" :: list :: tail => sizes = Some(list.split(",").map(_.trim.toInt).toSet); loop(tail)
            case "--no-pin" :: tail => pin = false; loop(tail)
            case "--repetitions" :: k :: tail => repetitions = k.toInt; loop(tail)
            case flag :: _ if flag.startsWith("--") => throw new IllegalArgumentException(s"Unknown option $flag")
            case name :: tail => names += name; loop(tail)
            case Nil =>
        }
        loop(args)
        if (names.isEmpty) throw new IllegalArgumentException("No campaign given")

        if (names.toSeq == Seq("list")) { list(); return 0 }
        val outDir = new File(out)
        outDir.mkdirs()
        if (!outDir.isDirectory || !outDir.canWrite)
            throw new IllegalStateException(s"Cannot write to the output folder ${outDir.getAbsolutePath}")

        if (names.contains("case-studies")) {
            if (names.size > 1) throw new IllegalArgumentException("Run case-studies on its own (it needs APP_SKIP_DATABASE=true)")
            if (!GlobalState.APP_MODE.skipDatabase)
                throw new IllegalStateException("case-studies runs without a database: set APP_SKIP_DATABASE=true and APP_LEGACY_DB=false")
            val system = actorSystem("ThesisCaseStudies")
            val file = new File(outDir, "case_studies_results.txt")
            val timeouts = CaseStudies.run(system, repetitions, CaseStudies.ThesisSpecs, file)
            println(s"\nCase studies written to ${file.getPath}${if (timeouts > 0) s" ($timeouts timeouts)" else ""}")
            shutdown(system)
            return if (timeouts > 0) 1 else 0
        }

        val campaigns = names.toSeq.flatMap(n => if (n == "all") ThesisCampaigns.all else ThesisCampaigns.resolve(n)).distinct
        val selected = campaigns.map(c => c.copy(runs = c.runs.filter(r => sizes.forall(_.contains(r.populationSize)))))
            .filter(_.runs.nonEmpty)
        if (selected.isEmpty) throw new IllegalArgumentException("No run matches the selection")

        val mode = GlobalState.APP_MODE
        println(s"App mode: ${mode.description}")
        if (!mode.skipDatabase) {
            if (!mode.usesLegacyDB)
                throw new IllegalStateException("The thesis campaigns use the legacy database: set APP_LEGACY_DB=true")
            if (pin) {
                val taken = selected.flatMap(_.runs.map(_.thesisRunId)).filter(DatabaseManager.legacyRunExists)
                if (taken.nonEmpty)
                    throw new IllegalStateException(
                        s"The database already holds run ids ${taken.mkString(", ")}. Use a fresh database to replicate " +
                        "the thesis, or --no-pin to run a new sample.")
            }
        } else println("No database: run ids = thesis ids; results are not persisted (CSV only).")

        val system = actorSystem("ThesisCampaigns")
        for (c <- selected) {
            println(s"\n===== ${c.label} (thesis Sec. ${c.thesisSection}): ${c.description} =====")
            val promise = Promise[List[CampaignResult]]()
            system.actorOf(Props(new CampaignOrchestrator(c.runs, pin, promise)), s"Campaign_${c.key}_${c.execution}")
            val results = Await.result(promise.future, Duration.Inf)
            val file = new File(outDir, c.csvFile)
            writeCsv(file, results)
            printTable(c, results)
            println(s"Saved ${file.getPath}")
        }
        shutdown(system)
        0
    }

    private def actorSystem(name: String): ActorSystem =
        ActorSystem(name, ConfigFactory.load().getConfig("app-dispatcher"))

    /**
     * Network actors report the end of a run before writing its final topology to the database, and stop only once
     * it is written; termination waits for every actor to stop, so no topology is cut short by the exit.
     */
    private def shutdown(system: ActorSystem): Unit = {
        println("Waiting for pending database writes to finish...")
        Await.ready(system.terminate(), Duration.Inf)
    }

    private def writeCsv(file: File, results: List[CampaignResult]): Unit = {
        val w = new PrintWriter(file, "UTF-8")
        try {
            w.println("PopulationSize,Scenario,RunID,TotalDurationMs,ConsensusCount,AvgRounds")
            results.foreach(r => w.println(s"${r.populationSize},${r.scenario},${r.runId},${r.totalDurationMs},${r.consensusCount},${r.avgRounds}"))
        } finally w.close()
    }

    private def printTable(c: Campaign, results: List[CampaignResult]): Unit = {
        println(f"| ${"N"}%-6s | ${"Scenario"}%-52s | ${"Run"}%-6s | ${"Time (ms)"}%-9s | ${"Consensus"}%-9s | ${"Avg rnd"}%-7s |")
        results.foreach { r =>
            println(f"| ${r.populationSize}%-6d | ${r.scenario}%-52s | ${r.runId}%-6d | ${r.totalDurationMs}%-9d | " +
                f"${s"${r.consensusCount}/${ThesisCampaigns.NetworksPerRun}"}%-9s | ${r.avgRounds}%-7.1f |")
        }
    }

    private def list(): Unit = {
        println(f"${"campaign"}%-24s ${"alias"}%-7s ${"runs"}%-9s ${"section"}%-8s description")
        ThesisCampaigns.all.foreach { c =>
            val ids = s"${c.runs.head.thesisRunId}-${c.runs.last.thesisRunId}"
            println(f"${c.label}%-24s ${c.alias.getOrElse("")}%-7s $ids%-9s ${c.thesisSection}%-8s ${c.description}")
        }
        println(f"${"small-world"}%-24s ${"phase3"}%-7s ${"196-205"}%-9s ${"6.7.3"}%-8s analysis only: src/scripts/thesis/small_world.py")
        println(f"${"case-studies"}%-24s ${""}%-7s ${"-"}%-9s ${"5.1-5.3"}%-8s Ch. 5 hand-built networks, 20 seeds (APP_SKIP_DATABASE=true)")
    }
}
