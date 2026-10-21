package io.web.polarization

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import io.web.testing.JsonTestSupport.*
import spray.json.*

import scala.concurrent.duration.*

/**
 * #129: validation of `POST /simulations/polarization` bodies (400 `invalid_body` with the offending
 * member), the worker's `compute` line (spray-json escaping, positions, integral weights), the
 * catalog's HTTP limits and the settings' in-code defaults.
 */
class PolarizationRequestSuite extends munit.FunSuite {

    private def body(text: String): JsValue = JsonParser(text)

    private def issues(text: String): List[(String, String)] =
        PolarizationRequest.parse(body(text)) match {
            case Left((_, fields)) => fields.map(f => f.field -> f.issue)
            case Right(request)    => fail(s"accepted: $request")
        }

    private def fieldsOf(text: String): List[String] = issues(text).map(_._1)

    test("a valid body: measure, params as given (missing = {}), k and one row per histogram") {
        val parsed = PolarizationRequest.parse(body(
            """{"measure": "EstebanRay", "params": {"alpha": 0.8, "K": null, "alienation": "d^2"}, "k": 3,
              | "histograms": [[1, 0, 2], [0.25, 0.5, 0.25]]}""".stripMargin))
        val request = parsed.getOrElse(fail(s"rejected: $parsed"))
        assertEquals(request.measure, "EstebanRay")
        assertEquals(request.k, 3)
        assertEquals(request.rounds, 2)
        assertEquals(request.histograms.map(_.toVector).toVector, Vector(Vector(1.0, 0.0, 2.0), Vector(0.25, 0.5, 0.25)))
        assertEquals(request.params.fields.keySet, Set("alpha", "K", "alienation"))

        val noParams = PolarizationRequest.parse(body("""{"measure": "MEC", "k": 5, "histograms": [[1,1,1,1,1]]}"""))
        assertEquals(noParams.map(_.params), Right(JsObject.empty))
        val nullParams = PolarizationRequest.parse(body("""{"measure": "MEC", "params": null, "k": 5, "histograms": [[1,1,1,1,1]]}"""))
        assertEquals(nullParams.map(_.params), Right(JsObject.empty))
    }

    test("not an object, missing or mistyped members → every offending member named") {
        assertEquals(PolarizationRequest.parse(body("[1, 2]")).left.map(_._1), Left("The body must be a JSON object"))
        assertEquals(fieldsOf("{}"), List("measure", "k", "histograms"))
        assertEquals(fieldsOf("""{"measure": 3, "k": "5", "histograms": {}}"""), List("measure", "k", "histograms"))
        assertEquals(fieldsOf("""{"measure": "", "k": 5, "histograms": [[1,1,1,1,1]]}"""), List("measure"))
        assertEquals(fieldsOf(s"""{"measure": "${"x" * 65}", "k": 5, "histograms": [[1,1,1,1,1]]}"""), List("measure"))
        assertEquals(fieldsOf("""{"measure": "MEC", "params": [1], "k": 5, "histograms": [[1,1,1,1,1]]}"""), List("params"))
        assertEquals(fieldsOf("""{"measure": "MEC", "params": {"alpha": [1], "beta": {"x": 1}, "ok": true}, "k": 5, "histograms": [[1,1,1,1,1]]}"""),
            List("params.alpha", "params.beta", "params.ok"))
    }

    test("k must be an integer from 2 to 10") {
        List("1", "11", "0", "-3", "2.5", "1e9").foreach { k =>
            assertEquals(fieldsOf(s"""{"measure": "MEC", "k": $k, "histograms": [[1, 1]]}"""), List("k"), s"k = $k")
        }
        List(2, 10).foreach { k =>
            val row = List.fill(k)(1).mkString("[", ",", "]")
            assert(PolarizationRequest.parse(body(s"""{"measure": "MEC", "k": $k, "histograms": [$row]}""")).isRight, s"k = $k")
        }
    }

