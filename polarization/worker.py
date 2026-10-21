"""
Polarization worker (#129): computes polarization measures with the REAL pol_measures package
(github.com/Ulvenforst/pol_measures, Python package `measures`, pinned in the Dockerfile by
POL_MEASURES_REF) for the backend's /simulations/polarization endpoints.

One persistent process per backend (io.web.polarization.PolarizationService starts it on first use
and reuses it). Protocol: JSON lines. Each request is ONE line on stdin, each answer ONE line on
stdout, in order:

    request : {"id": <int>, "op": "catalog" | "compute" | "version", ...}
    answer  : {"id": <same>, "ok": true, "result": {...}}
            | {"id": <same>, "ok": false, "error": {"code", "message"[, "fields": [{"field", "issue"}]]}}

error codes: "invalid_request" (malformed line, unknown op, bad x / histograms), "invalid_measure"
(unknown measure or parameter, parameter rejected by the package, unsupported k) and
"internal_error" (a bug here; the process keeps running).

Rules:
  - ONLY the package's public classes (measures.metrics.literature / .proposed `__all__`), their
    public call (`measure(x, w, labels=..., method=..., normalize_weights=True)`) and its public
    threshold tables (measures.thresholds) are used. No measure is reimplemented here.
  - f(d) of GeneralizedER / GeneralizedMEC is one of the named functions in ALIENATIONS; client
    input is never evaluated.
  - Run with `python3 -I` (isolated: no PYTHON* variables, no user site, no script directory on
    sys.path). The worker reads stdin and writes stdout/stderr only: no network, no files besides
    the imported code and the installed package's metadata (its version and commit).
  - Anything the package prints goes to stderr (the backend logs it), never into the protocol.
"""

import io
import json
import math
import os
import platform
import sys
import traceback

PROTOCOL_VERSION = 1

# k (number of categories) accepted by `compute`; the backend uses 3, 5, 7 or 10.
MIN_CATEGORIES = 2
MAX_CATEGORIES = 10
# Rounds (histograms) per compute request; the HTTP endpoint has the same limit.
MAX_ROUNDS = 20000

# The scheme the run charts show: the package's default method, 3 levels (low / medium / high).
CLASSIFICATION_METHOD = "kmeans"
CLASSIFICATION_LEVELS = 3

# Display names (presentation only; ids are the package's class names).
DISPLAY_NAMES = {
    "EstebanRay": "Esteban–Ray",
    "GeneralizedER": "Generalized ER",
    "EMDPol": "EMD-pol",
    "Experts": "Experts",
    "ShannonPol": "Shannon-pol",
    "VanDerEijkPol": "Van der Eijk-pol",
    "MEC": "MEC",
    "MECNormalized": "MEC normalized",
    "GeneralizedMEC": "Generalized MEC",
    "BiPol": "BiPol",
}

# Still being developed upstream: the package README says of MECNormalized "It is still being
# developed".
BETA_MEASURES = frozenset({"MECNormalized"})

# Ranges of the numeric parameters, as the package enforces them (the package itself is still the
# final check: every measure is built and run once with the requested parameters before use):
#   EstebanRay.__init__        0 < alpha <= 1.6   (esteban_ray.py)
#   GeneralizedER.__init__     alpha > 0          (generalized_er.py)
#   GeneralizedMEC.__init__    alpha > 0          (generalized_mec.py)
#   MEC / MECNormalized        alpha, beta > 0    (validation.validate_parameters, at compute time)
# K (EstebanRay, GeneralizedER) is not checked by the package; None is its default normalization.
# The worker only accepts K > 0 (a non-positive K gives non-positive "polarization").
PARAM_RANGES = {
    ("EstebanRay", "alpha"): (0.0, 1.6),
    ("EstebanRay", "K"): (0.0, None),
    ("GeneralizedER", "alpha"): (0.0, None),
    ("GeneralizedER", "K"): (0.0, None),
    ("GeneralizedMEC", "alpha"): (0.0, None),
    ("MEC", "alpha"): (0.0, None),
    ("MEC", "beta"): (0.0, None),
    ("MECNormalized", "alpha"): (0.0, None),
    ("MECNormalized", "beta"): (0.0, None),
}

# Parameters taking a function: only these named alienation functions f(d). They are the ones the
# package's own validation uses (benchmarks/generalized_er_validation/run_validation.py,
# ALIENATION_FUNCTIONS) plus the identity, the package's default.
CALLABLE_PARAMS = frozenset({"alienation"})
ALIENATIONS = {}  # id -> (function, label, TeX); filled in main() once numpy is imported


