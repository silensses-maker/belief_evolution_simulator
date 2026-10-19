package io.web.json

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import io.db.DatabaseManager
import io.web.auth.AuthenticatedUser
import io.web.testing.JsonTestSupport.*
import io.web.{ResultsSnapshot, TopologySnapshot}
import spray.json.*

/**
 * Round trips of every REST body built by ApiJson (#123): user-provided strings with quotes,
 * backslashes, control characters and unicode parse back to the same values (with spray-json and
 * play-json), and plain values keep the exact bytes of the pre-#123 string interpolation (the
 * `legacy*` functions below are verbatim copies of the removed code), so the frontend parses the
 * same shapes, member order and number types.
 *
 * DatabaseManager's case classes are built with `new` so the DatabaseManager object (which opens
 * the connection pools) is never initialized by these tests.
 */
class ApiJsonSuite extends munit.FunSuite {

    private def limits(maxAgents: Int, maxIterations: Int, densityFactor: Double, maxNetworks: Int) =
        new DatabaseManager.UsageLimits(maxAgents, maxIterations, densityFactor, maxNetworks)

    private val roleLimits = List(
        limits(Int.MaxValue, Int.MaxValue, 1.0, Int.MaxValue), // Administrator
        limits(1000, 1000, 0.75, 20),                          // Researcher
        limits(100, 100, 0.5, 5),                              // BaseUser
        limits(10, 10, 0.5, 1)                                 // Guest
    )

    // ── Verbatim copies of the pre-#123 builders (Server.scala at 6e8767b) ──────────────────────

    private def legacyUsageLimitsJson(limits: DatabaseManager.UsageLimits): String = {
        def cap(value: Int): String = if (value == Int.MaxValue) "null" else value.toString
        s"""{"maxAgents":${cap(limits.maxAgents)},"maxIterations":${cap(limits.maxIterations)},"densityFactor":${limits.densityFactor},"maxNetworks":${cap(limits.maxNetworks)}}"""
    }

    private def legacySimCreatedJson(runId: Long, networkCount: Int, effectiveDensity: Option[Int] = None): String = {
        val densityField = effectiveDensity.map(d => s""""effectiveDensity":$d,""").getOrElse("")
        s"""{"runId":"$runId","status":"running","networkCount":$networkCount,$densityField"wsUrl":"/ws"}"""
    }

    private def legacyRunSummaryJson(r: DatabaseManager.RunSummary): String =
        s"""{
           |  "id": "${r.id}",
           |  "type": "${r.runType}",
           |  "name": ${r.name.map(n => s""""$n"""").getOrElse("null")},
           |  "networkCount": ${r.networkCount},
           |  "iterationLimit": ${r.iterationLimit},
           |  "stopThreshold": ${r.stopThreshold},
           |  "createdAt": "${r.createdAt}",
           |  "status": "${r.status}"
           |}""".stripMargin

    private def legacyTopologyPageJson(snap: TopologySnapshot, runId: Long, networkId: String,
        aOff: Int, aLim: Int, eOff: Int, eLim: Int): String = {
        val aEnd = math.min(aOff + aLim, snap.agentCount)
        val agents = (math.min(aOff, snap.agentCount) until aEnd).map { i =>
            val name = if (snap.names != null) s""""${snap.names(i)}"""" else "null"
            s"""{"index":$i,"name":$name,"initialBelief":${snap.initialBeliefs(i)},"toleranceRadius":${snap.tolRadius(i)},"toleranceOffset":${snap.tolOffset(i)},"silenceStrategy":${snap.silenceStrategies(i) & 0xFF},"silenceEffect":${snap.silenceEffects(i) & 0xFF}}"""
        }.mkString(",")
        val eStart = math.min(eOff, snap.edgeCount)
        val eEnd   = math.min(eOff + eLim, snap.edgeCount)
        val edges  = legacyEdgesPageJson(snap, eStart, eEnd)
        s"""{"runId":"$runId","networkId":"$networkId","agentCount":${snap.agentCount},"edgeCount":${snap.edgeCount},"agentOffset":$aOff,"agentLimit":$aLim,"edgeOffset":$eOff,"edgeLimit":$eLim,"agents":[$agents],"edges":[$edges]}"""
    }

    private def legacyEdgesPageJson(snap: TopologySnapshot, eStart: Int, eEnd: Int): String = {
        if (eStart >= eEnd || snap.edgeCount == 0) return ""
        val sb  = new StringBuilder
        var owner = 0
        while (owner < snap.agentCount - 1 && snap.indexOffset(owner) <= eStart) owner += 1
        var first = true
        for (j <- eStart until eEnd) {
            while (owner < snap.agentCount - 1 && snap.indexOffset(owner) <= j) owner += 1
            if (!first) sb.append(',')
            first = false
            sb.append(s"""{"source":${snap.neighborsRefs(j)},"target":$owner,"influence":${snap.neighborsWeights(j)},"bias":${snap.neighborBiases(j) & 0xFF}}""")
        }
        sb.toString
    }

