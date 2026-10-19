package io.web

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, OPTIONS, POST, PUT}
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, HttpResponse, MediaType, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import io.web.ws.{ConnectionClosed, Dispatch, WsConnectionActor, WsRegistry}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import core.model.agent.behavior.silence.SilenceEffects.SilenceEffect
import core.model.agent.behavior.silence.{SilenceEffects, SilenceStrategies}
import core.model.agent.behavior.silence.SilenceStrategies.SilenceStrategy
import core.simulation.actors.{AddNetworks, CancelRun, RunCustomNetwork}
import core.simulation.config.*
import core.simulation.config.SaveModes.SaveMode
import utils.logging.Logger
import io.db.DatabaseManager
import io.web.auth.ErrorResponses.*
import io.web.auth.FirebaseAuthDirective.{authenticate, requireAdmin}
import utils.datastructures.SnowflakeID
import utils.rng.distributions.Uniform
import spray.json.*
import DefaultJsonProtocol.*

import java.nio.{ByteBuffer, ByteOrder}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.control.NonFatal

// ── JSON shapes for /simulations/* requests ──────────────────────────────────

case class AgentTypeReq(silenceStrategy: Int, silenceEffect: Int, count: Int)
case class BiasTypeReq(biasType: Int, count: Int)
case class GeneratedRunReq(
    numberOfNetworks: Int,
    density: Int,
    iterationLimit: Int,
    stopThreshold: Float,
    seed: Option[Long],
    saveMode: Int,
    agentTypes: Seq[AgentTypeReq],
    biasTypes: Seq[BiasTypeReq],
    persistFrames: Option[Boolean],
    frameRetention: Option[String]
)

case class AgentReq(
    name: String,
    belief: Float,
    toleranceRadius: Float,
    toleranceOffset: Float,
    silenceStrategy: Int,
    silenceEffect: Int
)
case class EdgeReq(source: String, target: String, influence: Float, bias: Int)
case class CustomRunReq(
    name: String,
    iterationLimit: Int,
    stopThreshold: Float,
    saveMode: Int,
    agents: Seq[AgentReq],
    edges: Seq[EdgeReq],
    persistFrames: Option[Boolean],
    frameRetention: Option[String]
)

object SimulationJsonProtocol {
    implicit val agentTypeReqFmt: RootJsonFormat[AgentTypeReq]     = jsonFormat3(AgentTypeReq.apply)
    implicit val biasTypeReqFmt: RootJsonFormat[BiasTypeReq]       = jsonFormat2(BiasTypeReq.apply)
    implicit val generatedRunReqFmt: RootJsonFormat[GeneratedRunReq] = jsonFormat10(GeneratedRunReq.apply)
    implicit val agentReqFmt: RootJsonFormat[AgentReq]             = jsonFormat6(AgentReq.apply)
    implicit val edgeReqFmt: RootJsonFormat[EdgeReq]               = jsonFormat4(EdgeReq.apply)
    implicit val customRunReqFmt: RootJsonFormat[CustomRunReq]     = jsonFormat8(CustomRunReq.apply)
}

// Data containers:
/**
 * A custom network as the simulator consumes it (Network.BuildCustomNetwork copies these arrays).
 * The last four fields are its CSR adjacency, in the simulator's layout, NOT the user's edge list:
 * the row of agent `i` is `[indexOffset(i - 1), indexOffset(i))` (from 0 for `i = 0`) and lists the
 * agents `i` LISTENS TO — `i` updates its belief from each of them with the row's weight and bias.
 * A user edge `source -> target` ("source influences target") therefore lives in the row of its
 * TARGET, and its SOURCE is the neighbor reference stored in `target` (the field keeps its
 * historical name because core reads it as `neighborsRefs`). See Server.startCustomRun.
 *
 * @param indexOffset CSR row END pointers, one per agent (Server.buildEndOffsets)
 * @param target      CSR neighbor references: for each row, the agents that influence its owner
 *                    (the user edges' SOURCES), grouped by listening agent
 * @param influences  weight the row owner gives to each neighbor's opinion (same order)
 * @param bias        cognitive bias the row owner applies to each neighbor's opinion (same order)
 */
case class CustomRunInfo(
    runID: Long,
    stopThreshold: Float,
    iterationLimit: Int,
    saveMode: SaveMode,
    networkName: String,
    agentBeliefs: Array[Float],
    agentToleranceRadii: Array[Float],
    agentToleranceOffsets: Array[Float],
    agentSilenceStrategy: Array[SilenceStrategy],
    agentSilenceEffect: Array[SilenceEffect],
    agentNames: Array[String],
    indexOffset: Array[Int],
    target: Array[Int],
    influences: Array[Float],
    bias: Array[Bias],
    persistFrames: Boolean = false
)

/**
 * Contains the data to save to the database for custom agents table
 * @param belief Initial beliefs of the agents
 * @param radius Tolerance radius of the agents
 * @param offset Tolerance offset of the agents
 * @param majorityThreshold Majority thresholds of the agents
 * @param confidence Initial confidences of the agents
 * @param silenceStrategy Silence strategies of the agents
 * @param silenceEffect Silence effects of the agents 
 * @param name Names of the agents
 */
case class CustomAgentsData(
    belief: Array[Float],
    radius: Array[Float],
    offset: Array[Float],
    silenceStrategy: Array[SilenceStrategy],
    silenceEffect: Array[SilenceEffect],
    name: Array[String],
    majorityThreshold: Option[mutable.Map[Int, Float]],
    confidence: Option[mutable.Map[Int, Float]],
)

/**
 * Contains the data to save to the database for the custom neighbors table
 * @param source The agents that influence
 * @param target The agents that are influenced
 * @param influence The influences
 * @param bias The cognitive biases the agents react with
 */
case class CustomNeighborsData(
    source: Array[Int],
    target: Array[Int],
    influence: Array[Float],
    bias: Array[Bias],
)

final case class Payload(data: Array[Byte])

object BinaryProtocol {
    implicit def payloadUnmarshaller: FromEntityUnmarshaller[Payload] =
        Unmarshaller.byteStringUnmarshaller
          .mapWithInput { (_, bytes) =>
              Payload(bytes.toArray)
          }
}

object Server {
    
    import BinaryProtocol.*

    // ── Retention of ephemeral run data (#124) ──────────────────────────────────
    // The cron below calls cleanupEphemeralFrames(EphemeralFramesGraceMinutes) every
    // EphemeralFramesCleanupPeriodMinutes (first tick 5 min after startup), so the frames of an
    // 'ephemeral' run survive between grace and grace + period after its completed_at. The
    // in-memory topology / results / network ids of a terminal ephemeral run (SimulationCache) are
    // kept for grace + period + a 5-minute margin for cron drift (95 min), so /topology, /results
    // and /networks never disappear before the frames do. A backend restart still loses the
    // in-memory part (frames remain until the cleanup).
    final val EphemeralFramesGraceMinutes: Int = 60
    final val EphemeralFramesCleanupPeriodMinutes: Int = 30
    final val EphemeralSnapshotRetentionMinutes: Int =
        EphemeralFramesGraceMinutes + EphemeralFramesCleanupPeriodMinutes + 5
    
    private var initialized = false
    private var system: Option[ActorSystem] = None
    private var monitor: Option[ActorRef] = None
    
