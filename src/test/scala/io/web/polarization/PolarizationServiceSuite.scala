package io.web.polarization

import spray.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/**
 * #129: PolarizationService's process lifecycle against a FAKE worker (a POSIX shell script speaking
 * the same JSON-lines protocol; the builder image has no Python): lazy start, handshake, reuse,
 * timeout / crash / malformed output → kill, clear 503 message and one restart, stderr drained,
 * bounded queue, cached catalog, shutdown.
 */
class PolarizationServiceSuite extends munit.FunSuite {
    import PolarizationFailure.*

    /**
     * The fake answers by the measure named in the request: hang (no answer), crash (exit 3), garbage
     * (not JSON), wrongid (answer to id 0), reject (ok: false), noisy (~200 KB of stderr first),
     * short (no values), slow (1 s), anything else → one value. Results carry the shell's pid.
     */
    private val fakeWorker: String =
        """#!/bin/sh
          |while IFS= read -r line; do
          |  id=$(printf '%s\n' "$line" | sed -n 's/^{"id":\([0-9]*\).*/\1/p')
          |  case "$line" in
          |    *'"op":"version"'*) printf '{"id":%s,"ok":true,"result":{"protocol":1,"package":{"name":"measures","version":"fake","commit":"abc"},"numpy":"n","scipy":"s","python":"p"}}\n' "$id" ;;
          |    *'"op":"catalog"'*) printf '{"id":%s,"ok":true,"result":{"measures":[],"limits":{"maxRounds":3},"pid":%s}}\n' "$id" "$$" ;;
          |    *'"measure":"hang"'*) exec sleep 600 ;;
          |    *'"measure":"crash"'*) exit 3 ;;
          |    *'"measure":"garbage"'*) echo 'this is not json' ;;
          |    *'"measure":"wrongid"'*) printf '{"id":0,"ok":true,"result":{"values":[1],"levels":[null]}}\n' ;;
          |    *'"measure":"reject"'*) printf '{"id":%s,"ok":false,"error":{"code":"invalid_measure","message":"Unknown measure","fields":[{"field":"measure","issue":"unknown"}]}}\n' "$id" ;;
          |    *'"measure":"noisy"'*)
          |      i=0
          |      while [ $i -lt 2000 ]; do echo "warning $i: ......................................................................................" >&2; i=$((i+1)); done
          |      printf '{"id":%s,"ok":true,"result":{"values":[1],"levels":[null],"pid":%s}}\n' "$id" "$$" ;;
          |    *'"measure":"short"'*) printf '{"id":%s,"ok":true,"result":{"values":[],"levels":[]}}\n' "$id" ;;
          |    *'"measure":"slow"'*) sleep 1; printf '{"id":%s,"ok":true,"result":{"values":[1],"levels":[null],"pid":%s}}\n' "$id" "$$" ;;
          |    *) printf '{"id":%s,"ok":true,"result":{"values":[0.5],"levels":["low"],"pid":%s}}\n' "$id" "$$" ;;
          |  esac
          |done
          |""".stripMargin

    private val script: Path = {
        val file = Files.createTempFile("fake-polarization-worker", ".sh")
        Files.writeString(file, fakeWorker, UTF_8)
        file.toFile.deleteOnExit()
        file
    }

    private val services = scala.collection.mutable.ListBuffer.empty[PolarizationService]

    /** stderr lines of the fake workers (drained by the service instead of going to the test log). */
    private val stderrLines = new java.util.concurrent.atomic.AtomicInteger(0)

    private def service(timeout: FiniteDuration = 5.seconds, maxQueued: Int = 8,
                        command: Seq[String] = Seq("/bin/sh", script.toString)): PolarizationService = {
        val created = new PolarizationService(command, timeout, maxQueued, (_, _) => stderrLines.incrementAndGet())
        services += created
        created
    }

    override def afterEach(context: AfterEach): Unit = {
        services.foreach(_.close())
        services.clear()
    }

    private def await[A](future: Future[A]): A = Await.result(future, 30.seconds)

    private def request(measure: String): PolarizationRequest =
        PolarizationRequest(measure, JsObject.empty, 3, Array(Array(1.0, 0.0, 1.0)))

    private def compute(svc: PolarizationService, measure: String) = await(svc.compute(request(measure)))

    private def pidOf(result: Either[PolarizationFailure, JsObject]): Long = result match {
        case Right(obj) => obj.fields("pid") match {
            case JsNumber(n) => n.toLong
            case other       => fail(s"pid: $other")
        }
        case Left(failure) => fail(s"failed: $failure")
    }

    private def unavailable(result: Either[PolarizationFailure, JsObject]): String = result match {
        case Left(Unavailable(message)) => message
        case other                      => fail(s"expected Unavailable, got $other")
    }

