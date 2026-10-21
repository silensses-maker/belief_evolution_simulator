package io.web.polarization

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import io.web.auth.FieldIssue
import spray.json.*

import scala.collection.mutable.ListBuffer

/**
 * A validated `POST /simulations/polarization` body (#129):
 * `{"measure": "EstebanRay", "params": {"alpha": 0.8}, "k": 5, "histograms": [[3, 0, 1, 0, 6], ...]}`.
 *
 * One histogram per round, each with `k` non-negative finite weights (agent counts or fractions;
 * the worker normalizes them with the package's `normalize_weights=True`). Shape checks live here
 * (400 `invalid_body`); whether the measure, its parameters and `k` are acceptable is decided by
 * the worker from the package itself (422 `invalid_measure`).
 *
 * @param params the measure's parameters as given (numbers, strings, nulls); missing = defaults
 */
final case class PolarizationRequest(measure: String, params: JsObject, k: Int, histograms: Array[Array[Double]]) {

    def rounds: Int = histograms.length

    /**
     * The worker's `compute` message as ONE JSON line (no newline): positions `x_j = j / (k − 1)`
     * and the histograms, printed straight into a buffer (a JsValue tree of 20 000 × 10 numbers is
     * avoidable garbage). Integral weights are printed as integers.
     */
    def workerMessage(id: Long): String = {
        val sb = new java.lang.StringBuilder(64 + histograms.length * (k * 4 + 3))
        sb.append("{\"id\":").append(id).append(",\"op\":\"compute\",\"measure\":")
        CompactPrinter.print(JsString(measure), sb)
        sb.append(",\"params\":")
        CompactPrinter.print(params, sb)
        sb.append(",\"x\":[")
        PolarizationRequest.positions(k).iterator.zipWithIndex.foreach { case (x, j) =>
            if (j > 0) sb.append(',')
            sb.append(x)
        }
        sb.append("],\"histograms\":[")
        var i = 0
        while (i < histograms.length) {
            if (i > 0) sb.append(',')
            sb.append('[')
            val row = histograms(i)
            var j = 0
            while (j < row.length) {
                if (j > 0) sb.append(',')
                PolarizationRequest.appendWeight(sb, row(j))
                j += 1
            }
            sb.append(']')
            i += 1
        }
        sb.append("]}")
        sb.toString
    }
}

object PolarizationRequest {
    final val MinCategories = 2
    final val MaxCategories = 10
    /** Rounds per request (the worker's MAX_ROUNDS is the same). Larger series are sent in chunks. */
    final val MaxRounds = 20000
    /** Body size limit of the endpoint (a 20 000 × 10 histogram batch of counts is ~1–2 MB). */
    final val MaxBodyBytes: Long = 8L * 1024 * 1024
    private final val MaxMeasureLength = 64
    private final val MaxParams = 16

    /** The k category positions j / (k − 1) in [0, 1] (what the package's examples use). */
    def positions(k: Int): Array[Double] = Array.tabulate(k)(j => j.toDouble / (k - 1))

    private def appendWeight(sb: java.lang.StringBuilder, value: Double): Unit =
        if (value == Math.rint(value) && value < 1e15) sb.append(value.toLong) else sb.append(value)

    /** The request, or the 400 `invalid_body` message with the offending members. */
    def parse(json: JsValue): Either[(String, List[FieldIssue]), PolarizationRequest] = json match {
        case JsObject(fields) =>
            val issues = ListBuffer.empty[FieldIssue]
            def issue(field: String, text: String): Unit = issues += FieldIssue(field, text)

            val measure = fields.get("measure") match {
                case Some(JsString(s)) if s.nonEmpty && s.length <= MaxMeasureLength => s
                case Some(JsString(_)) => issue("measure", s"must be a non-empty string of at most $MaxMeasureLength characters"); ""
                case Some(_)           => issue("measure", "must be a string"); ""
                case None              => issue("measure", "is required"); ""
            }

            val params = fields.get("params") match {
                case None | Some(JsNull) => JsObject.empty
                case Some(obj @ JsObject(members)) =>
                    if (members.size > MaxParams) issue("params", s"at most $MaxParams parameters")
                    members.foreach {
                        case (_, JsNumber(_) | JsString(_) | JsNull) => ()
                        case (name, _) => issue(s"params.$name", "must be a number, a string or null")
                    }
                    obj
                case Some(_) => issue("params", "must be an object"); JsObject.empty
            }

            val k = fields.get("k") match {
                case Some(JsNumber(n)) if n.isValidInt && n.toInt >= MinCategories && n.toInt <= MaxCategories => n.toInt
                case Some(JsNumber(_)) => issue("k", s"must be an integer from $MinCategories to $MaxCategories"); -1
                case Some(_)           => issue("k", "must be an integer"); -1
                case None              => issue("k", "is required"); -1
            }

            val histograms = fields.get("histograms") match {
                case Some(JsArray(rows)) if rows.isEmpty =>
                    issue("histograms", "must contain at least one histogram"); Array.empty[Array[Double]]
                case Some(JsArray(rows)) if rows.size > MaxRounds =>
                    issue("histograms", s"at most $MaxRounds histograms per request (got ${rows.size}); send the rounds in chunks")
                    Array.empty[Array[Double]]
                case Some(JsArray(rows)) if k > 0 =>
                    parseRows(rows, k) match {
                        case Right(parsed) => parsed
                        case Left(problem) => issues += problem; Array.empty[Array[Double]]
                    }
                case Some(JsArray(_)) => Array.empty[Array[Double]] // k is invalid: rows not checked
                case Some(_)          => issue("histograms", "must be an array of histograms"); Array.empty[Array[Double]]
                case None             => issue("histograms", "is required"); Array.empty[Array[Double]]
            }

            if (issues.isEmpty) Right(PolarizationRequest(measure, params, k, histograms))
            else Left(("Invalid polarization request", issues.toList))
        case _ => Left(("The body must be a JSON object", Nil))
    }

    /** Every row must have k finite weights >= 0; the first offending row or weight is reported. */
    private def parseRows(rows: Vector[JsValue], k: Int): Either[FieldIssue, Array[Array[Double]]] = {
        val out = new Array[Array[Double]](rows.size)
        var i = 0
        while (i < rows.size) {
            rows(i) match {
                case JsArray(values) if values.size == k =>
                    val row = new Array[Double](k)
                    var j = 0
                    while (j < k) {
                        values(j) match {
                            case JsNumber(n) =>
                                val value = n.toDouble
                                if (value.isNaN || value.isInfinite || value < 0)
                                    return Left(FieldIssue(s"histograms[$i][$j]", "must be a finite number >= 0"))
                                row(j) = value
                            case _ => return Left(FieldIssue(s"histograms[$i][$j]", "must be a number"))
                        }
                        j += 1
                    }
                    out(i) = row
                case JsArray(values) =>
                    return Left(FieldIssue(s"histograms[$i]", s"must have k = $k weights (got ${values.size})"))
                case _ =>
                    return Left(FieldIssue(s"histograms[$i]", s"must be an array of k = $k weights"))
            }
            i += 1
        }
        Right(out)
    }
}