    test("histograms: at least one, at most MaxRounds, k weights each, finite and >= 0 (first offender named)") {
        assertEquals(fieldsOf("""{"measure": "MEC", "k": 3, "histograms": []}"""), List("histograms"))
        val tooMany = List.fill(PolarizationRequest.MaxRounds + 1)("[1,1,1]").mkString("[", ",", "]")
        assertEquals(issues(s"""{"measure": "MEC", "k": 3, "histograms": $tooMany}""").map(_._1), List("histograms"))
        val atLimit = List.fill(PolarizationRequest.MaxRounds)("[1,1,1]").mkString("[", ",", "]")
        assert(PolarizationRequest.parse(body(s"""{"measure": "MEC", "k": 3, "histograms": $atLimit}""")).isRight)

        assertEquals(issues("""{"measure": "MEC", "k": 3, "histograms": [[1,1,1], [1,1]]}"""),
            List("histograms[1]" -> "must have k = 3 weights (got 2)"))
        assertEquals(fieldsOf("""{"measure": "MEC", "k": 3, "histograms": [[1,1,1], 7]}"""), List("histograms[1]"))
        assertEquals(fieldsOf("""{"measure": "MEC", "k": 3, "histograms": [[1,1,1], [1,-0.5,1]]}"""), List("histograms[1][1]"))
        assertEquals(fieldsOf("""{"measure": "MEC", "k": 3, "histograms": [[1,"1",1]]}"""), List("histograms[0][1]"))
        assertEquals(fieldsOf("""{"measure": "MEC", "k": 3, "histograms": [[1,null,1]]}"""), List("histograms[0][1]"))
        assertEquals(fieldsOf("""{"measure": "MEC", "k": 3, "histograms": [[1,1,1e400]]}"""), List("histograms[0][2]"))
        // An invalid k reports k only: the rows cannot be checked against it.
        assertEquals(fieldsOf("""{"measure": "MEC", "k": 12, "histograms": [[1,1]]}"""), List("k"))
        // Zeros are fine here: an empty histogram is a per-round error of the package itself.
        assert(PolarizationRequest.parse(body("""{"measure": "MEC", "k": 3, "histograms": [[0,0,0]]}""")).isRight)
    }

    test("positions are j / (k - 1)") {
        assertEquals(PolarizationRequest.positions(5).toVector, Vector(0.0, 0.25, 0.5, 0.75, 1.0))
        assertEquals(PolarizationRequest.positions(2).toVector, Vector(0.0, 1.0))
        assertEquals(PolarizationRequest.positions(3)(1), 0.5)
    }

    test("worker line: one JSON object, measure / params escaped by spray-json, integral weights as integers") {
        val request = PolarizationRequest("MEC", JsObject("alpha" -> JsNumber(2), "alienation" -> JsString("d^2")), 3,
            Array(Array(3.0, 0.0, 1.0), Array(0.25, 0.5, 0.25), Array(1e-7, 2.5e15, 0.0)))
        val line = request.workerMessage(42)
        assert(!line.contains('\n'), line)
        assert(line.contains("[3,0,1]"), line)
        val parsed = parseAsClient(line)
        assertEquals(int(parsed, "id"), 42)
        assertEquals(str(parsed, "op"), "compute")
        assertEquals(str(parsed, "measure"), "MEC")
        assertEquals(field(parsed, "params"), JsObject("alpha" -> JsNumber(2), "alienation" -> JsString("d^2")))
        assertEquals(array(parsed, "x").map { case JsNumber(n) => n.toDouble; case other => fail(s"$other") },
            Vector(0.0, 0.5, 1.0))
        val rows = array(parsed, "histograms").map {
            case JsArray(values) => values.map { case JsNumber(n) => n.toDouble; case other => fail(s"$other") }
            case other           => fail(s"$other")
        }
        assertEquals(rows, Vector(Vector(3.0, 0.0, 1.0), Vector(0.25, 0.5, 0.25), Vector(1e-7, 2.5e15, 0.0)))
    }

