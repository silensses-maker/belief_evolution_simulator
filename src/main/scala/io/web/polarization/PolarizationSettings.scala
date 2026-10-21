package io.web.polarization

import scala.concurrent.duration.*

/**
 * Configuration of the polarization measures (#129). Every value has its production default HERE,
 * in code, so the production server needs no compose or `.env` change:
 *
 * | Variable                          | Default                              | Meaning |
 * |-----------------------------------|--------------------------------------|---------|
 * | `APP_POLARIZATION`                | enabled                              | `false` (exactly, any case) disables the endpoints (404) and Python is never started |
 * | `APP_POLARIZATION_PYTHON`         | `/opt/polarization/venv/bin/python3` | interpreter of the pol_measures venv (Dockerfile) |
 * | `APP_POLARIZATION_WORKER`         | `/app/polarization/worker.py`        | the worker script (Dockerfile) |
 * | `APP_POLARIZATION_TIMEOUT_SECONDS`| 30                                   | per-request timeout of the worker (1–300) |
 *
 * `POL_MEASURES_REF` is set by the Dockerfile (its `ARG POL_MEASURES_REF`) and only logged: the
 * worker reports the commit actually installed.
 */
final case class PolarizationSettings(
    enabled: Boolean,
    python: String,
    worker: String,
    timeout: FiniteDuration,
    packageRef: String
) {

    /** The worker's command line: isolated mode (`-I`), no PYTHON* variables, no user site. */
    def command: Seq[String] = Seq(python, "-I", worker)

    /** The one startup log line (Server.initialize). */
    def startupLine: String =
        if (enabled)
            s"Polarization measures (#129): enabled, pol_measures @ $packageRef; worker `${command.mkString(" ")}` " +
                s"starts on first use (timeout ${timeout.toSeconds} s)"
        else
            "Polarization measures (#129): DISABLED (APP_POLARIZATION=false): /simulations/polarization* " +
                "answer 404 and Python is never started"
}

object PolarizationSettings {
    final val DefaultPython: String = "/opt/polarization/venv/bin/python3"
    final val DefaultWorker: String = "/app/polarization/worker.py"
    final val DefaultTimeout: FiniteDuration = 30.seconds
    private final val MaxTimeoutSeconds = 300

    def fromEnv(env: collection.Map[String, String] = sys.env): PolarizationSettings = {
        def nonEmpty(name: String): Option[String] = env.get(name).map(_.trim).filter(_.nonEmpty)
        val timeout = nonEmpty("APP_POLARIZATION_TIMEOUT_SECONDS")
            .flatMap(_.toIntOption)
            .filter(seconds => seconds >= 1 && seconds <= MaxTimeoutSeconds)
            .map(_.seconds)
            .getOrElse(DefaultTimeout)
        PolarizationSettings(
            enabled    = !env.get("APP_POLARIZATION").exists(_.trim.equalsIgnoreCase("false")),
            python     = nonEmpty("APP_POLARIZATION_PYTHON").getOrElse(DefaultPython),
            worker     = nonEmpty("APP_POLARIZATION_WORKER").getOrElse(DefaultWorker),
            timeout    = timeout,
            packageRef = nonEmpty("POL_MEASURES_REF").getOrElse("unknown (POL_MEASURES_REF not set)")
        )
    }
}