def _alienations(np):
    return {
        "d": (lambda d: d, "d", "d"),
        "d^2": (lambda d: d**2, "d²", "d^{2}"),
        "d^3": (lambda d: d**3, "d³", "d^{3}"),
        "d+d^2": (lambda d: d + d**2, "d + d²", "d + d^{2}"),
        "d+2d^2": (lambda d: d + 2 * d**2, "d + 2d²", "d + 2d^{2}"),
        "exp(d)-1": (lambda d: np.exp(d) - 1, "eᵈ − 1", "e^{d} - 1"),
        "exp(2d)-1": (lambda d: np.exp(2 * d) - 1, "e²ᵈ − 1", "e^{2d} - 1"),
    }


DEFAULT_ALIENATION = "d"


class RequestError(Exception):
    """A structured error answer."""

    def __init__(self, code, message, fields=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.fields = fields or []


def _invalid(message, field=None, issue=None):
    fields = [{"field": field, "issue": issue or message}] if field else None
    return RequestError("invalid_request", message, fields)


def _rejected(message, field=None, issue=None):
    fields = [{"field": field, "issue": issue or message}] if field else None
    return RequestError("invalid_measure", message, fields)


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _finite_or_none(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


class Worker:
    def __init__(self):
        import numpy as np
        import scipy
        import measures  # noqa: F401  (the package; imports its submodules)
        from measures.metrics import literature, proposed
        from measures import thresholds

        self.np = np
        self.scipy = scipy
        self.thresholds = thresholds
        ALIENATIONS.update(_alienations(np))
        self.classes = {}
        self.kinds = {}
        for kind, module in (("literature", literature), ("proposed", proposed)):
            for name in module.__all__:
                self.classes[name] = getattr(module, name)
                self.kinds[name] = kind
        self._version = None
        self._catalog = None
        self._specs = {}

    # ── version ────────────────────────────────────────────────────────────────────────────
    def version(self):
        if self._version is None:
            from importlib import metadata

            info = {"name": "measures", "version": "unknown", "commit": None, "url": None}
            try:
                dist = metadata.distribution("measures")
                info["version"] = dist.version
                direct = dist.read_text("direct_url.json")
                if direct:
                    parsed = json.loads(direct)
                    info["url"] = parsed.get("url")
                    info["commit"] = (parsed.get("vcs_info") or {}).get("commit_id")
            except Exception:  # metadata missing (e.g. a source checkout on sys.path)
                pass
            if not info["commit"]:
                info["commit"] = os.environ.get("POL_MEASURES_REF") or "unknown"
            self._version = {
                "protocol": PROTOCOL_VERSION,
                "package": info,
                "numpy": self.np.__version__,
                "scipy": self.scipy.__version__,
                "python": platform.python_version(),
            }
        return self._version

    def package_ref(self):
        package = self.version()["package"]
        return {"ref": package["commit"], "version": package["version"]}

    # ── catalog ────────────────────────────────────────────────────────────────────────────
    def param_specs(self, measure_id):
        """Parameters of a measure from its constructor signature (defaults are the package's)."""
        if measure_id not in self._specs:
            import inspect

            specs = []
            signature = inspect.signature(self.classes[measure_id].__init__)
            for name, parameter in signature.parameters.items():
                if name == "self" or parameter.kind in (parameter.VAR_POSITIONAL, parameter.VAR_KEYWORD):
                    continue
                default = parameter.default
                if name in CALLABLE_PARAMS:
                    specs.append({
                        "name": name,
                        "type": "choice",
                        "default": DEFAULT_ALIENATION,
                        "options": list(ALIENATIONS),
                    })
                    continue
                if default is not None and not _is_number(default):
                    continue  # not exposed: the package default is always used
                low, high = PARAM_RANGES.get((measure_id, name), (None, None))
                specs.append({
                    "name": name,
                    "type": "number",
                    "default": default,
                    "nullable": default is None,
                    "minExclusive": low,
                    "max": high,
                })
            self._specs[measure_id] = specs
        return self._specs[measure_id]

    def classification_of(self, measure):
        """Thresholds of the run charts' scheme for this instance's parameters (None if none apply)."""
        try:
            param_set = measure.find_matching_parameter_set()
        except Exception:
            return None
        if param_set is None:
            return None
        table = self.thresholds.THRESHOLDS.get(measure.measure_id)
        if table is None:
            return None
        params = {}
        if "_params" in table:
            params = dict(table["_params"].get(param_set, {}))
            table = table.get(param_set)
        try:
            bounds = list(table[CLASSIFICATION_METHOD][CLASSIFICATION_LEVELS])
        except (KeyError, TypeError):
            return None
        labels = self.thresholds.CATEGORY_LABELS.get(
            CLASSIFICATION_LEVELS, [f"category_{i + 1}" for i in range(CLASSIFICATION_LEVELS)]
        )
        return {
            "method": CLASSIFICATION_METHOD,
            "paramSet": param_set,
            "params": params,
            "levels": list(labels),
            "thresholds": [float(b) for b in bounds],
        }

    def supported_categories(self, measure_id):
        """k in [MIN, MAX] for which the measure (default parameters) gives a finite value."""
        supported = []
        for k in range(MIN_CATEGORIES, MAX_CATEGORIES + 1):
            x = self.positions(k)
            try:
                value = self.classes[measure_id]()(x, self.np.ones(k), normalize_weights=True)
            except Exception:
                continue
            if _finite_or_none(value) is not None:
                supported.append(k)
        return supported

    def catalog(self):
        if self._catalog is None:
            measures_info = []
            for measure_id, cls in self.classes.items():
                measures_info.append({
                    "id": measure_id,
                    "name": DISPLAY_NAMES.get(measure_id, measure_id),
                    "kind": self.kinds[measure_id],
                    "beta": measure_id in BETA_MEASURES,
                    "params": self.param_specs(measure_id),
                    "categories": self.supported_categories(measure_id),
                    "hasThresholds": measure_id in self.thresholds.THRESHOLDS,
                    "classification": self.classification_of(cls()),
                })
            self._catalog = {
                "measures": measures_info,
                "alienations": [
                    {"id": key, "label": label, "tex": tex} for key, (_, label, tex) in ALIENATIONS.items()
                ],
                "classification": {"method": CLASSIFICATION_METHOD, "levels": CLASSIFICATION_LEVELS},
                "limits": {
                    "minCategories": MIN_CATEGORIES,
                    "maxCategories": MAX_CATEGORIES,
                    "maxRounds": MAX_ROUNDS,
                },
                "package": self.version()["package"],
                "versions": {
                    "numpy": self.np.__version__,
                    "scipy": self.scipy.__version__,
                    "python": platform.python_version(),
                },
            }
        return self._catalog

    # ── compute ────────────────────────────────────────────────────────────────────────────
    def positions(self, k):
        return self.np.array([j / (k - 1) for j in range(k)], dtype=self.np.float64)

    def build_measure(self, measure_id, raw_params):
        if not isinstance(measure_id, str) or measure_id not in self.classes:
            raise _rejected(f"Unknown measure: {measure_id!r}", "measure",
                            f"must be one of {', '.join(self.classes)}")
        if raw_params is None:
            raw_params = {}
        if not isinstance(raw_params, dict):
            raise _invalid("params must be an object", "params")
        specs = {spec["name"]: spec for spec in self.param_specs(measure_id)}
        kwargs = {}
        for name, value in raw_params.items():
            field = f"params.{name}"
            spec = specs.get(name)
            if spec is None:
                allowed = ", ".join(specs) or "none"
                raise _rejected(f"{measure_id} has no parameter {name!r}", field,
                                f"unknown parameter (allowed: {allowed})")
            if spec["type"] == "choice":
                if value is None:
                    continue
                if not isinstance(value, str) or value not in ALIENATIONS:
                    raise _rejected(f"Unknown {name} for {measure_id}: {value!r}", field,
                                    f"must be one of {', '.join(ALIENATIONS)}")
                kwargs[name] = ALIENATIONS[value][0]
                continue
            if value is None:
                if not spec["nullable"]:
                    raise _rejected(f"{measure_id}.{name} must be a number", field, "must be a number")
                continue
            if not _is_number(value) or not math.isfinite(float(value)):
                raise _rejected(f"{measure_id}.{name} must be a finite number", field,
                                "must be a finite number")
            low, high = spec["minExclusive"], spec["max"]
            if (low is not None and value <= low) or (high is not None and value > high):
                interval = f"({low}, {high}]" if high is not None else f"> {low}"
                raise _rejected(f"{measure_id}.{name} = {value} is outside {interval}", field,
                                f"must be in {interval}")
            kwargs[name] = value
        try:
            return self.classes[measure_id](**kwargs)
        except Exception as exc:  # the package's own parameter checks (constructor)
            raise _rejected(f"{measure_id} rejected the parameters: {exc}", "params", str(exc))

    def compute(self, message):
        np = self.np
        measure_id = message.get("measure")
        measure = self.build_measure(measure_id, message.get("params"))

        x_raw = message.get("x")
        if not isinstance(x_raw, list) or not (MIN_CATEGORIES <= len(x_raw) <= MAX_CATEGORIES):
            raise _invalid(f"x must be a list of {MIN_CATEGORIES} to {MAX_CATEGORIES} positions", "x")
        if not all(_is_number(v) and math.isfinite(float(v)) for v in x_raw):
            raise _invalid("x must contain finite numbers", "x")
        k = len(x_raw)
        x = np.array(x_raw, dtype=np.float64)
        if not np.all(np.diff(x) > 0):
            raise _invalid("x values must be strictly increasing", "x")

        supported = self.catalog_entry(measure_id)["categories"]
        if k not in supported:
            raise _rejected(f"{measure_id} is not defined for k = {k} categories", "k",
                            f"supported: {', '.join(map(str, supported))}")

        histograms = message.get("histograms")
        if not isinstance(histograms, list) or not histograms:
            raise _invalid("histograms must be a non-empty list", "histograms")
        if len(histograms) > MAX_ROUNDS:
            raise _invalid(f"At most {MAX_ROUNDS} histograms per request (got {len(histograms)})",
                           "histograms")
        rows = []
        for i, row in enumerate(histograms):
            field = f"histograms[{i}]"
            if not isinstance(row, list) or len(row) != k:
                raise _invalid(f"{field} must be a list of k = {k} weights", field)
            for j, value in enumerate(row):
                if not _is_number(value) or not math.isfinite(float(value)) or value < 0:
                    raise _invalid(f"{field}[{j}] must be a finite number >= 0", f"{field}[{j}]")
            rows.append(tuple(float(v) for v in row))

        # The package's own compute-time checks (e.g. MEC's validate_parameters) on a probe histogram:
        # a parameter it rejects fails the request instead of every round.
        try:
            measure(x, np.ones(k), normalize_weights=True)
        except Exception as exc:
            raise _rejected(f"{measure_id} rejected the parameters: {exc}", "params", str(exc))

        classification = self.classification_of(measure)
        unique = {}
        values, levels, errors = [], [], []
        any_error = False
        for row in rows:
            outcome = unique.get(row)
            if outcome is None:
                outcome = self.evaluate(measure, x, row, classification is not None)
                unique[row] = outcome
            values.append(outcome[0])
            levels.append(outcome[1])
            errors.append(outcome[2])
            any_error = any_error or outcome[2] is not None

        result = {
            "values": values,
            "levels": levels,
            "classification": classification,
            "unique": len(unique),
            "package": self.package_ref(),
        }
        if any_error:
            result["errors"] = errors
        return result

    def evaluate(self, measure, x, row, classify):
        """(value | None, level | None, error | None) of one histogram, by the package."""
        weights = self.np.array(row, dtype=self.np.float64)
        try:
            if classify:
                raw, label = measure(x, weights, labels=CLASSIFICATION_LEVELS,
                                     method=CLASSIFICATION_METHOD, normalize_weights=True)
            else:
                raw, label = measure(x, weights, normalize_weights=True), None
        except Exception as exc:  # the package's per-histogram errors (e.g. an empty histogram)
            return None, None, f"{type(exc).__name__}: {exc}"
        value = _finite_or_none(raw)
        if value is None:
            return None, None, f"The measure returned a non-finite value ({raw})"
        level = label if isinstance(label, str) and label != "no_classification" else None
        return value, level, None

    def catalog_entry(self, measure_id):
        for entry in self.catalog()["measures"]:
            if entry["id"] == measure_id:
                return entry
        raise _rejected(f"Unknown measure: {measure_id!r}", "measure")

    # ── dispatch ───────────────────────────────────────────────────────────────────────────
    def handle(self, line):
        request_id = None
        try:
            try:
                message = json.loads(line)
            except ValueError as exc:
                raise _invalid(f"Malformed JSON: {exc}")
            if not isinstance(message, dict):
                raise _invalid("A request must be a JSON object")
            request_id = message.get("id")
            op = message.get("op")
            if op == "version":
                result = self.version()
            elif op == "catalog":
                result = self.catalog()
            elif op == "compute":
                result = self.compute(message)
            else:
                raise _invalid(f"Unknown op: {op!r}", "op", "must be catalog, compute or version")
            return {"id": request_id, "ok": True, "result": result}
        except RequestError as exc:
            error = {"code": exc.code, "message": exc.message}
            if exc.fields:
                error["fields"] = exc.fields
            return {"id": request_id, "ok": False, "error": error}
        except Exception as exc:
            traceback.print_exc(file=sys.stderr)
            return {"id": request_id, "ok": False,
                    "error": {"code": "internal_error", "message": f"{type(exc).__name__}: {exc}"}}


def main():
    stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8", errors="replace")
    protocol_out = sys.stdout.buffer
    # Anything printed from here on (the package prints warnings with print()) goes to stderr.
    sys.stdout = sys.stderr
    worker = Worker()
    while True:
        line = stdin.readline()
        if not line:
            return 0  # stdin closed: the backend went away
        line = line.strip()
        if not line:
            continue
        answer = worker.handle(line)
        try:
            encoded = json.dumps(answer, allow_nan=False, separators=(",", ":"))
        except ValueError:  # a non-finite number slipped through: never write invalid JSON
            encoded = json.dumps({"id": answer.get("id"), "ok": False,
                                  "error": {"code": "internal_error",
                                            "message": "non-finite number in the answer"}})
        protocol_out.write(encoded.encode("ascii") + b"\n")
        protocol_out.flush()


if __name__ == "__main__":
    sys.exit(main())
