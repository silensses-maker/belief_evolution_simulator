package io.web.json

import io.db.DatabaseManager.{RunSummary, UsageLimits, UserData}
import io.web.{ResultsSnapshot, TopologySnapshot}
import io.web.auth.AuthenticatedUser
import spray.json.*

import scala.collection.immutable.ListMap

/**
 * JSON bodies of the REST API (#123). Every value goes through spray-json (`JsString` escapes any
 * user-provided string: run, agent and user names, uids, network ids), and the shapes are the ones
 * the frontend parses (`front_BES/src/shared/api/backend/types/backend.types.ts`):
 *  - members keep the order they had when these bodies were hand-built strings ([[obj]]: a
 *    `ListMap`; spray-json's own `JsObject(members*)` sorts them by name);
 *  - ids (`runId`, `id`, `networkId`, `uid`) are JSON strings, counts and codes are integers;
 *  - Float fields are printed from `java.lang.Float.toString`, i.e. the same decimal digits the
 *    string interpolation produced (`0.1f` → `0.1`, not the widened `0.10000000149011612`);
 *    NaN / ±Infinity become `null` (they used to be printed as `NaN` / `Infinity`, which is not
 *    JSON at all);
 *  - an unlimited quota (`Int.MaxValue`) is `null`, as before.
 * Large pages (topology / results) are streamed element by element: see [[objectWithArrays]].
 */
object ApiJson {

    // ── Building blocks ─────────────────────────────────────────────────────

    /** Object with its members in this order (spray-json's `JsObject(members*)` would sort them). */
    def obj(members: (String, JsValue)*): JsObject = JsObject(ListMap.from(members))

    /** A Float with the digits of `Float.toString`; NaN and ±Infinity are not JSON numbers → null. */
    def jsFloat(value: Float): JsValue =
        if (value.isNaN || value.isInfinite) JsNull
        else JsNumber(BigDecimal(java.lang.Float.toString(value)))

    /** A per-role quota: `Int.MaxValue` means unlimited and is serialized as null. */
    def jsQuota(value: Int): JsValue = if (value == Int.MaxValue) JsNull else JsNumber(value)

    /** A string that may be null (database columns, snapshot names). */
    def jsNullable(value: String): JsValue = if (value == null) JsNull else JsString(value)

    /** Snowflake run ids travel as decimal strings (they exceed JavaScript's safe integers). */
    def jsId(id: Long): JsString = JsString(id.toString)

    private def jsStrings(values: Iterable[String]): JsArray =
        JsArray(values.iterator.map(jsNullable).toVector)

    /**
     * Compact JSON object made of the scalar `members` followed by the `arrays`, in order, printed
     * into ONE buffer element by element: each element (and each scalar) is still a spray-json
     * value printed by spray-json's `CompactPrinter` — escaping and number format are spray-json's —
     * but a page of thousands of agents / edges never exists as a single JsValue tree, so the cost
     * is linear in the output and the per-element objects are short-lived garbage.
     */
    def objectWithArrays(members: Seq[(String, JsValue)], arrays: Seq[(String, Iterator[JsValue])],
                         sizeHint: Long = 256): String = {
        val sb = new java.lang.StringBuilder(math.max(16L, math.min(sizeHint, 64L * 1024 * 1024)).toInt)
        var first = true
        def key(name: String): Unit = {
            sb.append(if (first) '{' else ',')
            first = false
            CompactPrinter.print(JsString(name), sb)
            sb.append(':')
        }
        members.foreach { case (name, value) =>
            key(name)
            CompactPrinter.print(value, sb)
        }
        arrays.foreach { case (name, items) =>
            key(name)
            sb.append('[')
            var firstItem = true
            items.foreach { item =>
                if (!firstItem) sb.append(',')
                firstItem = false
                CompactPrinter.print(item, sb)
            }
            sb.append(']')
        }
        if (first) sb.append('{')
        sb.append('}').toString
    }

    /**
     * `[start, end)` of a page of `count` elements: `offset` and `offset + limit` clamped to
     * `[0, count]`, in Long arithmetic (a large `limit` used to overflow `offset + limit` into an
     * empty page). Callers reject negative values with 400 before.
     */
    def pageWindow(offset: Int, limit: Int, count: Int): (Int, Int) = {
        val start = math.min(math.max(offset, 0), count)
        val end   = math.max(start.toLong, math.min(offset.toLong + math.max(limit, 0), count.toLong)).toInt
        (start, end)
    }

