package io.web.polarization

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import io.web.auth.FieldIssue
import spray.json.*
import utils.logging.Logger

import java.io.{BufferedReader, BufferedWriter, IOException, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, RejectedExecutionException, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Why the polarization worker produced no result. */
sealed trait PolarizationFailure {
    def message: String
}

object PolarizationFailure {

    /** No answer: not started, crashed, timed out, malformed output, busy or shutting down (503). */
    final case class Unavailable(message: String) extends PolarizationFailure

    /** The worker answered `ok: false` (`invalid_measure` → 422, `invalid_request` → 400, else 500). */
    final case class Rejected(code: String, message: String, fields: List[FieldIssue]) extends PolarizationFailure
}

/** What the routes need (PolarizationService, or a stub in the route tests). */
trait PolarizationBackend {
    /** The worker's catalog (cached after the first success), with the HTTP limits. */
    def catalog(): Future[Either[PolarizationFailure, JsObject]]

    /** One value (and level / error) per histogram of the request, in order. */
    def compute(request: PolarizationRequest): Future[Either[PolarizationFailure, JsObject]]
}

/**
 * The pol_measures worker (#129): ONE `python3 -I worker.py` process, started lazily by the first
 * request and reused (JSON lines over its stdin / stdout, see polarization/worker.py).
 *
 *  - Requests are serialized: one dedicated thread writes a request and waits for its answer, so
 *    a single request is in flight; at most `maxQueued` wait (more → 503 busy).
 *  - Each answer must arrive within `timeout`. On a timeout, a crash (EOF / broken pipe), a
 *    malformed answer or an answer to another request id, the process is killed, that request
 *    fails with a clear 503 message, and the worker is restarted once right away (if that restart
 *    fails, the next request tries again).
 *  - The worker's stderr (the package's warnings, tracebacks) is drained line by line into the
 *    server log, so it can never fill the pipe and block the worker.
 *  - [[close]] (CoordinatedShutdown) destroys the process; if the JVM dies first, the worker exits
 *    on its own when its stdin closes.
 */
final class PolarizationService(command: Seq[String], timeout: FiniteDuration,
                                maxQueued: Int = PolarizationService.DefaultMaxQueued,
                                stderrLine: (Long, String) => Unit = PolarizationService.logStderrLine)
    extends PolarizationBackend with AutoCloseable {

    import PolarizationFailure.*
    import PolarizationService.*

    private val executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue[Runnable](), new ThreadFactory {
            def newThread(task: Runnable): Thread = {
                val thread = new Thread(task, "polarization-worker-client")
                thread.setDaemon(true)
                thread
            }
        })
    private val queued = new AtomicInteger(0)
    private val cachedCatalog = new AtomicReference[JsObject](null)
    @volatile private var closed = false

    // Worker-thread state (only the executor's thread touches it, except close()).
    @volatile private var worker: WorkerProcess = null
    @volatile private var started = 0
    private var nextId = 0L
    private var restartPending = false

    // ── API ──────────────────────────────────────────────────────────────────────────────────

    def catalog(): Future[Either[PolarizationFailure, JsObject]] = {
        val hit = cachedCatalog.get()
        if (hit != null) Future.successful(Right(hit))
        else submit(id => s"""{"id":$id,"op":"catalog"}""") { result =>
            val withLimits = withHttpLimits(result)
            cachedCatalog.compareAndSet(null, withLimits)
            Right(cachedCatalog.get())
        }
    }

    def compute(request: PolarizationRequest): Future[Either[PolarizationFailure, JsObject]] =
        submit(request.workerMessage) { result =>
            def rounds(name: String): Option[Int] = result.fields.get(name).collect { case JsArray(items) => items.size }
            if (rounds("values").contains(request.rounds) && rounds("levels").contains(request.rounds)) Right(result)
            else Left(Unavailable(s"The polarization worker answered ${rounds("values").getOrElse(0)} values " +
                s"for ${request.rounds} histograms; the request was not computed"))
        }

    /** Destroys the worker; later requests answer 503. Idempotent. */
    def close(): Unit = {
        closed = true
        executor.shutdownNow().asScala.foreach {
            case job: Job => job.abort()
            case _        => ()
        }
        stopWorker()
    }

    // ── For the tests ────────────────────────────────────────────────────────────────────────
    private[polarization] def processesStarted: Int = started
    private[polarization] def workerPid: Option[Long] = Option(worker).filter(_.isAlive).map(_.pid)
    private[polarization] def workerAlive: Boolean = Option(worker).exists(_.isAlive)

    // ── Queue ────────────────────────────────────────────────────────────────────────────────

    /** One request; `onResult` turns the worker's `result` into the answer (on the worker thread). */
    private final class Job(render: Long => String, onResult: JsObject => Either[PolarizationFailure, JsObject])
        extends Runnable {
        val promise: Promise[Either[PolarizationFailure, JsObject]] = Promise()

        def run(): Unit = {
            queued.decrementAndGet()
            val answer =
                try exchange(render).flatMap(onResult)
                catch { case NonFatal(e) =>
                    Logger.logError("Polarization request failed unexpectedly", e)
                    Left(Unavailable("The polarization worker failed unexpectedly; retry later"))
                }
            promise.trySuccess(answer)
            if (restartPending && !closed) {
                restartPending = false
                start() match {
                    case Left(failure) if !closed => Logger.logWarning(s"Polarization worker restart failed: ${failure.message}")
                    case _                        => ()
                }
            }
        }

        def abort(): Unit = promise.trySuccess(Left(Unavailable(ShuttingDown)))
    }

    private def submit(render: Long => String)(onResult: JsObject => Either[PolarizationFailure, JsObject])
        : Future[Either[PolarizationFailure, JsObject]] = {
        if (closed) return Future.successful(Left(Unavailable(ShuttingDown)))
        if (queued.incrementAndGet() > maxQueued) {
            queued.decrementAndGet()
            return Future.successful(Left(Unavailable(
                s"The polarization worker is busy ($maxQueued requests waiting); retry in a few seconds")))
        }
        val job = new Job(render, onResult)
        try executor.execute(job)
        catch { case _: RejectedExecutionException =>
            queued.decrementAndGet()
            job.abort()
        }
        job.promise.future
    }

    // ── Worker process ───────────────────────────────────────────────────────────────────────

    private def exchange(render: Long => String): Either[PolarizationFailure, JsObject] = {
        if (closed) return Left(Unavailable(ShuttingDown))
        val current = if (worker != null && worker.isAlive) Right(worker) else { stopWorker(); start() }
        current.flatMap { process =>
            nextId += 1
            val id = nextId
            process.roundTrip(render(id), timeout) match {
                case WorkerProcess.Answer(line) => decode(line, id) match {
                    case Right(answer)  => answer
                    case Left(problem) => fail(s"answered malformed output ($problem)")
                }
                case WorkerProcess.TimedOut if closed => stopWorker(); Left(Unavailable(ShuttingDown))
                case WorkerProcess.TimedOut     => fail(s"did not answer within ${timeout.toSeconds} s")
                case WorkerProcess.Exited(code) => fail(s"exited unexpectedly (exit code $code)")
            }
        }
    }

    /** Kills the worker after a failed exchange; the restart happens once the caller has its answer. */
    private def fail(reason: String): Left[PolarizationFailure, Nothing] = {
        Logger.logWarning(s"Polarization worker $reason: killed, restarting it")
        stopWorker()
        restartPending = true
        Left(Unavailable(s"The polarization worker $reason; this request was not computed (the worker is being restarted, retry)"))
    }

    /** Starts a worker and checks it answers `version` (the handshake). */
    private def start(): Either[PolarizationFailure, WorkerProcess] = {
        if (closed) return Left(Unavailable(ShuttingDown))
        val process =
            try WorkerProcess.start(command, stderrLine)
            catch { case e: IOException =>
                Logger.logError(s"Polarization worker could not be started (${command.mkString(" ")})", e)
                return Left(Unavailable(s"The polarization worker could not be started: ${e.getMessage}"))
            }
        started += 1
        nextId += 1
        val id = nextId
        val handshake = process.roundTrip(s"""{"id":$id,"op":"version"}""", timeout) match {
            case WorkerProcess.Answer(line) => decode(line, id) match {
                case Right(Right(version)) => Right(version)
                case Right(Left(failure))  => Left(s"answered an error to the handshake: ${failure.message}")
                case Left(problem)        => Left(s"answered malformed output to the handshake ($problem)")
            }
            case WorkerProcess.TimedOut     => Left(s"did not start within ${timeout.toSeconds} s")
            case WorkerProcess.Exited(code) => Left(s"exited during startup (exit code $code)")
        }
        handshake match {
            case Left(_) if closed =>
                process.destroy()
                Left(Unavailable(ShuttingDown))
            case Right(version) =>
                worker = process
                Logger.log(s"Polarization worker started (pid ${process.pid}): ${describe(version)}")
                Right(process)
            case Left(problem) =>
                process.destroy()
                Logger.logWarning(s"Polarization worker (pid ${process.pid}) $problem")
                Left(Unavailable(s"The polarization worker could not be started: it $problem"))
        }
    }

    private def stopWorker(): Unit = {
        val current = worker
        worker = null
        if (current != null) current.destroy()
    }
}