    def initialize(actorSystem: ActorSystem, monitor: ActorRef): Unit = {
        if (initialized) return
        
        system = Some(actorSystem)
        this.monitor = Some(monitor)
        
        implicit val systenImplicit: ActorSystem = actorSystem
        implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
        implicit val materializer: Materializer = Materializer(actorSystem)
        
        val serverHost = sys.env.getOrElse("SERVER_HOST", "0.0.0.0")
        val serverPort = sys.env.getOrElse("SERVER_PORT", "8080").toInt

        val corsResponseHeaders = List(
            `Access-Control-Allow-Origin`.*,
            `Access-Control-Allow-Methods`(POST, GET, PUT, DELETE, OPTIONS),
            `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin"),
            `Access-Control-Max-Age`(86400),
            `Access-Control-Allow-Credentials`(false)
        )
        
        def addCorsHeaders: Directive0 = {
            respondWithHeaders(corsResponseHeaders)
        }
        
        val corsHandler: Route = addCorsHeaders {
            options {
                complete(HttpResponse(StatusCodes.OK))
            }
        }
        
        val webSocketRoute: Route = addCorsHeaders {
            path("ws") {
                get {
                    // Backpressured outbound (#119): the source holds ONE element and acks it once
                    // emitted; WsConnectionActor queues the rest (control events never dropped,
                    // binary frames bounded by APP_WS_CONNECTION_BUFFER_MB) and sends the next on
                    // each ack. FailOutbound closes the socket (1011) when the client stops reading.
                    val (outRef, outSource) = Source.actorRefWithBackpressure[Message](
                        ackMessage        = WsConnectionActor.OutboundAck,
                        completionMatcher = PartialFunction.empty,
                        failureMatcher    = {
                            case WsConnectionActor.FailOutbound(reason) =>
                                new IllegalStateException(s"WS outbound closed: $reason")
                        }
                    ).preMaterialize()
                    val connActor = system.get.actorOf(
                        akka.actor.Props(new WsConnectionActor(outRef)))
                    // Inbound (#119): every client message reaches the actor in order as a strict
                    // text or an UnreadableClientMessage — streamed texts are read to the end (up to
                    // MaxClientMessageChars) and binaries drained, so nothing stalls the socket.
                    val inSink = Flow[Message]
                        .mapAsync(1)(msg => WsConnectionActor.readClientMessage(msg))
                        .to(Sink.actorRef[Any](connActor, ConnectionClosed, _ => ConnectionClosed))
                    handleWebSocketMessages(Flow.fromSinkAndSource(inSink, outSource))
                }
            }
        }
        
        // Legacy anonymous binary endpoints (#85), REMOVED in #121. By default `legacyRoute` is
        // `legacyGoneRoutes`: every method on /run, /custom and /neighbors (and on their /api
        // mounts) answers 410 Gone, the body is discarded unread and nothing is started.
        // `legacyRunRoutes` below is the pre-#121 behavior, mounted ONLY with
        // APP_ENABLE_LEGACY_ROUTES=true (see legacyRoutesEnabled): dev/test only, never production.
        val legacyRunRoutes: Route = addCorsHeaders {
            pathPrefix("run") {
                post {
                    withDeprecation("/simulations/generated") {
                        optionalHeaderValueByName("User-Agent") { ua =>
                            optionalHeaderValueByName("X-Forwarded-For") { xff =>
                                logLegacy("/run", ua, xff)
                                entity(as[Payload]) { payload =>
                                    complete(parseGeneratedRun(payload.data))
                                }
                            }
                        }
                    }
                }
            } ~ pathPrefix("custom") {
                post {
                    withDeprecation("/simulations/custom") {
                        optionalHeaderValueByName("User-Agent") { ua =>
                            optionalHeaderValueByName("X-Forwarded-For") { xff =>
                                logLegacy("/custom", ua, xff)
                                entity(as[Payload]) { payload =>
                                    complete(legacyCustomRun(payload.data))
                                }
                            }
                        }
                    }
                }
            } ~ pathPrefix("neighbors") {
                get {
                    withDeprecation("/simulations/custom") {
                        optionalHeaderValueByName("User-Agent") { ua =>
                            optionalHeaderValueByName("X-Forwarded-For") { xff =>
                                logLegacy("/neighbors", ua, xff)
                                entity(as[Payload]) { payload =>
                                    complete(legacyCustomRun(payload.data))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 410 Gone of a removed legacy endpoint (#121), whatever the method (the CORS preflight
        // OPTIONS is still answered by corsHandler). Exact path only: /run/ and /run/x get the same
        // answer as any unknown path. The Link targets are RELATIVE references, so the client
        // resolves them against the URL it requested: /run → /simulations/generated, /api/run →
        // /api/simulations/generated, also behind a reverse proxy that strips /api. The call is
        // still recorded in legacy_endpoint_usage so remaining callers can be detected.
        def legacyGone(segment: String, successor: String): Route =
            path(segment) {
                extractRequest { request =>
                    optionalHeaderValueByName("User-Agent") { ua =>
                        optionalHeaderValueByName("X-Forwarded-For") { xff =>
                            // Never parsed. Discarded so the connection is not left back-pressured
                            // (Akka closes it after this early answer if a large upload is still
                            // arriving; small bodies keep the connection alive).
                            request.discardEntityBytes()
                            logLegacy(s"/$segment", ua, xff)
                            complete(HttpResponse(
                                StatusCodes.Gone,
                                headers = List(RawHeader("Link",
                                    s"""<$successor>; rel="successor-version", <docs>; rel="service-doc"""")),
                                entity = errorJson("gone",
                                    s"This legacy endpoint was removed; use POST /$successor (authenticated, see /docs)")
                            ))
                        }
                    }
                }
            }

        val legacyGoneRoutes: Route = addCorsHeaders {
            legacyGone("run", "simulations/generated") ~
            legacyGone("custom", "simulations/custom") ~
            legacyGone("neighbors", "simulations/custom")
        }

        val legacyRoute: Route = if (legacyRoutesEnabled) legacyRunRoutes else legacyGoneRoutes

        // Shared by /api/users/sync and /api/users/info so both expose the same quotas.
        // Int.MaxValue (unlimited) is serialized as null.
        def usageLimitsJson(limits: DatabaseManager.UsageLimits): String = {
            def cap(value: Int): String = if (value == Int.MaxValue) "null" else value.toString
            s"""{"maxAgents":${cap(limits.maxAgents)},"maxIterations":${cap(limits.maxIterations)},"densityFactor":${limits.densityFactor},"maxNetworks":${cap(limits.maxNetworks)}}"""
        }

        val userRoutes: Route = addCorsHeaders {
            pathPrefix("api" / "users") {
                // POST /api/users/sync — authenticated; uid comes from token, not body
                path("sync") {
                    post {
                        authenticate { authUser =>
                            val limits = DatabaseManager.getUsageLimits(authUser.roles)
                            // email/name/photo come from the token (the name is user-editable in
                            // Firebase Auth): JSON-escape them.
                            val responseJson = s"""{
                              |  "uid": "${authUser.uid}",
                              |  "email": ${JsString(authUser.email).compactPrint},
                              |  "name": ${JsString(authUser.name).compactPrint},
                              |  "photo": ${authUser.photo.map(p => JsString(p).compactPrint).getOrElse("null")},
                              |  "roles": [${authUser.roles.map(r => s""""$r"""").mkString(",")}],
                              |  "usageLimits": ${usageLimitsJson(limits)},
                              |  "deactivated": false
                              |}""".stripMargin
                            complete(StatusCodes.OK ->
                                HttpEntity(ContentTypes.`application/json`, responseJson))
                        }
                    }
                } ~
                // GET /api/users/info/{uid} — owner or admin only
                path("info" / Segment) { targetUid =>
                    get {
                        authenticate { authUser =>
                            if (authUser.uid == targetUid || authUser.isAdmin) {
                                DatabaseManager.getUserByFirebaseUid(targetUid) match {
                                    case Some(user) =>
                                        val rolesJson = user.roles.map(r => s""""$r"""").mkString(",")
                                        val limitsJson = usageLimitsJson(DatabaseManager.getUsageLimits(user.roles))
                                        complete(StatusCodes.OK -> HttpEntity(ContentTypes.`application/json`,
                                            s"""{"uid":"${user.firebaseUid}","email":${JsString(user.email).compactPrint},"name":${JsString(user.name).compactPrint},"roles":[$rolesJson],"usageLimits":$limitsJson,"deactivated":${user.deactivated}}"""))
                                    case None =>
                                        complete(StatusCodes.NotFound ->
                                            errorJson("not_found", "User not found"))
                                }
                            } else {
                                complete(StatusCodes.Forbidden ->
                                    errorJson("forbidden", "Cannot access another user's info"))
                            }
                        }
                    }
                } ~
                // POST /api/users/me/deactivate — authenticated; deactivates the caller's own account
                // (idempotent). From then on `authenticate` answers 401 "Account is deactivated" to
                // every REST call of the account; reactivation is manual (an admin, in the database).
                path("me" / "deactivate") {
                    post {
                        authenticate { authUser =>
                            if (DatabaseManager.deactivateUser(authUser.dbUserId)) {
                                Logger.log(s"User deactivated: ${authUser.uid}")
                                complete(StatusCodes.OK -> HttpEntity(ContentTypes.`application/json`,
                                    """{"deactivated":true}"""))
                            } else {
                                complete(StatusCodes.InternalServerError ->
                                    errorJson("internal_error", "Failed to deactivate account"))
                            }
                        }
                    }
                } ~
                // PUT /api/users/role/{uid}/{role} — admin only
                path("role" / Segment / Segment) { (targetUid, newRole) =>
                    put {
                        requireAdmin { _ =>
                            val validRoles = Set("Administrator", "Researcher", "BaseUser", "Guest")
                            if (!validRoles.contains(newRole)) {
                                complete(StatusCodes.BadRequest ->
                                    errorJson("invalid_body", s"Invalid role: $newRole"))
                            } else if (DatabaseManager.addUserRole(targetUid, newRole)) {
                                complete(StatusCodes.OK -> HttpEntity(ContentTypes.`application/json`,
                                    s"""{"uid":"$targetUid","role":"$newRole","action":"added"}"""))
                            } else {
                                complete(StatusCodes.InternalServerError ->
                                    errorJson("internal_error", "Failed to update role"))
                            }
                        }
                    }
                }
            }
        }
        
        // Startup reconciliation (#124): runs left 'running' by a previous process died with it.
        // Done before binding, so no run of this process exists yet.
        val reconciled = DatabaseManager.reconcileOrphanedRuns()
        if (reconciled > 0)
            Logger.log(s"Startup reconciliation: $reconciled run(s) left 'running' by a previous process set to 'error'")
        Logger.log(f"In-memory run stores: SimulationCache budget ${SimulationCache.budgetBytes / 1048576.0}%.0f MB, " +
            f"WS buffer budget ${WsRegistry.globalBudgetBytes / 1048576.0}%.0f MB, " +
            f"WS per-connection frame queue ${WsConnectionActor.dataBudgetBytes / 1048576.0}%.0f MB, " +
            s"ephemeral snapshot retention $EphemeralSnapshotRetentionMinutes min after the run ends")
        Logger.log(s"WS topology binary: ${if (wsSendsTopology) "sent (APP_WS_SEND_TOPOLOGY=true)" else "not sent (REST /topology only)"}")
        if (legacyRoutesEnabled)
            Logger.logWarning("APP_ENABLE_LEGACY_ROUTES=true: the removed legacy endpoints /run, /custom and " +
                "/neighbors (and their /api mounts) are ENABLED: anonymous, without usage limits. " +
                "Dev/test only, NEVER in production.")
        else
            Logger.log("Legacy endpoints /run, /custom, /neighbors: removed, they answer 410 Gone (#121)")

        // Periodic cleanup every 60 seconds
        import scala.concurrent.duration.*
        actorSystem.scheduler.scheduleWithFixedDelay(60.seconds, 60.seconds)(() => {
            SimulationCache.cleanup()
            WsRegistry.cleanup()
        })(actorSystem.dispatcher)

        // Ephemeral frame cleanup runs less often (storage cost, not correctness). Grace window and
        // period: see EphemeralFramesGraceMinutes / EphemeralFramesCleanupPeriodMinutes above.
        actorSystem.scheduler.scheduleWithFixedDelay(5.minutes, EphemeralFramesCleanupPeriodMinutes.minutes)(() => {
            val deleted = DatabaseManager.cleanupEphemeralFrames(EphemeralFramesGraceMinutes)
            if (deleted > 0) Logger.log(s"cleanupEphemeralFrames removed $deleted rows")
        })(actorSystem.dispatcher)

        import SimulationJsonProtocol.*

        // effectiveDensity is only sent for generated runs (#83, #122).
        def simCreatedJson(runId: Long, networkCount: Int, effectiveDensity: Option[Int] = None): String = {
            val densityField = effectiveDensity.map(d => s""""effectiveDensity":$d,""").getOrElse("")
            s"""{"runId":"$runId","status":"running","networkCount":$networkCount,$densityField"wsUrl":"/ws"}"""
        }

        def invalidBody(message: String): HttpResponse =
            HttpResponse(StatusCodes.BadRequest, entity = errorJson("invalid_body", message))

        // 422 usage_limit_exceeded for the first per-role quota the request exceeds. Shared by
        // /simulations/generated and both variants (JSON + binary) of /simulations/custom.
        // Custom runs always have a single network.
        def usageLimitError(limits: DatabaseManager.UsageLimits, agents: Int, iterations: Int,
                            networks: Int = 1): Option[HttpResponse] = {
            def exceeded(message: String, limit: Int, requested: Int): Option[HttpResponse] =
                Some(HttpResponse(StatusCodes.UnprocessableEntity,
                    entity = usageLimitJson(message, limit, requested)))
            if (agents > limits.maxAgents)
                exceeded("Agent count exceeds your limit", limits.maxAgents, agents)
            else if (iterations > limits.maxIterations)
                exceeded("Iteration limit exceeds your limit", limits.maxIterations, iterations)
            else if (networks > limits.maxNetworks)
                exceeded("Network count exceeds your limit", limits.maxNetworks, networks)
            else None
        }

        def runSummaryJson(r: DatabaseManager.RunSummary): String =
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

        def jsonOk(body: String): HttpResponse =
            HttpResponse(StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`application/json`, body))

        // Resolves frameRetention: only Administrators can request "persistent".
        // Returns Right(retention) on success, Left(errorResponse) on rejection.
        def resolveFrameRetention(requested: Option[String], isAdmin: Boolean): Either[HttpResponse, String] =
            requested.map(_.toLowerCase) match {
                case None | Some("ephemeral") => Right("ephemeral")
                case Some("persistent") if isAdmin => Right("persistent")
                case Some("persistent") =>
                    Left(HttpResponse(StatusCodes.Forbidden,
                        entity = errorJson("forbidden", "frameRetention=persistent requires Administrator role")))
                case Some(other) =>
                    Left(HttpResponse(StatusCodes.BadRequest,
                        entity = errorJson("invalid_body", s"Invalid frameRetention: $other")))
            }

        // Optional boolean query parameter (binary POST /simulations/custom). Same default as the
        // JSON field (absent → false); "true" / "false" in any case, anything else → 400 invalid_body.
        def booleanParam(name: String, value: Option[String]): Either[HttpResponse, Boolean] =
            value.map(_.toLowerCase) match {
                case None | Some("false") => Right(false)
                case Some("true")         => Right(true)
                case Some(other)          => Left(invalidBody(s"Invalid $name: $other (expected true or false)"))
            }

        val simulationRoutes: Route = addCorsHeaders {
            pathPrefix("simulations") {
                // POST /simulations/generated  — JSON body
                path("generated") {
                    post {
                        authenticate { authUser =>
                            entity(as[GeneratedRunReq]) { req =>
                                val limits = DatabaseManager.getUsageLimits(authUser.roles)
                                // 400 (impossible network) → 422 (role quotas) → frameRetention.
                                val checked: Either[HttpResponse, (Int, Int, String)] = for {
                                    agentsPerNetwork <- validateGeneratedRunReq(req).left.map(invalidBody)
                                    effectiveDensity = clampEffectiveDensity(req.density, limits.densityFactor, agentsPerNetwork)
                                    _ <- validateBiasCoverage(req.biasTypes, effectiveDensity, agentsPerNetwork)
                                             .toLeft(()).left.map(invalidBody)
                                    _ <- usageLimitError(limits, agentsPerNetwork, req.iterationLimit, req.numberOfNetworks)
                                             .toLeft(())
                                    retention <- resolveFrameRetention(req.frameRetention, authUser.isAdmin)
                                } yield (agentsPerNetwork, effectiveDensity, retention)
                                checked match {
                                    case Left(resp) => complete(resp)
                                    case Right((agentsPerNetwork, effectiveDensity, retention)) =>
                                        val persistFrames = req.persistFrames.getOrElse(false)
                                        val seed = req.seed.getOrElse(-1L) match {
                                            case -1L => System.currentTimeMillis() ^ System.nanoTime()
                                            case s   => s
                                        }
                                        val agentTypesArr = req.agentTypes.map { a =>
                                            (SilenceStrategies.fromByte(a.silenceStrategy.toByte),
                                             SilenceEffects.fromByte(a.silenceEffect.toByte),
                                             a.count)
                                        }.toArray
                                        val biasArr = req.biasTypes.map { b =>
                                            (CognitiveBiases.fromByte(b.biasType.toByte), b.count)
                                        }.toArray
                                        val runId = executeGeneratedRun(
                                            seed, req.saveMode.toByte, req.numberOfNetworks,
                                            effectiveDensity, req.iterationLimit, req.stopThreshold,
                                            agentTypesArr, biasArr,
                                            agentsPerNetwork,
                                            userId = Some(authUser.dbUserId),
                                            persistFrames = persistFrames,
                                            frameRetention = retention
                                        )
                                        complete(jsonOk(simCreatedJson(runId, req.numberOfNetworks, Some(effectiveDensity))))
                                }
                            }
                        }
                    }
                } ~
                // POST /simulations/custom — binary or JSON based on Content-Type
                path("custom") {
                    post {
                        authenticate { authUser =>
                            extractRequest { req =>
                                val ct = req.entity.contentType.mediaType.toString
                                if (ct.contains("octet-stream")) {
                                    // The binary payload has no persistFrames / frameRetention fields: they
                                    // come as optional query parameters with the JSON fields' semantics and
                                    // defaults (false / ephemeral). Decoded (no side effects) and checked
                                    // against the role quotas BEFORE anything is persisted or sent to the
                                    // Monitor (#122).
                                    parameters("persistFrames".?, "frameRetention".?) { (persistFramesParam, frameRetentionParam) =>
                                        entity(as[Payload]) { payload =>
                                            val limits = DatabaseManager.getUsageLimits(authUser.roles)
                                            val checked: Either[HttpResponse, (DecodedCustomRun, Boolean, String)] = for {
                                                persistFrames <- booleanParam("persistFrames", persistFramesParam)
                                                run <- decodeCustomRun(payload.data).left.map(invalidBody)
                                                _   <- usageLimitError(limits, run.agentNames.length, run.iterationLimit)
                                                           .toLeft(())
                                                retention <- resolveFrameRetention(frameRetentionParam, authUser.isAdmin)
                                            } yield (run, persistFrames, retention)
                                            checked match {
                                                case Left(resp) => complete(resp)
                                                case Right((run, persistFrames, retention)) =>
                                                    startCustomRun(run, Some(authUser.dbUserId), persistFrames, retention) match {
                                                        case Left(resp)   => complete(resp)
                                                        case Right(runId) => complete(jsonOk(simCreatedJson(runId, 1)))
                                                    }
                                            }
                                        }
                                    }
                                } else {
                                    entity(as[CustomRunReq]) { runReq =>
                                        val limits = DatabaseManager.getUsageLimits(authUser.roles)
                                        val checked: Either[HttpResponse, String] = for {
                                            _ <- validateCustomRunReq(runReq).toLeft(()).left.map(invalidBody)
                                            _ <- usageLimitError(limits, runReq.agents.size, runReq.iterationLimit)
                                                     .toLeft(())
                                            retention <- resolveFrameRetention(runReq.frameRetention, authUser.isAdmin)
                                        } yield retention
                                        checked match {
                                            case Left(resp) => complete(resp)
                                            case Right(retention) =>
                                                executeCustomRunFromJson(
                                                    runReq, Some(authUser.dbUserId),
                                                    persistFrames = runReq.persistFrames.getOrElse(false),
                                                    frameRetention = retention
                                                ) match {
                                                    case Left(resp)   => complete(resp)
                                                    case Right(runId) => complete(jsonOk(simCreatedJson(runId, 1)))
                                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } ~
                // GET /simulations/mine — paginated list for authenticated user
                path("mine") {
                    get {
                        authenticate { authUser =>
                            parameters("limit".as[Int].withDefault(20), "offset".as[Int].withDefault(0)) { (limit, offset) =>
                                val runs = DatabaseManager.getRunsForUser(authUser.dbUserId, limit, offset)
                                val items = runs.map(runSummaryJson).mkString(",")
                                complete(jsonOk(s"""{"runs":[$items],"limit":$limit,"offset":$offset}"""))
                            }
                        }
                    }
                } ~
                // GET /simulations — admin: all runs
                pathEndOrSingleSlash {
                    get {
                        requireAdmin { _ =>
                            parameters("limit".as[Int].withDefault(20), "offset".as[Int].withDefault(0)) { (limit, offset) =>
                                val runs = DatabaseManager.getAllRuns(limit, offset)
                                val items = runs.map(runSummaryJson).mkString(",")
                                complete(jsonOk(s"""{"runs":[$items],"limit":$limit,"offset":$offset}"""))
                            }
                        }
                    }
                } ~
                // Routes by runId
                pathPrefix(LongNumber) { runId =>
                    pathEndOrSingleSlash {
                        // GET /simulations/{runId}
                        get {
                            authenticate { authUser =>
                                DatabaseManager.getRunSummary(runId) match {
                                    case None => complete(StatusCodes.NotFound ->
                                        errorJson("not_found", "Run not found"))
                                    case Some(run) =>
                                        if (run.userId.contains(authUser.dbUserId) || authUser.isAdmin)
                                            complete(jsonOk(runSummaryJson(run)))
                                        else
                                            complete(StatusCodes.Forbidden ->
                                                errorJson("forbidden", "Access denied"))
                                }
                            }
                        } ~
                        // DELETE /simulations/{runId} — cancels a RUNNING run. Only the run path itself
                        // matches (it used to match any subpath). A finished run keeps its terminal
                        // status: 409 conflict (#124).
                        delete {
                            authenticate { authUser =>
                                DatabaseManager.getRunSummary(runId) match {
                                    case None => complete(StatusCodes.NotFound ->
                                        errorJson("not_found", "Run not found"))
                                    case Some(run) if !run.userId.contains(authUser.dbUserId) && !authUser.isAdmin =>
                                        complete(StatusCodes.Forbidden ->
                                            errorJson("forbidden", "Access denied"))
                                    case Some(run) if run.status != "running" =>
                                        complete(StatusCodes.Conflict ->
                                            errorJson("conflict", "Run already finished"))
                                    case Some(_) =>
                                        // Conditional on status = 'running': false means the run
                                        // finished between the read above and this update (or
                                        // the update itself failed — then it is still running).
                                        if (DatabaseManager.setRunStatus(runId, "cancelled")) {
                                            monitor ! CancelRun(runId)
                                            complete(jsonOk(s"""{"runId":"$runId","cancelled":true}"""))
                                        } else if (DatabaseManager.getRunSummary(runId).exists(_.status == "running")) {
                                            complete(StatusCodes.InternalServerError ->
                                                errorJson("internal_error", "Failed to cancel the run"))
                                        } else {
                                            complete(StatusCodes.Conflict ->
                                                errorJson("conflict", "Run already finished"))
                                        }
                                }
                            }
                        }
                    } ~
                    // GET /simulations/{runId}/networks
                    path("networks") {
                        get {
                            authenticate { authUser =>
                                DatabaseManager.getRunOwner(runId) match {
                                    case None => complete(StatusCodes.NotFound ->
                                        errorJson("not_found", "Run not found"))
                                    case Some(ownerId) if ownerId != authUser.dbUserId && !authUser.isAdmin =>
                                        complete(StatusCodes.Forbidden ->
                                            errorJson("forbidden", "Access denied"))
                                    case _ =>
                                        // Cache first (live runs, retention window); when it no
                                        // longer holds the run, the networks that still have
                                        // persisted frames (#124).
                                        val cached = SimulationCache.getNetworkIds(runId)
                                        val ids = if (cached.nonEmpty) cached else DatabaseManager.getFrameNetworkIds(runId)
                                        val items = ids.map(id => s""""$id"""").mkString(",")
                                        complete(jsonOk(s"""{"runId":"$runId","networks":[$items]}"""))
                                }
                            }
                        }
                    } ~
                    // GET /simulations/{runId}/networks/{networkId}/topology
                    // GET /simulations/{runId}/networks/{networkId}/results
                    pathPrefix("networks" / Segment) { networkId =>
                        authenticate { authUser =>
                            DatabaseManager.getRunOwner(runId) match {
                                case None => complete(StatusCodes.NotFound ->
                                    errorJson("not_found", "Run not found"))
                                case Some(ownerId) if ownerId != authUser.dbUserId && !authUser.isAdmin =>
                                    complete(StatusCodes.Forbidden ->
                                        errorJson("forbidden", "Access denied"))
                                case _ =>
                                    path("topology") {
                                        get {
                                            parameters(
                                                "agentOffset".as[Int].withDefault(0),
                                                "agentLimit".as[Int].withDefault(500),
                                                "edgeOffset".as[Int].withDefault(0),
                                                "edgeLimit".as[Int].withDefault(1000)
                                            ) { (aOff, aLim, eOff, eLim) =>
                                                SimulationCache.getTopology(runId, networkId) match {
                                                    case None => complete(StatusCodes.Accepted ->
                                                        errorJson("not_ready", "Topology not yet available"))
                                                    case Some(snap) =>
                                                        complete(jsonOk(topologyPageJson(snap, runId, networkId, aOff, aLim, eOff, eLim)))
                                                }
                                            }
                                        }
                                    } ~
                                    path("results") {
                                        get {
                                            parameters(
                                                "offset".as[Int].withDefault(0),
                                                "limit".as[Int].withDefault(500)
                                            ) { (off, lim) =>
                                                SimulationCache.getResults(runId, networkId) match {
                                                    case None => complete(StatusCodes.Accepted ->
                                                        errorJson("not_ready", "Results not yet available — run may still be in progress"))
                                                    case Some(snap) =>
                                                        complete(jsonOk(resultsPageJson(snap, runId, networkId, off, lim)))
                                                }
                                            }
                                        }
                                    } ~
                                    // GET /simulations/{runId}/networks/{networkId}/frames
                                    //   ?round=N           single round
                                    //   ?from=A&to=B       range (capped at 1000 rounds)
                                    //   ?round=last        most recent persisted round
                                    // Body is the WS on-the-wire format (concatenated slices).
                                    path("frames") {
                                        get {
                                            parameters(
                                                "round".?,
                                                "from".as[Int].?,
                                                "to".as[Int].?
                                            ) { (roundParam, fromOpt, toOpt) =>
                                                val networkUuid = try Some(java.util.UUID.fromString(networkId))
                                                                  catch { case _: IllegalArgumentException => None }
                                                networkUuid match {
                                                    case None => complete(StatusCodes.BadRequest ->
                                                        errorJson("invalid_body", "Invalid networkId UUID"))
                                                    case Some(nid) =>
                                                        val range: Either[String, (Int, Int)] = roundParam match {
                                                            case Some("last") =>
                                                                DatabaseManager.getLastFrameRound(nid) match {
                                                                    case Some(r) => Right((r, r))
                                                                    case None    => Left("no_frames")
                                                                }
                                                            case Some(r) =>
                                                                try { val v = r.toInt; Right((v, v)) }
                                                                catch { case _: NumberFormatException => Left("invalid_round") }
                                                            case None =>
                                                                (fromOpt, toOpt) match {
                                                                    case (Some(f), Some(t)) if f <= t => Right((f, t))
                                                                    case _ => Left("missing_range")
                                                                }
                                                        }
                                                        range match {
                                                            case Left("no_frames") =>
                                                                complete(StatusCodes.NotFound ->
                                                                    errorJson("frames_not_persisted",
                                                                        "No frames stored for this network (persistFrames was false, or ephemeral frames already expired)"))
                                                            case Left("invalid_round") =>
                                                                complete(StatusCodes.BadRequest ->
                                                                    errorJson("invalid_body", "round must be an integer or 'last'"))
                                                            case Left(_) =>
                                                                complete(StatusCodes.BadRequest ->
                                                                    errorJson("invalid_body", "Provide ?round=N|last or ?from=A&to=B"))
                                                            case Right((fromR, toR)) =>
                                                                val span = toR - fromR + 1
                                                                if (span > 1000)
                                                                    complete(StatusCodes.BadRequest ->
                                                                        errorJson("range_too_large", "Frame range capped at 1000 rounds per request"))
                                                                else {
                                                                    val bytes = DatabaseManager.getFramesInRange(nid, fromR, toR)
                                                                    if (bytes.isEmpty)
                                                                        complete(StatusCodes.NotFound ->
                                                                            errorJson("frames_not_persisted",
                                                                                "No frames stored in this range (persistFrames was false, or ephemeral frames already expired)"))
                                                                    else
                                                                        complete(HttpResponse(StatusCodes.OK,
                                                                            entity = HttpEntity(ContentTypes.`application/octet-stream`, bytes)))
                                                                }
                                                        }
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }

        val openapiYaml: String = scala.io.Source.fromResource("openapi.yaml").mkString

        val docsRoute: Route = addCorsHeaders {
            path("openapi.yaml") {
                get {
                    complete(HttpEntity(
                        ContentType(MediaType.applicationWithFixedCharset("x-yaml", HttpCharsets.`UTF-8`)),
                        openapiYaml
                    ))
                }
            } ~
            path("docs") {
                get {
                    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
                        s"""<!DOCTYPE html>
                           |<html>
                           |<head>
                           |  <title>BES API Docs</title>
                           |  <meta charset="utf-8"/>
                           |  <meta name="viewport" content="width=device-width, initial-scale=1">
                           |  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
                           |</head>
                           |<body>
                           |<div id="swagger-ui"></div>
                           |<script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                           |<script>
                           |SwaggerUIBundle({
                           |  // RELATIVA a propósito: esta página se sirve como /docs (backend
                           |  // directo) y como /api/docs (prod, detrás del reverse proxy). Con
                           |  // "/openapi.yaml" absoluta, en prod el fetch caía en el origen raíz
                           |  // → el SPA del front devolvía su index.html y Swagger UI quedaba vacío.
                           |  url: "openapi.yaml",
                           |  dom_id: '#swagger-ui',
                           |  deepLinking: true,
                           |  presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
                           |  layout: "BaseLayout"
                           |})
                           |</script>
                           |</body>
                           |</html>""".stripMargin))
                }
            }
        }

        val homeRoute: Route = addCorsHeaders {
            pathEndOrSingleSlash {
                get {
                    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
                        """
                          |<html>
                          |  <head>
                          |    <title>Simulation Server</title>
                          |  </head>
                          |  <body>
                          |    <h1>Simulation Server</h1>
                          |    <p>API docs (Swagger UI): /docs &mdash; OpenAPI spec: /openapi.yaml</p>
                          |    <p>Simulations (authenticated): POST /simulations/generated, POST /simulations/custom</p>
                          |    <p>WebSocket endpoint: /ws</p>
                          |    <p>Every path is also served under the /api prefix.</p>
                          |  </body>
                          |</html>
                            """.stripMargin))
                }
            }
        }
        
        val baseRoutes: Route = corsHandler ~ webSocketRoute ~ legacyRoute ~ userRoutes ~ simulationRoutes ~ docsRoute ~ homeRoute
        // Dual-mount: el reverse proxy del LIPN expone el backend bajo /api y
        // no controlamos si strippea el prefijo — aceptamos ambas formas
        // (/ws y /api/ws, /simulations y /api/simulations, etc.).
        val routes: Route = baseRoutes ~ pathPrefix("api")(baseRoutes)
        val bindingFuture = Http().newServerAt(serverHost, serverPort).bind(routes)
        
        bindingFuture.onComplete {
            case scala.util.Success(binding) =>
                val address = binding.localAddress
                Logger.log(s"Server online at http://${address.getHostString}:${address.getPort}/")
                Logger.log(s"API docs: http://${address.getHostString}:${address.getPort}/docs")
                Logger.log(s"Simulations API: http://${address.getHostString}:${address.getPort}/simulations (authenticated)")
                Logger.log(s"WebSocket endpoint: ws://${address.getHostString}:${address.getPort}/ws")
            case scala.util.Failure(ex) =>
                Logger.log(s"Failed to bind server: ${ex.getMessage}")
                system.get.terminate()
        }
        
        initialized = true
    }
    
    /**
     * Broadcasts binary simulation data to all WebSocket clients connected to a specific channel.
     *
     * Receives the binary packet created by AgentProcessor.sendRoundToWebSocketServer() and distributes
     * it to all connected WebSocket clients for that simulation run. This enables real-time visualization
     * of agent states and beliefs during simulation execution. Uses Akka Streams for efficient
     * message delivery to multiple concurrent clients.
     *
     * '''Message Flow:'''
     * {{{
     * AgentProcessor ──▶ Server (this) ──▶ WebSocket Clients (Browser/Frontend)
     * }}}
     *
     * '''Binary Data Layout:'''
     * {{{
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │ INPUT: ByteBuffer from AgentProcessor                                │
     * ├──────────────────────────────────────────────────────────────────────┤
     * │ HEADER (36 bytes)   │ networkId + runID + numOfAgents + round + range│
     * │ BELIEF DATA (n*8)   │ Public + Private beliefs for n agents          │
     * │ SPEAKING DATA (n*1) │ Speaking state (0/1) for n agents              │
     * ├──────────────────────────────────────────────────────────────────────┤
     * │ OUTPUT: BinaryMessage via WebSocket                                  │
     * └──────────────────────────────────────────────────────────────────────┘
     * }}}
     *
     * '''Error Handling:'''
     *  - Server not initialized: Logs error and returns early
     *  - Channel not found: Logs error when no active WebSocket connections exist
     *  - Network failures: Handled by Akka Streams internally
     *
     * '''Performance Notes:'''
     *  - Uses Akka Streams for backpressure handling
     *  - ByteString wraps buffer for zero-copy transmission
     *  - Single Source broadcasts to multiple clients efficiently
     *  - Debug logging only enabled when APP_MODE.hasServerLogs is true
     *
     * @param runId Unique identifier for the simulation run
     * @param buffer Binary packet containing agent states (created by AgentProcessor.sendRoundToWebSocketServer)
     */
    def sendSimulationBinaryData(runId: Long, buffer: ByteBuffer): Unit = {
        if (!initialized) return
        if (GlobalState.APP_MODE.hasServerLogs) logBufferDebugInfo(buffer)
        WsRegistry.dispatchBinary(runId, ByteString(buffer))
    }
    
    /**
     * Broadcasts neighbor topology data to all WebSocket clients connected to a specific channel.
     * No-op unless [[wsSendsTopology]] (`APP_WS_SEND_TOPOLOGY=true`, off by default since #119).
     *
     * Receives the binary packet created by Network.sendNeighbors() and distributes it to all
     * connected WebSocket clients for that simulation run. This enables real-time visualization
     * of the agent network topology and relationships. Uses Akka Streams for efficient message
     * delivery to multiple concurrent clients.
     *
     * '''Message Flow:'''
     * {{{
     * Network.sendNeighbors() ──▶ Server (this) ──▶ WebSocket Clients (Browser/Frontend)
     * }}}
     *
     * '''Binary Data Layout:'''
     * {{{
     * ┌─────────────────────────────────────────────────────────────────────────────┐
     * │ INPUT: ByteBuffer from Network.sendNeighbors() (little-endian)              │
     * ├─────────────────────────────────────────────────────────────────────────────┤
     * │ HEADER (32 bytes)      │ networkId(16) + runId(8) + numAgents(4) + numN(4)│
     * │ INDEX OFFSETS (n*4)    │ Agent index mapping for n agents (CSR format)   │
     * │ NEIGHBOR REFS (m*4)    │ Neighbor reference indices for m connections    │
     * │ NEIGHBOR WEIGHTS (m*4) │ Connection weights for m neighbor pairs         │
     * │ NEIGHBOR BIASES (m*1)  │ Cognitive bias types for m connections          │
     * ├─────────────────────────────────────────────────────────────────────────────┤
     * │ OUTPUT: BinaryMessage via WebSocket                                         │
     * └─────────────────────────────────────────────────────────────────────────────┘
     * }}}
     *
     * '''Error Handling:'''
     *  - Server not initialized: Logs error and returns early
     *  - Channel not found: Silent failure (no active WebSocket connections)
     *  - Network failures: Handled by Akka Streams internally
     *
     * '''Performance Notes:'''
     *  - Uses Akka Streams for backpressure handling
     *  - ByteString wraps buffer for zero-copy transmission
     *  - Single Source broadcasts to multiple clients efficiently
     *  - Debug logging only enabled when APP_MODE.hasServerLogs is true
     *
     * @param runId Unique identifier for the simulation run
     * @param buffer Binary packet containing network topology data (created by Network.sendNeighbors)
     */
    def sendNeighborBinaryData(runId: Long, buffer: ByteBuffer): Unit = {
        if (!initialized || !wsSendsTopology) return
        WsRegistry.dispatchBinary(runId, ByteString(buffer))
    }

    /**
     * Whether the topology binary above is sent over the WebSocket (#119): off by default, on with
     * `APP_WS_SEND_TOPOLOGY=true`. Clients read the topology from REST `GET /topology` (cached at
     * `topology_ready`, which is still emitted); the binary costs several MB per network on the
     * socket and in the run's replay buffer, and its size `32 + 4n + 9m` equals a frame's
     * `36 + 9n` whenever `9m = 5n + 4`, so with it off frames are the only binary messages.
     */
    val wsSendsTopology: Boolean = sys.env.get("APP_WS_SEND_TOPOLOGY").exists(_.trim.equalsIgnoreCase("true"))

    def sendControlEvent(runId: Long, json: String): Unit = {
        if (!initialized) return
        WsRegistry.dispatchText(runId, json)
    }

    /**
     * WS control event of a run that ended in status `error` (#124). Same `event: "error"` shape the
     * frontend handles; `runId` routes it to the run's subscription.
     */
    def runErrorEventJson(runId: Long, message: String): String =
        JsObject(
            "event"   -> JsString("error"),
            "runId"   -> JsString(runId.toString),
            "code"    -> JsString("run_failed"),
            "message" -> JsString(message)
        ).compactPrint

    /** Starts the post-run retention of every in-memory store of a run (#124). */
    def markRunTerminal(runId: Long): Unit = {
        WsRegistry.markTerminal(runId)
        SimulationCache.markTerminal(runId)
    }

    private def topologyPageJson(snap: TopologySnapshot, runId: Long, networkId: String,
        aOff: Int, aLim: Int, eOff: Int, eLim: Int): String = {
        val aEnd = math.min(aOff + aLim, snap.agentCount)
        val agents = (math.min(aOff, snap.agentCount) until aEnd).map { i =>
            val name = if (snap.names != null) s""""${snap.names(i)}"""" else "null"
            s"""{"index":$i,"name":$name,"initialBelief":${snap.initialBeliefs(i)},"toleranceRadius":${snap.tolRadius(i)},"toleranceOffset":${snap.tolOffset(i)},"silenceStrategy":${snap.silenceStrategies(i) & 0xFF},"silenceEffect":${snap.silenceEffects(i) & 0xFF}}"""
        }.mkString(",")
        val eStart = math.min(eOff, snap.edgeCount)
        val eEnd   = math.min(eOff + eLim, snap.edgeCount)
        val edges  = edgesPageJson(snap, eStart, eEnd)
        s"""{"runId":"$runId","networkId":"$networkId","agentCount":${snap.agentCount},"edgeCount":${snap.edgeCount},"agentOffset":$aOff,"agentLimit":$aLim,"edgeOffset":$eOff,"edgeLimit":$eLim,"agents":[$agents],"edges":[$edges]}"""
    }

    /**
     * Edges `[eStart, eEnd)` of the snapshot's CSR, in CSR order. Row `owner` lists the agents that
     * `owner` listens to (`neighborsRefs(j)`, with the weight `owner` gives it), for custom AND
     * generated networks, so each entry is reported as `source` = `neighborsRefs(j)` (the influencer)
     * and `target` = `owner` (the agent whose belief is updated): "source influences target", the
     * same meaning as the custom-run request edges.
     */
    private def edgesPageJson(snap: TopologySnapshot, eStart: Int, eEnd: Int): String = {
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

    private def resultsPageJson(snap: ResultsSnapshot, runId: Long, networkId: String, off: Int, lim: Int): String = {
        val start = math.min(off, snap.agentCount)
        val end   = math.min(off + lim, snap.agentCount)
        val agents = (start until end).map { i =>
            val name = if (snap.names != null) s""""${snap.names(i)}"""" else "null"
            s"""{"index":$i,"name":$name,"finalBelief":${snap.finalBeliefs(i)},"publicBelief":${snap.publicBeliefs(i)}}"""
        }.mkString(",")
        s"""{"runId":"$runId","networkId":"$networkId","finalRound":${snap.finalRound},"consensus":${snap.consensus},"agentCount":${snap.agentCount},"offset":$off,"limit":$lim,"agents":[$agents]}"""
    }

    /**
     * Dev/test-only escape hatch of #121: with `APP_ENABLE_LEGACY_ROUTES=true` the removed legacy
     * endpoints `POST /run`, `POST /custom` and `GET /neighbors` keep their pre-#121 behavior
     * (anonymous, no usage limits, `/run` without bounds checks), e.g. to start a run in a throwaway
     * container without a Firebase token. Off by default: they answer 410 Gone. NEVER enable it in
     * production (docker-compose.prod.yml forces it to false); a WARNING is logged at startup when on.
     */
    val legacyRoutesEnabled: Boolean =
        sys.env.get("APP_ENABLE_LEGACY_ROUTES").exists(_.trim.equalsIgnoreCase("true"))

    // Headers of the legacy routes when APP_ENABLE_LEGACY_ROUTES=true (pre-#121 behavior, kept as is).
    private val deprecationSunset = "Tue, 01 Sep 2026 00:00:00 GMT"

    private def withDeprecation(successor: String): Directive0 =
        respondWithHeaders(
            RawHeader("Deprecation", "true"),
            RawHeader("Sunset", deprecationSunset),
            RawHeader("Link", s"<$successor>; rel=\"successor-version\""),
            RawHeader("Warning", s"""299 - "Deprecated API. Migrate to $successor." """)
        )

    // At most this many legacy_endpoint_usage inserts in flight (#121). The 410 answer is anonymous,
    // so a flood of requests must not become one blocking JDBC call per request on the dispatcher;
    // calls beyond the cap are not recorded (the table detects remaining callers, it does not count
    // every call).
    private val MaxLegacyLogsInFlight = 4
    private val legacyLogsInFlight = new java.util.concurrent.atomic.AtomicInteger(0)

    private def logLegacy(endpoint: String, userAgent: Option[String], forwardedFor: Option[String]): Unit =
        if (!GlobalState.APP_MODE.skipDatabase) {
            if (legacyLogsInFlight.incrementAndGet() > MaxLegacyLogsInFlight) legacyLogsInFlight.decrementAndGet()
            else {
                import scala.concurrent.Future
                implicit val ec: ExecutionContextExecutor = system.get.dispatcher
                // X-Forwarded-For can be a list ("client, proxy, ..."); the ip column is a single inet.
                val ip = forwardedFor.flatMap(_.split(',').headOption).map(_.trim).filter(_.nonEmpty)
                Future(DatabaseManager.logLegacyUsage(endpoint, None, userAgent, ip))
                    .onComplete(_ => legacyLogsInFlight.decrementAndGet())
            }
        }

    private def executeGeneratedRun(
        seed: Long, saveMode: Byte, numberOfNetworks: Int, density: Int,
        iterationLimit: Int, stopThreshold: Float,
        agentTypes: Array[(SilenceStrategy, SilenceEffect, Int)],
        biases: Array[(Bias, Int)],
        agentsPerNetwork: Int,
        confidenceParams: mutable.Map[Int, (Float, Float)] = mutable.Map(),
        userId: Option[Int] = None,
        persistFrames: Boolean = false,
        frameRetention: String = "ephemeral"
    ): Long = {
        var runID = SnowflakeID.generateId()
        // Save mode forced to DEBUG BY DESIGN in this phase (#133): while APP_LEGACY_DB=false any
        // requested saveMode becomes DEBUG, so no per-agent data is written. Only the run row
        // (configuration and distributions, owner, status) and, with persistFrames, the replay
        // frames are stored (ephemeral frames: deleted 60–90 min after the run ends, see
        // EphemeralFramesGraceMinutes). The frontend no longer offers a save-mode choice and always
        // sends 1 (STANDARD). To revisit (e.g. once the platform has real users): this condition,
        // here AND in startCustomRun, is the switch; per-agent saving currently relies on the
        // legacy-DB path (Network.agentsIds exists only with APP_LEGACY_DB=true), so it has to be
        // ported to the current schema first, and the wizard's selector brought back.
        val convertedSaveMode = if (GlobalState.APP_MODE.skipDatabase || !GlobalState.APP_MODE.usesLegacyDB)
            SaveModes.DEBUG
        else
            SaveModes.codeToSaveMode(saveMode)

        if (GlobalState.APP_MODE.usesLegacyDB && convertedSaveMode.savesToDB) {
            runID = DatabaseManager.createRun(
                RunMode.GENERATED, saveMode, numberOfNetworks, Some(density), Some(2.5f),
                stopThreshold, iterationLimit, "uniform"
            ).get
        } else if (!GlobalState.APP_MODE.skipDatabase) {
            DatabaseManager.saveGeneratedRun(
                id = runID,
                seed = seed,
                density = density,
                iterationLimit = iterationLimit,
                totalNetworks = numberOfNetworks,
                agentsPerNetwork = agentsPerNetwork,
                stopThreshold = stopThreshold,
                agentTypeDistributions = agentTypes,
                cognitiveBiasDistributions = biases,
                userId = userId,
                frameRetention = frameRetention
            )
        }

        SimulationCache.registerRun(runID, persistent = frameRetention == "persistent")
        monitor.get ! AddNetworks(
            runID, agentTypes, biases, confidenceParams,
            Uniform, convertedSaveMode, numberOfNetworks, density,
            iterationLimit, seed, 2.5f, stopThreshold,
            persistFrames
        )

        runID
    }

    // Legacy POST /run, mounted only with APP_ENABLE_LEGACY_ROUTES=true (#121). No bounds checks.
    private def parseGeneratedRun(data: Array[Byte]): String = {
        val saveMode = data(1)
        val agentTypeCount = data(2)
        val biasTypeCount = data(3)
        val numberOfNetworks = bytesToInt(data, 4)
        val density = bytesToInt(data, 8)
        val iterationLimit = bytesToInt(data, 12)
        val stopThreshold = bytesToFloat(data, 16)
        val seed: Long = if (bytesToLong(data, 20) == -1) {
            System.currentTimeMillis() ^ System.nanoTime()
        } else {
            bytesToLong(data, 20)
        }

        val agentTypes = new Array[(SilenceStrategy, SilenceEffect, Int)](agentTypeCount)
        val confidenceParams: mutable.Map[Int, (Float, Float)] = mutable.Map()
        var curOffset = 28
        var agentsPerNetwork = 0
        for (i <- 0 until agentTypeCount) {
            val count = bytesToInt(data, curOffset)
            val silenceStrategyType = SilenceStrategies.fromByte(data(curOffset + 4))
            val silenceEffectType = SilenceEffects.fromByte(data(curOffset + 5))
            agentTypes(i) = (silenceStrategyType, silenceEffectType, count)
            curOffset += 6
            agentsPerNetwork += count
        }

        val biases = new Array[(Bias, Int)](biasTypeCount)
        for (i <- 0 until biasTypeCount) {
            val count = bytesToInt(data, curOffset)
            val biasType: Bias = CognitiveBiases.fromByte(data(curOffset + 4))
            biases(i) = (biasType, count)
            curOffset += 5
        }

        executeGeneratedRun(
            seed, saveMode, numberOfNetworks, density, iterationLimit, stopThreshold,
            agentTypes, biases, agentsPerNetwork, confidenceParams, userId = None
        ).toString
    }
    
    /** Rejected request detail, surfaced as 400 invalid_body (never an actor crash, #122). */
    private final class InvalidRunRequest(message: String) extends RuntimeException(message)

    // Codes accepted by the simulator (openapi.yaml enums). Anything else makes `.name` throw while
    // persisting, or throws inside the agents' update loop for biases.
    private def isSilenceStrategy(code: Int): Boolean = code >= 0 && code <= SilenceStrategies.CONFIDENCE.toInt
    private def isSilenceEffect(code: Int): Boolean = code >= 0 && code <= SilenceEffects.MEMORYLESS.toInt
    private def isBias(code: Int): Boolean = code >= 0 && code <= CognitiveBiases.INSULAR.toByte

    /**
     * `custom_runs.run_name` and `custom_agents.name` are varchar(32). A longer name made
     * saveCustomRun roll back while the run still started without its owner row, so both custom
     * variants reject it up front. Postgres counts characters (code points), not bytes or UTF-16
     * units, hence codePointCount.
     */
    private val MaxCustomNameLength = 32

    /** Some(message) (→ 400 invalid_body) when `name` does not fit the varchar(32) columns. */
    private def customNameTooLong(field: String, name: String): Option[String] = {
        val length = name.codePointCount(0, name.length)
        if (length > MaxCustomNameLength)
            Some(s"$field must be at most $MaxCustomNameLength characters (got $length)")
        else None
    }

    /**
     * Some(message) (→ 400 invalid_body) naming the first repeated agent name. `custom_agents` is
     * keyed by (run_id, name), so a repeated name made saveCustomRun fail (500), and edges refer to
     * agents by name, so they would be ambiguous anyway. Exact, case-sensitive match, like the key.
     */
    private def duplicateAgentName(names: IterableOnce[String]): Option[String] = {
        val seen = mutable.HashSet.empty[String]
        names.iterator.find(name => !seen.add(name)).map(name => s"Duplicate agent name '$name'")
    }

    /**
     * Some(message) (→ 400 invalid_body) naming the first repeated directed edge (same source and
     * target, exact match). `custom_neighbors` is keyed by (run_id, source, target), so a repeated
     * edge made saveCustomRun fail (500). Reverse edges (B -> A after A -> B) are distinct.
     */
    private def duplicateEdge(edges: IterableOnce[(String, String)]): Option[String] = {
        val seen = mutable.HashSet.empty[(String, String)]
        edges.iterator.find(edge => !seen.add(edge)).map { case (source, target) =>
            s"Duplicate edge '$source' -> '$target'"
        }
    }

    /**
     * Effective Barabási–Albert `m` of a generated run: `density × densityFactor` (#83) clamped to
     * `[1, agentsPerNetwork - 1]` (#122). Bounds derived from `Network.scala` (`BuildNetwork`):
     *  - `m = 0` builds no edges at all (phase 1 core is a single agent, phase 2 picks 0 neighbours
     *    per agent), so every agent would be isolated → lower bound 1 (m = 1 still builds a tree).
     *  - phase 1 fully connects agents `0..m` through `indexOffset` (length n) and
     *    `FenwickTree(n, m, ...)` seeds `m + 1` slots of an `n + 1` array, so `m + 1 <= n`
     *    (m >= n throws ArrayIndexOutOfBounds inside the actor; m = n - 1 is the complete graph)
     *    → upper bound `agentsPerNetwork - 1`.
     * The requested density is already validated to that range (see validateGeneratedRunReq) and
     * densityFactor <= 1, so the clamp only lifts `floor(density × densityFactor) = 0` to 1; the
     * upper bound is defensive.
     */
    private def clampEffectiveDensity(density: Int, densityFactor: Double, agentsPerNetwork: Int): Int =
        math.min(math.max((density * densityFactor).toInt, 1), agentsPerNetwork - 1)

    /**
     * Rejects generated runs that cannot be built (#122). Returns agentsPerNetwork on success.
     * The requested density must satisfy `1 <= density < agentsPerNetwork` (see
     * clampEffectiveDensity), which also requires at least 2 agents per network.
     */
    private def validateGeneratedRunReq(req: GeneratedRunReq): Either[String, Int] = {
        val totalAgents = req.agentTypes.iterator.map(_.count.toLong).sum
        if (req.numberOfNetworks < 1)
            Left("numberOfNetworks must be at least 1")
        else if (req.iterationLimit < 1)
            Left("iterationLimit must be at least 1")
        else if (req.agentTypes.isEmpty || req.agentTypes.exists(_.count < 1))
            Left("agentTypes must not be empty and every count must be at least 1")
        else if (req.agentTypes.exists(a => !isSilenceStrategy(a.silenceStrategy) || !isSilenceEffect(a.silenceEffect)))
            Left("agentTypes contains an unknown silenceStrategy or silenceEffect")
        else if (req.biasTypes.isEmpty || req.biasTypes.exists(_.count < 1))
            Left("biasTypes must not be empty and every count must be at least 1")
        else if (req.biasTypes.exists(b => !isBias(b.biasType)))
            Left("biasTypes contains an unknown biasType")
        else if (totalAgents > Int.MaxValue)
            Left("Total agent count is too large")
        else if (req.density < 1 || req.density >= totalAgents)
            Left(s"density must satisfy 1 <= density < agentsPerNetwork ($totalAgents), got ${req.density}")
        else
            Right(totalAgents.toInt)
    }

    /**
     * Every directed edge of a generated network receives a bias from biasTypes: each agent actor
     * consumes the counts edge by edge (AgentProcessor) and indexes past the last bias type once all
     * of them reach 0, so the counts (one per distinct biasType, the last one wins as in Network)
     * must cover the `E = m(m-1) + (n-m)·2m` edges of a network built with m = effectiveDensity.
     * E must also fit the Int-sized arrays Network allocates.
     */
    private def validateBiasCoverage(biasTypes: Seq[BiasTypeReq], effectiveDensity: Int,
                                     agentsPerNetwork: Int): Option[String] = {
        val m = effectiveDensity.toLong
        val edges = m * (m - 1) + (agentsPerNetwork - m) * 2 * m
        val covered = biasTypes.map(b => b.biasType -> b.count.toLong).toMap.values.sum
        if (edges > Int.MaxValue - 8)
            Some(s"Network too large: $edges edges per network")
        else if (covered < edges)
            Some(s"biasTypes counts ($covered) must cover the $edges edges of each network (effectiveDensity $effectiveDensity)")
        else None
    }

    /** Rejects JSON custom runs the simulator cannot run (#122); None when the request is valid. */
    private def validateCustomRunReq(req: CustomRunReq): Option[String] = {
        lazy val names = req.agents.iterator.map(_.name).toSet
        if (req.agents.isEmpty) Some("agents must not be empty")
        else if (req.iterationLimit < 1) Some("iterationLimit must be at least 1")
        else customNameTooLong("name", req.name).orElse(req.agents.iterator.zipWithIndex
            .flatMap { case (a, i) => customNameTooLong(s"agents[$i].name", a.name) }
            .nextOption()
        ).orElse(duplicateAgentName(req.agents.iterator.map(_.name))
        ).orElse(req.agents.collectFirst {
            case a if !isSilenceStrategy(a.silenceStrategy) =>
                s"Agent '${a.name}' has an unknown silenceStrategy (${a.silenceStrategy})"
            case a if !isSilenceEffect(a.silenceEffect) =>
                s"Agent '${a.name}' has an unknown silenceEffect (${a.silenceEffect})"
        }).orElse(req.edges.collectFirst {
            case e if !names.contains(e.source) => s"Edge source '${e.source}' is not a defined agent"
            case e if !names.contains(e.target) => s"Edge target '${e.target}' is not a defined agent"
            case e if !isBias(e.bias)           => s"Edge ${e.source} -> ${e.target} has an unknown bias (${e.bias})"
        }).orElse(duplicateEdge(req.edges.iterator.map(e => (e.source, e.target))))
    }

    /**
     * A custom run ready to persist and start — produced by both the binary and the JSON variants.
     * `sortedSource` / `sortedTarget` / `sortedInfluences` / `sortedBiases` are the user's edges with
     * the user's meaning (`sortedSource(k)` influences `sortedTarget(k)`), reordered by TARGET (the
     * listening agent; stable, so a listener's edges keep their input order). That is the simulator's
     * CSR order, so `indexOffset` = buildEndOffsets(sortedTarget) and `sortedSource` is the neighbor
     * reference array (see CustomRunInfo).
     */
    private final case class DecodedCustomRun(
        networkName: String,
        stopThreshold: Float,
        iterationLimit: Int,
        saveMode: Byte,
        agentNames: Array[String],
        initialBeliefs: Array[Float],
        toleranceRadius: Array[Float],
        toleranceOffset: Array[Float],
        silenceStrategies: Array[SilenceStrategy],
        silenceEffects: Array[SilenceEffect],
        majorityThreshold: Option[mutable.Map[Int, Float]],
        confidences: Option[mutable.Map[Int, Float]],
        indexOffset: Array[Int],
        sortedSource: Array[Int],
        sortedTarget: Array[Int],
        sortedInfluences: Array[Float],
        sortedBiases: Array[Bias]
    )

    /**
     * CSR row pointers of a custom network, shared by the binary and the JSON variants (#131).
     * The simulator reads `indexOffset` by AGENT index: agent `i` uses the edges
     * `[indexOffset(i - 1), indexOffset(i))` (from 0 for agent 0) of the edge arrays sorted by
     * listening agent — the edge TARGET, since an edge `source -> target` means the target updates
     * its belief from the source — so `indexOffset(i)` is the END of agent `i`'s range
     * (AgentProcessor; same convention as the generated networks built in Network). Counting the
     * edges of each target agent and accumulating gives every agent its own entry: an agent that is
     * the target of no edge (nobody influences it) gets an empty range
     * (`indexOffset(i) == indexOffset(i - 1)`). Writing one entry per DISTINCT row agent instead
     * shifted the range of every later agent onto the wrong agent. When every agent has at least one
     * edge both constructions give the same array.
     * `sortedTargets` must be sorted ascending, with every value in `[0, numberOfAgents)`.
     */
    private def buildEndOffsets(sortedTargets: Array[Int], numberOfAgents: Int): Array[Int] = {
        val indexOffset = new Array[Int](numberOfAgents)
        for (target <- sortedTargets) indexOffset(target) += 1
        for (i <- 1 until numberOfAgents) indexOffset(i) += indexOffset(i - 1)
        indexOffset
    }

    /**
     * Legacy POST /custom and GET /neighbors, mounted only with APP_ENABLE_LEGACY_ROUTES=true (#121):
     * run id as plain text, 400 JSON when malformed, 500 JSON when the run could not be saved (see
     * startCustomRun).
     */
    private def legacyCustomRun(data: Array[Byte]): HttpResponse =
        decodeCustomRun(data) match {
            case Left(message) =>
                HttpResponse(StatusCodes.BadRequest, entity = errorJson("invalid_body", message))
            case Right(run) =>
                startCustomRun(run) match {
                    case Left(resp)   => resp
                    case Right(runId) => HttpResponse(StatusCodes.OK, entity = HttpEntity(runId.toString))
                }
        }

    /**
     * Decodes the little-endian binary custom-run payload (legacy `POST /custom` format, also sent
     * to `POST /simulations/custom` as application/octet-stream) WITHOUT side effects, so callers
     * can apply the role quotas before anything is persisted or started (#122). Every read is
     * bounds-checked; truncated sections, unknown strategy/effect/bias codes, repeated agent names,
     * edges naming an undefined agent and repeated edges yield Left(message) (→ 400 invalid_body)
     * instead of an exception.
     */
    private def decodeCustomRun(data: Array[Byte]): Either[String, DecodedCustomRun] =
        try Right(decodeCustomRunUnchecked(data))
        catch {
            case e: InvalidRunRequest => Left(e.getMessage)
            case NonFatal(e)          => Left(s"Malformed binary payload (${e.getClass.getSimpleName})")
        }

    private def decodeCustomRunUnchecked(data: Array[Byte]): DecodedCustomRun = {
        var offset = 0
        def invalid(message: String): Nothing = throw new InvalidRunRequest(message)
        // Ensures `bytes` more bytes are available at the current offset.
        def need(bytes: Long, section: String): Unit =
            if (bytes < 0 || offset.toLong + bytes > data.length)
                invalid(s"Malformed binary payload: truncated $section")
        // Length prefixes are written as unsigned bytes (frontend encoder: Uint8).
        def readString(section: String): String = {
            need(1, section)
            val length = data(offset) & 0xFF
            offset += 1
            need(length, section)
            val str = byteArrToString(data, offset, length)
            offset += length
            str
        }

        // Header
        need(10, "header")
        val stopThreshold = bytesToFloat(data, 0)
        val iterationLimit = bytesToInt(data, 4)
        val saveMode = data(8)
        offset = 9
        val networkName = readString("network name")
        if (iterationLimit < 1) invalid("iterationLimit must be at least 1")
        customNameTooLong("network name", networkName).foreach(invalid)
        
        while (offset % 4 != 0) offset += 1
        
        // Agent section
        need(4, "agent count")
        val numberOfAgents = bytesToInt(data, offset)
        offset += 4
        if (numberOfAgents < 1) invalid("The payload must define at least one agent")
        // 3 floats + strategy + effect + name length per agent, checked before allocating.
        need(15L * numberOfAgents, "agent section")
        
        val initialBeliefs = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val toleranceRadius = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val toleranceOffset = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val silenceStrategies = data.slice(offset, offset + numberOfAgents).asInstanceOf[Array[SilenceStrategy]]
        offset += numberOfAgents
        
        val silenceEffects = data.slice(offset, offset + numberOfAgents).asInstanceOf[Array[SilenceEffect]]
        offset += numberOfAgents

        if (silenceStrategies.exists(s => !isSilenceStrategy(s.toInt)))
            invalid("The payload contains an unknown silenceStrategy")
        if (silenceEffects.exists(e => !isSilenceEffect(e.toInt)))
            invalid("The payload contains an unknown silenceEffect")
        
        // Read the agent names
        val agentNames = new Array[String](numberOfAgents)
        val agentIndexes = new mutable.HashMap[String, Int]()
        for (i <- 0 until numberOfAgents) {
            agentNames(i) = readString("agent names")
            customNameTooLong(s"agents[$i].name", agentNames(i)).foreach(invalid)
            agentIndexes.put(agentNames(i), i)
        }
        duplicateAgentName(agentNames).foreach(invalid)
        
        // Align to 4 bytes
        while (offset % 4 != 0) offset += 1
        
        // Neighbor section
        need(4, "edge count")
        val numberOfNeighbors = bytesToInt(data, offset)
        
        offset += 4
        if (numberOfNeighbors < 0) invalid("The edge count cannot be negative")
        // influence + bias + source/target name lengths per edge, checked before allocating.
        need(7L * numberOfNeighbors, "edge section")
        
        val influences = byteArrayToFloatArray(data, offset, numberOfNeighbors)
        offset += 4 * numberOfNeighbors
        
        val biases = data.slice(offset, offset + numberOfNeighbors).asInstanceOf[Array[Bias]]
        offset += numberOfNeighbors
        if (biases.exists(b => !isBias(b.toByte)))
            invalid("The payload contains an unknown bias")

        def agentIndex(name: String, role: String): Int =
            agentIndexes.getOrElse(name, invalid(s"Edge $role '$name' is not a defined agent"))
        
        val source = new Array[Int](numberOfNeighbors)
        for (i <- 0 until numberOfNeighbors) {
            source(i) = agentIndex(readString("edge sources"), "source")
        }
        
        val target = new Array[Int](numberOfNeighbors)
        for (i <- 0 until numberOfNeighbors) {
            target(i) = agentIndex(readString("edge targets"), "target")
        }
        // Agent names are unique (checked above), so equal index pairs are equal name pairs.
        duplicateEdge((0 until numberOfNeighbors).iterator.map(i => (agentNames(source(i)), agentNames(target(i)))))
            .foreach(invalid)

        // Optional data
        val majorityThreshold: mutable.Map[Int, Float] = mutable.Map()
        val confidences: mutable.Map[Int, Float] = mutable.Map()
        while (offset < data.length) {
            need(12, "optional agent data")
            val possibleConfidence = bytesToFloat(data, offset + 8)
            possibleConfidence match {
                case 2.0 =>
                    majorityThreshold(bytesToInt(data, offset)) = bytesToFloat(data, offset + 4)
                case _ =>
                    majorityThreshold(bytesToInt(data, offset)) = bytesToFloat(data, offset + 4)
                    confidences(bytesToInt(data, offset)) = possibleConfidence
            }
            offset += 12
        }
        
        // Preparing data: an edge `source -> target` means the target listens to the source, so the
        // simulator's CSR is grouped by TARGET and its neighbor references are the sources
        // (see DecodedCustomRun / CustomRunInfo).
        val sortedIndices = target.indices.sortBy(target(_))

        val sortedInfluences = sortedIndices.map(influences(_)).toArray
        val sortedBiases = sortedIndices.map(biases(_)).toArray
        val sortedSource = sortedIndices.map(source(_)).toArray
        val sortedTarget = sortedIndices.map(target(_)).toArray

        val indexOffset = buildEndOffsets(sortedTarget, numberOfAgents)

        DecodedCustomRun(
            networkName       = networkName,
            stopThreshold     = stopThreshold,
            iterationLimit    = iterationLimit,
            saveMode          = saveMode,
            agentNames        = agentNames,
            initialBeliefs    = initialBeliefs,
            toleranceRadius   = toleranceRadius,
            toleranceOffset   = toleranceOffset,
            silenceStrategies = silenceStrategies,
            silenceEffects    = silenceEffects,
            majorityThreshold = if (majorityThreshold.isEmpty) None else Some(majorityThreshold),
            confidences       = if (confidences.isEmpty) None else Some(confidences),
            indexOffset       = indexOffset,
            sortedSource      = sortedSource,
            sortedTarget      = sortedTarget,
            sortedInfluences  = sortedInfluences,
            sortedBiases      = sortedBiases
        )
    }

    /**
     * Persists and starts a custom run. Single save-mode gate for the JSON and binary variants
     * (#122): the legacy DB is only used when APP_MODE.usesLegacyDB; otherwise (unless the DB is
     * skipped) a `custom_runs` row carrying the caller's user_id is written, so the run shows up in
     * /simulations/mine and passes the owner-only checks.
     * If that row cannot be written the run is NOT started: Left(500 internal_error) instead of a
     * run without its owner row.
     */
    private def startCustomRun(
        run: DecodedCustomRun,
        userId: Option[Int] = None,
        persistFrames: Boolean = false,
        frameRetention: String = "ephemeral"
    ): Either[HttpResponse, Long] = {
        var runID = SnowflakeID.generateId()
        // Save mode forced to DEBUG BY DESIGN in this phase (#133), same decision as in
        // executeGeneratedRun: while APP_LEGACY_DB=false the requested saveMode (JSON body, or
        // byte 8 of the binary payload) is ignored and no per-agent data is written; only the
        // run's configuration (`custom_runs` with its agents and edges) and, with persistFrames,
        // the replay frames are stored. To revisit, change this condition here AND in
        // executeGeneratedRun (see the notes there).
        val convertedSaveMode = if (GlobalState.APP_MODE.skipDatabase || !GlobalState.APP_MODE.usesLegacyDB)
                                    SaveModes.DEBUG
                                else SaveModes.codeToSaveMode(run.saveMode)

        if (GlobalState.APP_MODE.usesLegacyDB && SaveModes.savesToDB(convertedSaveMode)) {
            runID = DatabaseManager.createRun(
                RunMode.GENERATED, run.saveMode, 1, None, None,
                run.stopThreshold, run.iterationLimit, "uniform"
            ).get
        } else if (!GlobalState.APP_MODE.skipDatabase) {
            // saveCustomRun logs and rolls back its own SQL errors; this also covers failing to get
            // a connection at all.
            val saved = try DatabaseManager.saveCustomRun(
                id                = runID,
                iterationLimit    = run.iterationLimit,
                stopThreshold     = run.stopThreshold,
                runName           = run.networkName,
                customAgentsData  = CustomAgentsData(
                    run.initialBeliefs,
                    run.toleranceRadius,
                    run.toleranceOffset,
                    run.silenceStrategies,
                    run.silenceEffects,
                    run.agentNames,
                    run.majorityThreshold,
                    run.confidences
                ),
                // The user's edges as sent (source influences target); only the row order differs.
                customNeighborsData = CustomNeighborsData(
                    run.sortedSource, run.sortedTarget, run.sortedInfluences, run.sortedBiases),
                userId = userId,
                frameRetention = frameRetention
            ) catch {
                case NonFatal(e) =>
                    Logger.logError(s"Error saving custom run $runID", e)
                    false
            }
            if (!saved)
                return Left(HttpResponse(StatusCodes.InternalServerError, entity = errorJson(
                    "internal_error", "The custom run could not be saved, so it was not started")))
        }

        SimulationCache.registerRun(runID, persistent = frameRetention == "persistent")
        monitor.get ! RunCustomNetwork(CustomRunInfo(
            runID                = runID,
            stopThreshold        = run.stopThreshold,
            iterationLimit       = run.iterationLimit,
            saveMode             = convertedSaveMode,
            networkName          = run.networkName,
            agentBeliefs         = run.initialBeliefs,
            agentToleranceRadii  = run.toleranceRadius,
            agentToleranceOffsets= run.toleranceOffset,
            agentSilenceStrategy = run.silenceStrategies,
            agentSilenceEffect   = run.silenceEffects,
            agentNames           = run.agentNames,
            indexOffset          = run.indexOffset,
            // CSR neighbor references = the agents each listener updates from = the edge SOURCES
            // (the field is named `target` for historical reasons; see CustomRunInfo).
            target               = run.sortedSource,
            influences           = run.sortedInfluences,
            bias                 = run.sortedBiases,
            persistFrames        = persistFrames
        ))

        Right(runID)
    }

    // Expects a request already accepted by validateCustomRunReq (edge names resolve).
    // Left(500) when the run could not be saved (see startCustomRun).
    private def executeCustomRunFromJson(
        req: CustomRunReq,
        userId: Option[Int],
        persistFrames: Boolean = false,
        frameRetention: String = "ephemeral"
    ): Either[HttpResponse, Long] = {
        val agentIndexes = req.agents.zipWithIndex.map { case (a, i) => a.name -> i }.toMap
        val agentNames         = req.agents.map(_.name).toArray
        val initialBeliefs     = req.agents.map(_.belief).toArray
        val toleranceRadius    = req.agents.map(_.toleranceRadius).toArray
        val toleranceOffset    = req.agents.map(_.toleranceOffset).toArray
        val silenceStrategies  = req.agents.map(a => SilenceStrategies.fromByte(a.silenceStrategy.toByte)).toArray
        val silenceEffects     = req.agents.map(a => SilenceEffects.fromByte(a.silenceEffect.toByte)).toArray

        val srcArr  = req.edges.map(e => agentIndexes(e.source)).toArray
        val tgtArr  = req.edges.map(e => agentIndexes(e.target)).toArray
        val infArr  = req.edges.map(_.influence).toArray
        val biasArr = req.edges.map(e => CognitiveBiases.fromByte(e.bias.toByte)).toArray

        // Same layout as the binary variant: grouped by TARGET (the listening agent), the sources
        // are the neighbor references (see DecodedCustomRun / CustomRunInfo).
        val sortedIdx    = tgtArr.indices.sortBy(tgtArr(_))
        val sortedSrc    = sortedIdx.map(srcArr(_)).toArray
        val sortedTgt    = sortedIdx.map(tgtArr(_)).toArray
        val sortedInf    = sortedIdx.map(infArr(_)).toArray
        val sortedBiases = sortedIdx.map(biasArr(_)).toArray

        val indexOffset = buildEndOffsets(sortedTgt, req.agents.size)

        startCustomRun(
            DecodedCustomRun(
                networkName       = req.name,
                stopThreshold     = req.stopThreshold,
                iterationLimit    = req.iterationLimit,
                saveMode          = req.saveMode.toByte,
                agentNames        = agentNames,
                initialBeliefs    = initialBeliefs,
                toleranceRadius   = toleranceRadius,
                toleranceOffset   = toleranceOffset,
                silenceStrategies = silenceStrategies,
                silenceEffects    = silenceEffects,
                majorityThreshold = None,
                confidences       = None,
                indexOffset       = indexOffset,
                sortedSource      = sortedSrc,
                sortedTarget      = sortedTgt,
                sortedInfluences  = sortedInf,
                sortedBiases      = sortedBiases
            ),
            userId, persistFrames, frameRetention
        )
    }

    // Utility methods for data transformation
    private def bytesToInt(bytes: Array[Byte], offset: Int): Int = {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }
    
    private def bytesToFloat(bytes: Array[Byte], offset: Int): Float = {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat()
    }
    
    private def bytesToLong(bytes: Array[Byte], offset: Int): Long = {
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
    }
    
    private def byteArrayToFloatArray(source: Array[Byte], offset: Int, length: Int): Array[Float] = {
        val dest = new Array[Float](length)
        ByteBuffer.wrap(source, offset, length * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(dest)
        dest
    }
    
    private def byteArrayToIntArray(source: Array[Byte], offset: Int, length: Int): Array[Int] = {
        val dest = new Array[Int](length)
        ByteBuffer.wrap(source, offset, length * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(dest)
        dest
    }
    
    /*
     * Converts a byte string representation in UTF-8 encoding to String
     */
    private def byteArrToString(bytes: Array[Byte], start: Int, length: Int): String = {
        new String(bytes, start, length, "UTF-8")
    }
    
    // Logging section methods only for server debug purposes
    private def logBufferDebugInfo(buffer: ByteBuffer): Unit = {
        val originalPosition = buffer.position()
        val originalLimit = buffer.limit()
        
        Logger.logServer("--- SERVER SEND DEBUG ---")
        Logger.logServer(s"Buffer state: position=$originalPosition, limit=$originalLimit, remaining=${buffer.remaining()}")
        
        // Read header for validation
        buffer.rewind()
        val networkIdMSB = buffer.getLong()
        val networkIdLSB = buffer.getLong()
        val runId = buffer.getInt()
        val numberOfAgents = buffer.getInt()
        val round = buffer.getInt()
        val indexReference = buffer.getInt()
        
        Logger.logServer(s"Header: runId=$runId, agents=$numberOfAgents, round=$round, indexRef=$indexReference")
        
        // Restore original buffer state
        buffer.position(originalPosition)
        buffer.limit(originalLimit)
    }
}