    // ── /api/users/* ────────────────────────────────────────────────────────

    /** Shared by /api/users/sync and /api/users/info so both expose the same quotas. */
    def usageLimits(limits: UsageLimits): JsObject = obj(
        "maxAgents"     -> jsQuota(limits.maxAgents),
        "maxIterations" -> jsQuota(limits.maxIterations),
        "densityFactor" -> JsNumber(limits.densityFactor),
        "maxNetworks"   -> jsQuota(limits.maxNetworks)
    )

    /** `POST /api/users/sync`: identity from the verified token, authorization from the database. */
    def userSync(user: AuthenticatedUser, limits: UsageLimits): JsObject = obj(
        "uid"         -> JsString(user.uid),
        "email"       -> jsNullable(user.email),
        "name"        -> jsNullable(user.name),
        "photo"       -> user.photo.fold[JsValue](JsNull)(JsString(_)),
        "roles"       -> jsStrings(user.roles),
        "usageLimits" -> usageLimits(limits),
        "deactivated" -> JsFalse
    )

    /** `GET /api/users/info/{uid}`: the sync shape without `photo`. */
    def userInfo(user: UserData, limits: UsageLimits): JsObject = obj(
        "uid"         -> jsNullable(user.firebaseUid),
        "email"       -> jsNullable(user.email),
        "name"        -> jsNullable(user.name),
        "roles"       -> jsStrings(user.roles),
        "usageLimits" -> usageLimits(limits),
        "deactivated" -> JsBoolean(user.deactivated)
    )

    /** `POST /api/users/me/deactivate`. */
    val deactivated: JsObject = obj("deactivated" -> JsTrue)

    /** `PUT /api/users/role/{uid}/{role}` (both from the path: any string). */
    def roleAction(uid: String, role: String, action: String): JsObject = obj(
        "uid"    -> JsString(uid),
        "role"   -> JsString(role),
        "action" -> JsString(action)
    )

    // ── /simulations/* ──────────────────────────────────────────────────────

    /** `POST /simulations/generated` and `/custom`. effectiveDensity only for generated runs (#83, #122). */
    def simCreated(runId: Long, networkCount: Int, effectiveDensity: Option[Int] = None): JsObject = {
        val members = Seq[(String, JsValue)](
            "runId"        -> jsId(runId),
            "status"       -> JsString("running"),
            "networkCount" -> JsNumber(networkCount)
        ) ++ effectiveDensity.map(d => "effectiveDensity" -> JsNumber(d)) :+ ("wsUrl" -> JsString("/ws"))
        obj(members*)
    }

    /** `GET /simulations/{runId}` and the items of the run lists. */
    def runSummary(run: RunSummary): JsObject = obj(
        "id"             -> jsId(run.id),
        "type"           -> jsNullable(run.runType),
        "name"           -> run.name.fold[JsValue](JsNull)(jsNullable),
        "networkCount"   -> JsNumber(run.networkCount),
        "iterationLimit" -> JsNumber(run.iterationLimit),
        "stopThreshold"  -> jsFloat(run.stopThreshold),
        "createdAt"      -> jsNullable(run.createdAt),
        "status"         -> jsNullable(run.status)
    )

    /** `GET /simulations/mine` and `GET /simulations` (admin). */
    def runList(runs: Seq[RunSummary], limit: Int, offset: Int): JsObject = obj(
        "runs"   -> JsArray(runs.iterator.map(runSummary).toVector),
        "limit"  -> JsNumber(limit),
        "offset" -> JsNumber(offset)
    )

    /** `DELETE /simulations/{runId}`. */
    def runCancelled(runId: Long): JsObject = obj("runId" -> jsId(runId), "cancelled" -> JsTrue)

    /** `GET /simulations/{runId}/networks`. */
    def networkList(runId: Long, networkIds: Seq[String]): JsObject =
        obj("runId" -> jsId(runId), "networks" -> jsStrings(networkIds))

    private def nameAt(names: Array[String], i: Int): JsValue =
        if (names == null) JsNull else jsNullable(names(i))