object PolarizationService {
    final val DefaultMaxQueued = 8
    private final val ShuttingDown = "The server is shutting down; polarization measures are unavailable"

    def apply(settings: PolarizationSettings): PolarizationService =
        new PolarizationService(settings.command, settings.timeout)

    /** Default sink of the worker's stderr: the server log, one warning per line. */
    def logStderrLine(pid: Long, line: String): Unit = Logger.logWarning(s"polarization worker [$pid]: $line")

    /** `limits` as the HTTP endpoint applies them (worker values overridden by the route's). */
    private[polarization] def withHttpLimits(catalog: JsObject): JsObject = {
        val workerLimits = catalog.fields.get("limits") match {
            case Some(JsObject(members)) => members
            case _                       => Map.empty[String, JsValue]
        }
        val limits = workerLimits ++ Map(
            "minCategories" -> JsNumber(PolarizationRequest.MinCategories),
            "maxCategories" -> JsNumber(PolarizationRequest.MaxCategories),
            "maxRounds"     -> JsNumber(PolarizationRequest.MaxRounds),
            "maxBodyBytes"  -> JsNumber(PolarizationRequest.MaxBodyBytes)
        )
        JsObject(catalog.fields.updated("limits", JsObject(limits)))
    }

    /** `{"id": id, "ok": true, "result": {...}}` → Right(Right(result)); `ok: false` → Right(Left(Rejected)). */
    private[polarization] def decode(line: String, id: Long): Either[String, Either[PolarizationFailure, JsObject]] = {
        val parsed =
            try JsonParser(line)
            catch { case NonFatal(_) => return Left("not JSON") }
        parsed match {
            case JsObject(fields) =>
                fields.get("id") match {
                    case Some(JsNumber(n)) if n == BigDecimal(id) =>
                        (fields.get("ok"), fields.get("result"), fields.get("error")) match {
                            case (Some(JsBoolean(true)), Some(result: JsObject), _) => Right(Right(result))
                            case (Some(JsBoolean(false)), _, Some(JsObject(error))) =>
                                (error.get("code"), error.get("message")) match {
                                    case (Some(JsString(code)), Some(JsString(message))) =>
                                        Right(Left(PolarizationFailure.Rejected(code, message, fieldIssues(error.get("fields")))))
                                    case _ => Left("error without code / message")
                                }
                            case _ => Left("neither a result nor an error")
                        }
                    case other => Left(s"answer to id ${other.map(_.compactPrint).getOrElse("none")}, expected $id")
                }
            case _ => Left("not a JSON object")
        }
    }