    test("worker line: hostile measure ids and parameter names round-trip (they reach the worker verbatim)") {
        nastyStrings.foreach { nasty =>
            val request = PolarizationRequest(nasty, JsObject(nasty -> JsString(nasty)), 2, Array(Array(1.0, 1.0)))
            val parsed = parseAsClient(request.workerMessage(1))
            assertEquals(str(parsed, "measure"), nasty)
            assertEquals(field(parsed, "params"), JsObject(nasty -> JsString(nasty)))
        }
    }

    test("catalog limits are the HTTP endpoint's (the worker's other limit members are kept)") {
        val catalog = JsObject("measures" -> JsArray(), "limits" -> JsObject("maxRounds" -> JsNumber(5), "extra" -> JsTrue))
        val limits = field(PolarizationService.withHttpLimits(catalog), "limits")
        assertEquals(int(limits, "maxRounds"), PolarizationRequest.MaxRounds)
        assertEquals(int(limits, "minCategories"), 2)
        assertEquals(int(limits, "maxCategories"), 10)
        assertEquals(field(limits, "maxBodyBytes"), JsNumber(PolarizationRequest.MaxBodyBytes))
        assertEquals(field(limits, "extra"), JsTrue)
        assert(PolarizationService.withHttpLimits(JsObject("measures" -> JsArray())).fields.contains("limits"))
    }

    test("settings: enabled by default with the image's paths; only APP_POLARIZATION=false disables") {
        val defaults = PolarizationSettings.fromEnv(Map.empty)
        assert(defaults.enabled)
        assertEquals(defaults.command, Seq("/opt/polarization/venv/bin/python3", "-I", "/app/polarization/worker.py"))
        assertEquals(defaults.timeout, 30.seconds)
        assert(defaults.startupLine.contains("enabled"), defaults.startupLine)
        assert(defaults.startupLine.contains("POL_MEASURES_REF not set"), defaults.startupLine)

        List("false", "FALSE", " False ").foreach { value =>
            val off = PolarizationSettings.fromEnv(Map("APP_POLARIZATION" -> value))
            assert(!off.enabled, value)
            assert(off.startupLine.contains("DISABLED"), off.startupLine)
        }
        List("true", "", "0", "no", "yes").foreach { value =>
            assert(PolarizationSettings.fromEnv(Map("APP_POLARIZATION" -> value)).enabled, value)
        }

        val custom = PolarizationSettings.fromEnv(Map(
            "APP_POLARIZATION_PYTHON" -> "/usr/bin/python3", "APP_POLARIZATION_WORKER" -> "/tmp/w.py",
            "APP_POLARIZATION_TIMEOUT_SECONDS" -> "5", "POL_MEASURES_REF" -> "0e05564"))
        assertEquals(custom.command, Seq("/usr/bin/python3", "-I", "/tmp/w.py"))
        assertEquals(custom.timeout, 5.seconds)
        assert(custom.startupLine.contains("pol_measures @ 0e05564"), custom.startupLine)
        List("0", "301", "abc", "-1").foreach { value =>
            assertEquals(PolarizationSettings.fromEnv(Map("APP_POLARIZATION_TIMEOUT_SECONDS" -> value)).timeout, 30.seconds, value)
        }
    }

    test("decode: answers to other ids, missing members and non-JSON are malformed") {
        import PolarizationService.decode
        assertEquals(decode("""{"id":3,"ok":true,"result":{"a":1}}""", 3), Right(Right(JsObject("a" -> JsNumber(1)))))
        assertEquals(decode("""{"id":3,"ok":false,"error":{"code":"invalid_measure","message":"m","fields":[{"field":"k","issue":"i"}]}}""", 3),
            Right(Left(PolarizationFailure.Rejected("invalid_measure", "m", List(io.web.auth.FieldIssue("k", "i"))))))
        assert(decode("""{"id":2,"ok":true,"result":{}}""", 3).isLeft)
        assert(decode("""{"ok":true,"result":{}}""", 3).isLeft)
        assert(decode("""{"id":3,"ok":true}""", 3).isLeft)
        assert(decode("""{"id":3,"ok":false,"error":{"code":1}}""", 3).isLeft)
        assert(decode("""[1]""", 3).isLeft)
        assert(decode("""not json""", 3).isLeft)
    }
}