    /** `GET /simulations/{runId}/networks/{networkId}/topology`: one page of agents and one of edges. */
    def topologyPage(snap: TopologySnapshot, runId: Long, networkId: String,
                     agentOffset: Int, agentLimit: Int, edgeOffset: Int, edgeLimit: Int): String = {
        val (aStart, aEnd) = pageWindow(agentOffset, agentLimit, snap.agentCount)
        val (eStart, eEnd) = pageWindow(edgeOffset, edgeLimit, snap.edgeCount)
        val agents = (aStart until aEnd).iterator.map { i =>
            obj(
                "index"           -> JsNumber(i),
                "name"            -> nameAt(snap.names, i),
                "initialBelief"   -> jsFloat(snap.initialBeliefs(i)),
                "toleranceRadius" -> jsFloat(snap.tolRadius(i)),
                "toleranceOffset" -> jsFloat(snap.tolOffset(i)),
                "silenceStrategy" -> JsNumber(snap.silenceStrategies(i) & 0xFF),
                "silenceEffect"   -> JsNumber(snap.silenceEffects(i) & 0xFF)
            )
        }
        objectWithArrays(
            Seq(
                "runId"       -> jsId(runId),
                "networkId"   -> JsString(networkId),
                "agentCount"  -> JsNumber(snap.agentCount),
                "edgeCount"   -> JsNumber(snap.edgeCount),
                "agentOffset" -> JsNumber(agentOffset),
                "agentLimit"  -> JsNumber(agentLimit),
                "edgeOffset"  -> JsNumber(edgeOffset),
                "edgeLimit"   -> JsNumber(edgeLimit)
            ),
            Seq("agents" -> agents, "edges" -> topologyEdges(snap, eStart, eEnd)),
            sizeHint = 512L + 192L * (aEnd - aStart) + 96L * (eEnd - eStart)
        )
    }

    /**
     * Edges `[eStart, eEnd)` of the snapshot's CSR, in CSR order. Row `owner` lists the agents that
     * `owner` listens to (`neighborsRefs(j)`, with the weight `owner` gives it), for custom AND
     * generated networks, so each entry is reported as `source` = `neighborsRefs(j)` (the influencer)
     * and `target` = `owner` (the agent whose belief is updated): "source influences target", the
     * same meaning as the custom-run request edges.
     */
    private def topologyEdges(snap: TopologySnapshot, eStart: Int, eEnd: Int): Iterator[JsValue] =
        if (eStart >= eEnd || snap.edgeCount == 0) Iterator.empty
        else new Iterator[JsValue] {
            private var j     = eStart
            private var owner = 0
            while (owner < snap.agentCount - 1 && snap.indexOffset(owner) <= eStart) owner += 1

            def hasNext: Boolean = j < eEnd

            def next(): JsValue = {
                if (!hasNext) throw new NoSuchElementException("no more edges in this page")
                while (owner < snap.agentCount - 1 && snap.indexOffset(owner) <= j) owner += 1
                val edge = obj(
                    "source"    -> JsNumber(snap.neighborsRefs(j)),
                    "target"    -> JsNumber(owner),
                    "influence" -> jsFloat(snap.neighborsWeights(j)),
                    "bias"      -> JsNumber(snap.neighborBiases(j) & 0xFF)
                )
                j += 1
                edge
            }
        }

    /** `GET /simulations/{runId}/networks/{networkId}/results`: one page of final beliefs. */
    def resultsPage(snap: ResultsSnapshot, runId: Long, networkId: String, offset: Int, limit: Int): String = {
        val (start, end) = pageWindow(offset, limit, snap.agentCount)
        val agents = (start until end).iterator.map { i =>
            obj(
                "index"        -> JsNumber(i),
                "name"         -> nameAt(snap.names, i),
                "finalBelief"  -> jsFloat(snap.finalBeliefs(i)),
                "publicBelief" -> jsFloat(snap.publicBeliefs(i))
            )
        }
        objectWithArrays(
            Seq(
                "runId"      -> jsId(runId),
                "networkId"  -> JsString(networkId),
                "finalRound" -> JsNumber(snap.finalRound),
                "consensus"  -> JsBoolean(snap.consensus),
                "agentCount" -> JsNumber(snap.agentCount),
                "offset"     -> JsNumber(offset),
                "limit"      -> JsNumber(limit)
            ),
            Seq("agents" -> agents),
            sizeHint = 256L + 112L * (end - start)
        )
    }
}