    private def fieldIssues(value: Option[JsValue]): List[FieldIssue] = value match {
        case Some(JsArray(items)) => items.toList.collect {
            case JsObject(f) => (f.get("field"), f.get("issue")) match {
                case (Some(JsString(field)), Some(JsString(issue))) => Some(FieldIssue(field, issue))
                case _ => None
            }
        }.flatten
        case _ => Nil
    }

    private def describe(version: JsObject): String = {
        def text(value: Option[JsValue]): String = value match {
            case Some(JsString(s)) => s
            case Some(other)       => other.compactPrint
            case None              => "?"
        }
        val pkg = version.fields.get("package") match {
            case Some(JsObject(p)) => s"measures ${text(p.get("version"))} @ ${text(p.get("commit"))}"
            case _                 => "measures ?"
        }
        s"$pkg, numpy ${text(version.fields.get("numpy"))}, scipy ${text(version.fields.get("scipy"))}, " +
            s"Python ${text(version.fields.get("python"))}"
    }
}

/** One worker process: stdin writer, a stdout reader thread (lines → queue), a stderr drain. */
private[polarization] final class WorkerProcess private (process: Process, stderrLine: (Long, String) => Unit) {
    import WorkerProcess.*

    private val lines = new LinkedBlockingQueue[Option[String]]()
    private val writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream, UTF_8))

    val pid: Long = process.pid()

    daemon(s"polarization-worker-$pid-stdout") {
        val reader = new BufferedReader(new InputStreamReader(process.getInputStream, UTF_8))
        try {
            var line = reader.readLine()
            while (line != null) {
                lines.put(Some(line))
                line = reader.readLine()
            }
        } catch { case _: IOException => () }
        finally lines.put(None)
    }

    daemon(s"polarization-worker-$pid-stderr") {
        val reader = new BufferedReader(new InputStreamReader(process.getErrorStream, UTF_8))
        try {
            var line = reader.readLine()
            while (line != null) {
                stderrLine(pid, line)
                line = reader.readLine()
            }
        } catch { case _: IOException => () }
    }

    def isAlive: Boolean = process.isAlive

    /** Writes one request line and waits for the next answer line. */
    def roundTrip(line: String, timeout: FiniteDuration): Outcome = {
        try {
            writer.write(line)
            writer.write('\n')
            writer.flush()
        } catch { case _: IOException => return Exited(exitCode()) }
        val next =
            try lines.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
            catch { case _: InterruptedException => null }
        next match {
            case null       => TimedOut
            case Some(text) => Answer(text)
            case None       => Exited(exitCode())
        }
    }

    def destroy(): Unit = {
        try writer.close() catch { case _: IOException => () }
        process.destroyForcibly()
        try process.waitFor(5, TimeUnit.SECONDS) catch { case _: InterruptedException => () }
    }

    private def exitCode(): String =
        try if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue().toString else "none, still running"
        catch { case _: InterruptedException => "unknown" }
}

private[polarization] object WorkerProcess {
    sealed trait Outcome
    final case class Answer(line: String) extends Outcome
    case object TimedOut extends Outcome
    final case class Exited(code: String) extends Outcome

    /**
     * Starts `command` with a minimal environment: PATH and POL_MEASURES_REF only, so the worker
     * never sees the backend's secrets (database passwords, credentials paths).
     */
    def start(command: Seq[String], stderrLine: (Long, String) => Unit): WorkerProcess = {
        val builder = new ProcessBuilder(command.asJava)
        val environment = builder.environment()
        environment.clear()
        List("PATH", "POL_MEASURES_REF").foreach(name => sys.env.get(name).foreach(environment.put(name, _)))
        new WorkerProcess(builder.start(), stderrLine)
    }

    private def daemon(name: String)(body: => Unit): Unit = {
        val thread = new Thread(() => body, name)
        thread.setDaemon(true)
        thread.start()
    }
}