    private def legacyResultsPageJson(snap: ResultsSnapshot, runId: Long, networkId: String, off: Int, lim: Int): String = {
        val start = math.min(off, snap.agentCount)
        val end   = math.min(off + lim, snap.agentCount)
        val agents = (start until end).map { i =>
            val name = if (snap.names != null) s""""${snap.names(i)}"""" else "null"
            s"""{"index":$i,"name":$name,"finalBelief":${snap.finalBeliefs(i)},"publicBelief":${snap.publicBeliefs(i)}}"""
        }.mkString(",")
        s"""{"runId":"$runId","networkId":"$networkId","finalRound":${snap.finalRound},"consensus":${snap.consensus},"agentCount":${snap.agentCount},"offset":$off,"limit":$lim,"agents":[$agents]}"""
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    private val networkId = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"

    /**
     * 4 agents; agent 0 is influenced by nobody (empty CSR row). Rows (END pointers 0,2,3,4):
     * agent 1 listens to 0 and 2, agent 2 to 3, agent 3 to 1.
     */
    private def topology(names: Array[String]): TopologySnapshot = TopologySnapshot(
        agentCount        = 4,
        edgeCount         = 4,
        names             = names,
        initialBeliefs    = Array(0.1f, 0.5f, 0.9f, 0.333333f),
        tolRadius         = Array(0.2f, 0.25f, 1.0f, 0.05f),
        tolOffset         = Array(0.0f, -0.1f, 0.15f, 0.75f),
        silenceStrategies = Array[Byte](0, 1, 2, 3),
        silenceEffects    = Array[Byte](0, 1, 2, 0),
        indexOffset       = Array(0, 2, 3, 4),
        neighborsRefs     = Array(0, 2, 3, 1),
        neighborsWeights  = Array(0.5f, 0.25f, 1.0f, 0.125f),
        neighborBiases    = Array[Byte](0, 1, 4, 2)
    )

    private def results(names: Array[String]): ResultsSnapshot = ResultsSnapshot(
        finalRound    = 17,
        consensus     = true,
        agentCount    = 4,
        names         = names,
        finalBeliefs  = Array(0.4f, 0.41f, 0.42f, 0.43f),
        publicBeliefs = Array(0.4f, 0.4f, 0.45f, -1.0f)
    )

    private def runSummary(name: Option[String], stopThreshold: Float = 0.001f) = new DatabaseManager.RunSummary(
        id = 7_123_456_789_012_345_678L, runType = "custom", name = name, networkCount = 1,
        iterationLimit = 1000, stopThreshold = stopThreshold, createdAt = "2026-10-19T12:34:56Z",
        userId = Some(3), status = "completed")

    // ── Number formats ──────────────────────────────────────────────────────────────────────────

    test("jsFloat keeps the digits of Float.toString (what the interpolation printed)") {
        val floats = List(0.1f, 0.5f, 1.0f, 0.333333f, -0.75f, 0.001f, 1e-5f, 1.234e-7f, 1e10f,
            Float.MaxValue, Float.MinPositiveValue, 123456.79f)
        floats.foreach { f =>
            val printed = ApiJson.jsFloat(f).compactPrint
            // Same numeric value as the old interpolation, read back as the frontend does (a double).
            assertEquals(printed.toDouble, java.lang.Float.toString(f).toDouble, s"value of $f")
            assertEquals(printed.toFloat, f, s"float round trip of $f")
        }
        // Typical beliefs / radii / thresholds: identical bytes.
        List(0.1f, 0.5f, 1.0f, 0.333333f, -0.75f, 0.001f, 0.125f).foreach { f =>
            assertEquals(ApiJson.jsFloat(f).compactPrint, java.lang.Float.toString(f))
        }
        // Same digits as spray-json's own Float writer for finite values (jsFloat adds NaN/Inf → null).
        floats.foreach { f =>
            assertEquals(ApiJson.jsFloat(f), DefaultJsonProtocol.FloatJsonFormat.write(f), s"$f")
        }
        // Not the Double widening, which would change the values the frontend shows.
        assertNotEquals(ApiJson.jsFloat(0.1f).compactPrint, JsNumber(0.1f.toDouble).compactPrint)
    }

    test("NaN and ±Infinity become null (the old interpolation printed NaN / Infinity: not JSON)") {
        List(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity).foreach { f =>
            assertEquals(ApiJson.jsFloat(f), JsNull)
        }
    }

    test("quotas: Int.MaxValue (unlimited) is null, other values stay integers") {
        assertEquals(ApiJson.jsQuota(Int.MaxValue), JsNull)
        assertEquals(ApiJson.jsQuota(10).compactPrint, "10")
        assertEquals(ApiJson.jsQuota(0).compactPrint, "0")
    }

    test("pageWindow clamps without Int overflow") {
        assertEquals(ApiJson.pageWindow(0, 500, 4), (0, 4))
        assertEquals(ApiJson.pageWindow(2, 1, 4), (2, 3))
        assertEquals(ApiJson.pageWindow(10, 5, 4), (4, 4))
        assertEquals(ApiJson.pageWindow(3, Int.MaxValue, 4), (3, 4)) // 3 + MaxValue overflowed before
        assertEquals(ApiJson.pageWindow(0, 0, 4), (0, 0))
    }

    // ── /api/users/* ────────────────────────────────────────────────────────────────────────────

    test("usageLimits: same bytes as before for every role") {
        roleLimits.foreach { l =>
            assertEquals(ApiJson.usageLimits(l).compactPrint, legacyUsageLimitsJson(l))
        }
    }

    test("userSync round-trips hostile names, emails and photo URLs") {
        nastyStrings.foreach { s =>
            val user = AuthenticatedUser(uid = s"uid-$s", email = s"$s@example.org", name = s,
                photo = Some(s"https://example.org/p?q=$s"), roles = Seq("Guest", s), dbUserId = 1)
            val parsed = parseAsClient(ApiJson.userSync(user, roleLimits(3)).compactPrint)
            assertEquals(str(parsed, "uid"), s"uid-$s")
            assertEquals(str(parsed, "email"), s"$s@example.org")
            assertEquals(str(parsed, "name"), s)
            assertEquals(str(parsed, "photo"), s"https://example.org/p?q=$s")
            assertEquals(array(parsed, "roles"), Vector(JsString("Guest"), JsString(s)))
            assertEquals(field(parsed, "usageLimits"), legacyUsageLimitsJson(roleLimits(3)).parseJson)
            assertEquals(field(parsed, "deactivated"), JsFalse)
            assertEquals(parsed.asJsObject.fields.keySet,
                Set("uid", "email", "name", "photo", "roles", "usageLimits", "deactivated"))
        }
    }

    test("userSync: no photo is null; same values as the pre-#123 body for plain input") {
        val user = AuthenticatedUser("Xn3jK2", "a@b.co", "Juan", None, Seq("Administrator"), 9)
        val legacy =
            s"""{"uid":"Xn3jK2","email":"a@b.co","name":"Juan","photo":null,"roles":["Administrator"],"usageLimits":${legacyUsageLimitsJson(roleLimits.head)},"deactivated":false}"""
        val printed = ApiJson.userSync(user, roleLimits.head).compactPrint
        assertEquals(printed, legacy) // also the member order
    }

    test("userInfo round-trips hostile values; plain input keeps the old bytes") {
        nastyStrings.foreach { s =>
            val user = new DatabaseManager.UserData(1, s"uid$s", s"$s@x.io", s, Some("p"), Seq("BaseUser"),
                deactivated = true, createdAt = "c", updatedAt = "u")
            val parsed = parseAsClient(ApiJson.userInfo(user, roleLimits(2)).compactPrint)
            assertEquals(str(parsed, "uid"), s"uid$s")
            assertEquals(str(parsed, "email"), s"$s@x.io")
            assertEquals(str(parsed, "name"), s)
            assertEquals(field(parsed, "deactivated"), JsTrue)
            assert(!parsed.asJsObject.fields.contains("photo"), "info has no photo")
        }
        val plain = new DatabaseManager.UserData(1, "u1", "e@x.io", "N", None, Seq("Guest", "BaseUser"), false, "c", "u")
        assertEquals(ApiJson.userInfo(plain, roleLimits(2)).compactPrint,
            s"""{"uid":"u1","email":"e@x.io","name":"N","roles":["Guest","BaseUser"],"usageLimits":${legacyUsageLimitsJson(roleLimits(2))},"deactivated":false}""")
        val nullColumns = new DatabaseManager.UserData(1, "u1", null, null, None, Seq.empty, false, "c", "u")
        assertEquals(field(parseAsClient(ApiJson.userInfo(nullColumns, roleLimits(3)).compactPrint), "email"), JsNull)
    }

    test("roleAction: uid and role come from the URL path and are escaped") {
        nastyStrings.foreach { s =>
            val parsed = parseAsClient(ApiJson.roleAction(s, s"r$s", "added").compactPrint)
            assertEquals(str(parsed, "uid"), s)
            assertEquals(str(parsed, "role"), s"r$s")
            assertEquals(str(parsed, "action"), "added")
        }
        assertEquals(ApiJson.roleAction("u1", "Researcher", "added").compactPrint,
            """{"uid":"u1","role":"Researcher","action":"added"}""")
        assertEquals(ApiJson.deactivated.compactPrint, """{"deactivated":true}""")
    }

    // ── /simulations/* ──────────────────────────────────────────────────────────────────────────

    test("simCreated: same bytes as before, with and without effectiveDensity") {
        val runId = 7_123_456_789_012_345_678L
        assertEquals(ApiJson.simCreated(runId, 3, Some(2)).compactPrint, legacySimCreatedJson(runId, 3, Some(2)))
        assertEquals(ApiJson.simCreated(runId, 1).compactPrint, legacySimCreatedJson(runId, 1))
        // runId stays a string (it exceeds Number.MAX_SAFE_INTEGER)
        assertEquals(field(parseAsClient(ApiJson.simCreated(runId, 1).compactPrint), "runId"), JsString(runId.toString))
    }

    test("runSummary: a custom run name with quotes no longer breaks /simulations/mine") {
        nastyStrings.foreach { s =>
            val parsed = parseAsClient(ApiJson.runSummary(runSummary(Some(s))).compactPrint)
            assertEquals(str(parsed, "name"), s)
            assertEquals(str(parsed, "id"), "7123456789012345678")
            assertEquals(int(parsed, "networkCount"), 1)
            assertEquals(int(parsed, "iterationLimit"), 1000)
            assertEquals(field(parsed, "stopThreshold"), JsNumber(BigDecimal("0.001")))
            assertEquals(str(parsed, "status"), "completed")
        }
        // The legacy body was invalid JSON for such a name...
        assert(scala.util.Try(legacyRunSummaryJson(runSummary(Some("a \"b\" c"))).parseJson).isFailure)
        // ... and for plain names the values are the same (it was pretty-printed, now compact).
        List(Some("my run"), None).foreach { name =>
            assertEquals(ApiJson.runSummary(runSummary(name)).compactPrint.parseJson,
                legacyRunSummaryJson(runSummary(name)).parseJson)
        }
        assertEquals(field(ApiJson.runSummary(runSummary(None)), "name"), JsNull)
    }

    test("runList / runCancelled / networkList: round trips and old bytes") {
        val runs   = nastyStrings.map(s => runSummary(Some(s)))
        val parsed = parseAsClient(ApiJson.runList(runs, 20, 40).compactPrint)
        assertEquals(array(parsed, "runs").map(r => str(r, "name")), nastyStrings.toVector)
        assertEquals(int(parsed, "limit"), 20)
        assertEquals(int(parsed, "offset"), 40)
        assertEquals(ApiJson.runList(Nil, 20, 0).compactPrint, """{"runs":[],"limit":20,"offset":0}""")

        assertEquals(ApiJson.runCancelled(42L).compactPrint, """{"runId":"42","cancelled":true}""")

        val ids = List(networkId, "4b0e6a1c-2d3f-4a5b-8c9d-0e1f2a3b4c5d")
        assertEquals(ApiJson.networkList(42L, ids).compactPrint,
            s"""{"runId":"42","networks":[${ids.map(id => s""""$id"""").mkString(",")}]}""")
        assertEquals(array(parseAsClient(ApiJson.networkList(42L, nastyStrings).compactPrint), "networks"),
            nastyStrings.map(JsString(_)).toVector)
    }

    test("topologyPage: same bytes as before for plain names, generated (null) names and every window") {
        val named = topology(Array("a", "b", "c", "d"))
        val windows = List((0, 500, 0, 1000), (1, 2, 1, 2), (0, 0, 3, 10), (4, 10, 4, 10), (3, 1, 0, 1))
        for (snap <- List(named, topology(null)); (aOff, aLim, eOff, eLim) <- windows) {
            assertEquals(ApiJson.topologyPage(snap, 99L, networkId, aOff, aLim, eOff, eLim),
                legacyTopologyPageJson(snap, 99L, networkId, aOff, aLim, eOff, eLim), s"window $aOff,$aLim,$eOff,$eLim")
        }
    }

    test("topologyPage: edges are source -> target from the CSR rows, agent 0 has an empty row") {
        val parsed = parseAsClient(ApiJson.topologyPage(topology(null), 99L, networkId, 0, 500, 0, 1000))
        val edges = array(parsed, "edges").map(e => (int(e, "source"), int(e, "target"), int(e, "bias")))
        assertEquals(edges, Vector((0, 1, 0), (2, 1, 1), (3, 2, 4), (1, 3, 2)))
        assertEquals(array(parsed, "agents").map(a => field(a, "name")).distinct, Vector(JsNull))
        assertEquals(field(parsed, "runId"), JsString("99"))
        assertEquals(int(parsed, "agentCount"), 4)
    }

    test("topologyPage round-trips hostile agent names (they come from the custom-run request)") {
        nastyStrings.grouped(4).foreach { group =>
            val names  = group.padTo(4, "pad").toArray
            val parsed = parseAsClient(ApiJson.topologyPage(topology(names), 1L, networkId, 0, 500, 0, 1000))
            assertEquals(array(parsed, "agents").map(a => str(a, "name")), names.toVector)
        }
        val withNullName = topology(Array("a", null, "c", "d"))
        val parsed = parseAsClient(ApiJson.topologyPage(withNullName, 1L, networkId, 0, 500, 0, 0))
        assertEquals(field(array(parsed, "agents")(1), "name"), JsNull)
    }

    test("topologyPage: non-finite floats are null, the page stays valid JSON") {
        val snap = topology(Array("a", "b", "c", "d")).copy(
            initialBeliefs = Array(Float.NaN, 0.5f, Float.PositiveInfinity, 0.1f),
            neighborsWeights = Array(Float.NegativeInfinity, 0.25f, 1.0f, 0.125f))
        val parsed = parseAsClient(ApiJson.topologyPage(snap, 1L, networkId, 0, 500, 0, 1000))
        assertEquals(array(parsed, "agents").map(a => field(a, "initialBelief")),
            Vector(JsNull, JsNumber(BigDecimal("0.5")), JsNull, JsNumber(BigDecimal("0.1"))))
        assertEquals(field(array(parsed, "edges").head, "influence"), JsNull)
    }

    test("resultsPage: old bytes for plain names, round trip for hostile ones") {
        val plain = results(Array("a", "b", "c", "d"))
        for ((off, lim) <- List((0, 500), (1, 2), (4, 1), (0, 0))) {
            assertEquals(ApiJson.resultsPage(plain, 5L, networkId, off, lim), legacyResultsPageJson(plain, 5L, networkId, off, lim))
            assertEquals(ApiJson.resultsPage(results(null), 5L, networkId, off, lim),
                legacyResultsPageJson(results(null), 5L, networkId, off, lim))
        }
        nastyStrings.grouped(4).foreach { group =>
            val names  = group.padTo(4, "pad").toArray
            val parsed = parseAsClient(ApiJson.resultsPage(results(names), 5L, networkId, 0, 500))
            assertEquals(array(parsed, "agents").map(a => str(a, "name")), names.toVector)
            assertEquals(int(parsed, "finalRound"), 17)
            assertEquals(field(parsed, "consensus"), JsTrue)
        }
    }

    test("large topology page is streamed in linear time (20k agents, 200k edges)") {
        val n = 20_000
        val m = 200_000
        val rnd = new scala.util.Random(7)
        val snap = TopologySnapshot(
            agentCount = n, edgeCount = m, names = Array.tabulate(n)(i => s"agent \"$i\""),
            initialBeliefs = Array.fill(n)(rnd.nextFloat()), tolRadius = Array.fill(n)(rnd.nextFloat()),
            tolOffset = Array.fill(n)(rnd.nextFloat()), silenceStrategies = Array.fill(n)(0.toByte),
            silenceEffects = Array.fill(n)(1.toByte), indexOffset = Array.tabulate(n)(i => ((i + 1).toLong * m / n).toInt),
            neighborsRefs = Array.fill(m)(rnd.nextInt(n)), neighborsWeights = Array.fill(m)(rnd.nextFloat()),
            neighborBiases = Array.fill(m)(2.toByte))
        val t0   = System.nanoTime()
        val json = ApiJson.topologyPage(snap, 1L, networkId, 0, n, 0, m)
        val secs = (System.nanoTime() - t0) / 1e9
        val parsed = JsonParser(json)
        assertEquals(array(parsed, "agents").length, n)
        assertEquals(array(parsed, "edges").length, m)
        assertEquals(str(array(parsed, "agents")(12345), "name"), "agent \"12345\"")
        assert(secs < 20.0, f"took $secs%.1f s for ${json.length / 1048576.0}%.1f MB")
        println(f"[perf] topology page: $n agents + $m edges = ${json.length / 1048576.0}%.1f MB in $secs%.2f s")
    }
}