    test("lazy: no process until the first request; then ONE process is reused") {
        val svc = service()
        assertEquals(svc.processesStarted, 0)
        assert(!svc.workerAlive)
        val first = pidOf(compute(svc, "ok"))
        val second = pidOf(compute(svc, "ok"))
        assertEquals(first, second)
        assertEquals(svc.processesStarted, 1)
        assertEquals(svc.workerPid, Some(first))
        assertEquals(compute(svc, "ok").map(_.fields("levels")), Right(JsArray(JsString("low"))))
    }

    test("timeout: the request fails with a clear message, the process is killed and restarted once") {
        val svc = service(timeout = 1.second)
        val before = pidOf(compute(svc, "ok"))
        val message = unavailable(compute(svc, "hang"))
        assert(message.contains("did not answer within 1 s"), message)
        assert(message.contains("retry"), message)
        val after = pidOf(compute(svc, "ok"))
        assertNotEquals(after, before)
        assertEquals(svc.processesStarted, 2, "restarted once after the failure; that process serves the next request")
    }

    test("crash: exit during a request → 503 message with the exit code; the next request works") {
        val svc = service()
        val before = pidOf(compute(svc, "ok"))
        val message = unavailable(compute(svc, "crash"))
        assert(message.contains("exited unexpectedly"), message)
        assert(message.contains("3"), message)
        assertNotEquals(pidOf(compute(svc, "ok")), before)
    }

    test("malformed output and answers to another id are protocol failures (kill + restart)") {
        val svc = service()
        val before = pidOf(compute(svc, "ok"))
        assert(unavailable(compute(svc, "garbage")).contains("malformed output (not JSON)"))
        val middle = pidOf(compute(svc, "ok"))
        assertNotEquals(middle, before)
        assert(unavailable(compute(svc, "wrongid")).contains("answer to id 0"))
        assertNotEquals(pidOf(compute(svc, "ok")), middle)
        assertEquals(svc.processesStarted, 3)
    }

    test("a result whose values do not match the histograms is not passed on") {
        val svc = service()
        assert(unavailable(compute(svc, "short")).contains("0 values for 1 histograms"))
    }

    test("ok: false answers become Rejected with the worker's code, message and fields (no restart)") {
        val svc = service()
        val pid = pidOf(compute(svc, "ok"))
        compute(svc, "reject") match {
            case Left(Rejected(code, message, fields)) =>
                assertEquals(code, "invalid_measure")
                assertEquals(message, "Unknown measure")
                assertEquals(fields.map(_.field), List("measure"))
            case other => fail(s"expected Rejected, got $other")
        }
        assertEquals(pidOf(compute(svc, "ok")), pid)
    }

    test("stderr is drained: a worker writing ~200 KB (2000 lines) to stderr before answering does not block") {
        val svc = service(timeout = 10.seconds)
        val before = stderrLines.get()
        assert(compute(svc, "noisy").isRight)
        val deadline = System.nanoTime() + 5.seconds.toNanos
        while (stderrLines.get() - before < 2000 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(stderrLines.get() - before, 2000)
    }

    test("the worker cannot start: missing interpreter, or it exits before the handshake") {
        val missing = service(command = Seq("/nonexistent/python3", "-I", "/nonexistent/worker.py"))
        assert(unavailable(compute(missing, "ok")).contains("could not be started"))
        val exits = service(command = Seq("/bin/sh", "-c", "exit 4"))
        val message = unavailable(await(exits.catalog()))
        assert(message.contains("could not be started") && message.contains("exit code 4"), message)
        // Each request tries one start: no restart loop.
        assertEquals(exits.processesStarted, 1)
        unavailable(await(exits.catalog()))
        assertEquals(exits.processesStarted, 2)
    }

    test("catalog: cached after the first success (served even after the worker is replaced), HTTP limits added") {
        val svc = service()
        val first = await(svc.catalog()).getOrElse(fail("catalog failed"))
        unavailable(compute(svc, "crash")) // a new process with another pid
        val second = await(svc.catalog()).getOrElse(fail("catalog failed"))
        assertEquals(second, first)
        assertEquals(first.fields("limits").asJsObject.fields("maxRounds"), JsNumber(PolarizationRequest.MaxRounds))
    }

    test("requests are serialized; beyond maxQueued waiting ones the answer is 503 busy") {
        val svc = service(maxQueued = 1)
        pidOf(compute(svc, "ok")) // started
        val slow = svc.compute(request("slow"))
        Thread.sleep(200) // the slow one is in flight, the queue is empty again
        val queued = svc.compute(request("ok"))
        val rejected = await(svc.compute(request("ok")))
        assert(unavailable(rejected).contains("busy"))
        assert(await(slow).isRight)
        assert(await(queued).isRight)
    }

    test("close destroys the worker; later requests answer 503 without starting Python") {
        val svc = service()
        pidOf(compute(svc, "ok"))
        assert(svc.workerAlive)
        svc.close()
        assert(!svc.workerAlive)
        assert(unavailable(compute(svc, "ok")).contains("shutting down"))
        assertEquals(svc.processesStarted, 1)
        svc.close() // idempotent
    }
}
