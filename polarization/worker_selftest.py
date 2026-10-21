"""
Self-test of the polarization worker (#129), run INSIDE the backend image:

    docker run --rm --entrypoint /opt/polarization/venv/bin/python3 <image> \
        -I /app/polarization/worker_selftest.py

It starts worker.py as the backend does (a subprocess speaking JSON lines), asks for the version,
the catalog and many computations, and checks every value against what the installed pol_measures
package returns when called directly, for uniform, consensus, two-pole and skewed histograms, for
every measure and every k the backend uses. A few textbook anchors (two poles = 1 for the
normalized measures, consensus = 0, ...) guard against both sides being wrong in the same way.
Exit code 0 = every check passed; the failures are listed otherwise.
"""

import inspect
import json
import math
import os
import subprocess
import sys

import numpy as np
from measures import thresholds as pkg_thresholds
from measures.metrics import literature, proposed

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, "worker.py")
CATEGORY_COUNTS = (3, 5, 7, 10)

failures = []
checks = 0


def check(condition, what):
    global checks
    checks += 1
    if not condition:
        failures.append(what)


class WorkerClient:
    def __init__(self):
        self.proc = subprocess.Popen(
            [sys.executable, "-I", WORKER],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.next_id = 0

    def raw(self, line):
        self.proc.stdin.write(line.encode("utf-8") + b"\n")
        self.proc.stdin.flush()
        answer = self.proc.stdout.readline()
        if not answer:
            raise RuntimeError("the worker closed its stdout")
        return json.loads(answer)

    def call(self, op, **members):
        self.next_id += 1
        answer = self.raw(json.dumps({"id": self.next_id, "op": op, **members}))
        check(answer.get("id") == self.next_id, f"{op}: answer id {answer.get('id')} != {self.next_id}")
        return answer

    def close(self):
        self.proc.stdin.close()
        code = self.proc.wait(timeout=10)
        stderr = self.proc.stderr.read().decode("utf-8", "replace")
        return code, stderr


def positions(k):
    return np.array([j / (k - 1) for j in range(k)], dtype=np.float64)


def histograms(k):
    middle = [0] * k
    middle[k // 2] = 10
    poles = [0] * k
    poles[0] = poles[-1] = 5
    skewed = [k - j for j in range(k)]
    return {"uniform": [1] * k, "consensus": middle, "two poles": poles, "skewed": skewed}


def classes():
    found = {}
    for module in (literature, proposed):
        for name in module.__all__:
            found[name] = getattr(module, name)
    return found


def main():
    worker = WorkerClient()
    measures = classes()

    # ── version ──────────────────────────────────────────────────────────────────────────────
    version = worker.call("version")
    check(version["ok"], f"version failed: {version}")
    package = version["result"]["package"]
    commit = package.get("commit") or ""
    check(len(commit) == 40 and all(c in "0123456789abcdef" for c in commit),
          f"installed commit is not a git sha: {commit!r}")
    expected_ref = os.environ.get("POL_MEASURES_REF")
    if expected_ref:
        check(commit == expected_ref, f"installed commit {commit} != POL_MEASURES_REF {expected_ref}")
    check(package.get("version") not in (None, "", "unknown"), f"package version missing: {package}")

    # ── catalog ──────────────────────────────────────────────────────────────────────────────
    answer = worker.call("catalog")
    check(answer["ok"], f"catalog failed: {answer}")
    catalog = answer["result"]
    by_id = {m["id"]: m for m in catalog["measures"]}
    check(list(by_id) == list(measures), f"catalog ids {list(by_id)} != package {list(measures)}")
    check([a["id"] for a in catalog["alienations"]] ==
          ["d", "d^2", "d^3", "d+d^2", "d+2d^2", "exp(d)-1", "exp(2d)-1"],
          f"alienations: {catalog['alienations']}")
    for measure_id, cls in measures.items():
        entry = by_id.get(measure_id, {})
        kind = "literature" if measure_id in literature.__all__ else "proposed"
        check(entry.get("kind") == kind, f"{measure_id}: kind {entry.get('kind')} != {kind}")
        check(entry.get("beta") == (measure_id == "MECNormalized"), f"{measure_id}: beta flag")
        defaults = {
            name: p.default for name, p in inspect.signature(cls.__init__).parameters.items()
            if name != "self"
        }
        for spec in entry.get("params", []):
            if spec["type"] == "number":
                check(spec["default"] == defaults.get(spec["name"]),
                      f"{measure_id}.{spec['name']}: default {spec['default']} != package {defaults.get(spec['name'])}")
            else:
                check(spec["default"] == "d" and defaults.get(spec["name"]) is None,
                      f"{measure_id}.{spec['name']}: alienation default")
        check({s["name"] for s in entry.get("params", [])} == set(defaults),
              f"{measure_id}: params {entry.get('params')} != signature {list(defaults)}")
        has_table = measure_id in pkg_thresholds.THRESHOLDS
        check(entry.get("hasThresholds") == has_table, f"{measure_id}: hasThresholds")
    check(by_id["Experts"]["categories"] == [5], f"Experts categories: {by_id['Experts']['categories']}")
    check(2 not in by_id["VanDerEijkPol"]["categories"], "VanDerEijkPol must not support k = 2")
    for measure_id in ("EstebanRay", "BiPol", "MECNormalized"):
        classification = by_id[measure_id]["classification"]
        table = pkg_thresholds.THRESHOLDS[measure_id]
        expected = (table["default"] if "_params" in table else table)["kmeans"][3]
        check(classification is not None and classification["thresholds"] == expected
              and classification["levels"] == ["low", "medium", "high"],
              f"{measure_id}: default classification {classification} != kmeans/3 {expected}")
    for measure_id in ("GeneralizedER", "EMDPol", "Experts", "ShannonPol", "VanDerEijkPol", "MEC",
                       "GeneralizedMEC"):
        check(by_id[measure_id]["classification"] is None, f"{measure_id}: unexpected classification")

    # The ranges the catalog announces are the package's: just outside a bound it raises.
    def raises(build):
        try:
            build()
        except Exception:
            return True
        return False

    x5, w5 = positions(5), np.ones(5)
    check(raises(lambda: literature.EstebanRay(alpha=1.6000001)), "EstebanRay accepts alpha > 1.6")
    check(not raises(lambda: literature.EstebanRay(alpha=1.6)), "EstebanRay rejects alpha = 1.6")
    check(raises(lambda: literature.EstebanRay(alpha=0)), "EstebanRay accepts alpha = 0")
    check(raises(lambda: literature.GeneralizedER(alpha=0)), "GeneralizedER accepts alpha = 0")
    check(raises(lambda: proposed.GeneralizedMEC(alpha=0)), "GeneralizedMEC accepts alpha = 0")
    for name in ("MEC", "MECNormalized"):
        cls = getattr(proposed, name)
        check(raises(lambda: cls(alpha=0)(x5, w5)), f"{name} accepts alpha = 0")
        check(raises(lambda: cls(beta=0)(x5, w5)), f"{name} accepts beta = 0")

    # ── compute: every measure, k and histogram against the package called directly ──────────
    for measure_id, cls in measures.items():
        for k in CATEGORY_COUNTS:
            if k not in by_id[measure_id]["categories"]:
                continue
            x = positions(k)
            cases = histograms(k)
            answer = worker.call("compute", measure=measure_id, params={}, x=list(x),
                                 histograms=list(cases.values()))
            if not answer["ok"]:
                check(False, f"{measure_id} k={k}: {answer['error']}")
                continue
            result = answer["result"]
            check(result["package"]["ref"] == commit, f"{measure_id}: package ref in compute answer")
            for i, (case, row) in enumerate(cases.items()):
                weights = np.array(row, dtype=np.float64)
                expected = float(cls()(x, weights, normalize_weights=True))
                got = result["values"][i]
                check(got == expected, f"{measure_id} k={k} {case}: worker {got} != package {expected}")
                if by_id[measure_id]["classification"] is not None:
                    _, level = cls()(x, weights, labels=3, method="kmeans", normalize_weights=True)
                    check(result["levels"][i] == level,
                          f"{measure_id} k={k} {case}: level {result['levels'][i]} != {level}")
                else:
                    check(result["levels"][i] is None, f"{measure_id} k={k} {case}: unexpected level")

    # Textbook anchors (k = 5): two poles is the maximum of the normalized measures, consensus 0.
    anchors = [
        ("EstebanRay", "two poles", 1.0), ("GeneralizedER", "two poles", 1.0),
        ("EMDPol", "two poles", 0.5), ("Experts", "two poles", 1.0), ("BiPol", "two poles", 1.0),
        ("MECNormalized", "two poles", 1.0), ("EstebanRay", "consensus", 0.0),
        ("GeneralizedER", "consensus", 0.0), ("EMDPol", "consensus", 0.0),
        ("Experts", "consensus", 0.0), ("BiPol", "consensus", 0.0), ("MEC", "consensus", 0.0),
        ("MECNormalized", "consensus", 0.0), ("VanDerEijkPol", "consensus", 0.0),
    ]
    cases5 = histograms(5)
    for measure_id, case, expected in anchors:
        answer = worker.call("compute", measure=measure_id, x=list(positions(5)), histograms=[cases5[case]])
        got = answer["result"]["values"][0] if answer["ok"] else None
        check(got is not None and math.isclose(got, expected, abs_tol=1e-6),
              f"anchor {measure_id} {case}: {got} != {expected}")

    # Non-default parameters and f(d): still the package's values.
    x = positions(5)
    w = np.array([3, 1, 0, 2, 4], dtype=np.float64)
    param_cases = [
        ("EstebanRay", {"alpha": 1.2, "K": 2.5}, lambda: literature.EstebanRay(alpha=1.2, K=2.5)),
        ("MEC", {"alpha": 1, "beta": 1}, lambda: proposed.MEC(alpha=1, beta=1)),
        ("GeneralizedER", {"alpha": 0.5, "alienation": "d^2"},
         lambda: literature.GeneralizedER(alpha=0.5, alienation=lambda d: d**2)),
        ("GeneralizedMEC", {"alpha": 1.5, "alienation": "exp(2d)-1"},
         lambda: proposed.GeneralizedMEC(alpha=1.5, alienation=lambda d: np.exp(2 * d) - 1)),
    ]
    for measure_id, params, build in param_cases:
        answer = worker.call("compute", measure=measure_id, params=params, x=list(x), histograms=[list(w)])
        expected = float(build()(x, w, normalize_weights=True))
        got = answer["result"]["values"][0] if answer["ok"] else answer
        check(got == expected, f"{measure_id} {params}: worker {got} != package {expected}")
    answer = worker.call("compute", measure="EstebanRay", params={"alpha": 1.0}, x=list(x), histograms=[list(w)])
    check(answer["ok"] and answer["result"]["classification"] is None and answer["result"]["levels"] == [None],
          "EstebanRay alpha = 1: no thresholds apply")

    # ── dedupe ───────────────────────────────────────────────────────────────────────────────
    rows = [[1, 2, 3, 4, 5]] * 1000 + [[5, 4, 3, 2, 1]]
    answer = worker.call("compute", measure="MEC", x=list(x), histograms=rows)
    check(answer["ok"] and answer["result"]["unique"] == 2, f"dedupe: {answer.get('result', answer)}")
    check(answer["ok"] and len(set(answer["result"]["values"][:1000])) == 1, "dedupe: same values")

    # ── errors ───────────────────────────────────────────────────────────────────────────────
    def error_of(**members):
        answer = worker.call("compute", **members)
        return None if answer["ok"] else answer["error"]

    def expect_error(error, code, field, what):
        fields = [f["field"] for f in (error or {}).get("fields", [])]
        check(error is not None and error["code"] == code and field in fields,
              f"{what}: expected {code} on {field}, got {error}")

    xs = list(positions(5))
    expect_error(error_of(measure="Nope", x=xs, histograms=[[1] * 5]), "invalid_measure", "measure", "unknown measure")
    expect_error(error_of(measure="Experts", x=list(positions(3)), histograms=[[1] * 3]),
                 "invalid_measure", "k", "Experts with k = 3")
    expect_error(error_of(measure="EstebanRay", params={"alpha": 2}, x=xs, histograms=[[1] * 5]),
                 "invalid_measure", "params.alpha", "EstebanRay alpha = 2")
    expect_error(error_of(measure="MEC", params={"beta": 0}, x=xs, histograms=[[1] * 5]),
                 "invalid_measure", "params.beta", "MEC beta = 0")
    expect_error(error_of(measure="MEC", params={"gamma": 1}, x=xs, histograms=[[1] * 5]),
                 "invalid_measure", "params.gamma", "unknown parameter")
    expect_error(error_of(measure="GeneralizedER", params={"alienation": "__import__('os').getcwd()"},
                          x=xs, histograms=[[1] * 5]),
                 "invalid_measure", "params.alienation", "alienation outside the whitelist")
    expect_error(error_of(measure="MEC", params={"alpha": True}, x=xs, histograms=[[1] * 5]),
                 "invalid_measure", "params.alpha", "boolean parameter")
    expect_error(error_of(measure="MEC", x=xs, histograms=[[1, -1, 0, 0, 0]]),
                 "invalid_request", "histograms[0][1]", "negative weight")
    expect_error(error_of(measure="MEC", x=xs, histograms=[[1, 1, 1]]),
                 "invalid_request", "histograms[0]", "wrong histogram length")
    expect_error(error_of(measure="MEC", x=xs, histograms=[]), "invalid_request", "histograms", "no histograms")
    expect_error(error_of(measure="MEC", x=xs, histograms=[[1] * 5] * 20001),
                 "invalid_request", "histograms", "too many histograms")
    expect_error(error_of(measure="MEC", x=[0, 1, 0.5], histograms=[[1] * 3]), "invalid_request", "x", "unsorted x")
    expect_error(error_of(measure="MEC", x=[0.0] * 11, histograms=[[1] * 11]), "invalid_request", "x", "k = 11")
    nan_answer = worker.raw('{"id": 99, "op": "compute", "measure": "MEC", "x": [0, 0.5, 1], '
                            '"histograms": [[1, NaN, 1]]}')
    expect_error(None if nan_answer["ok"] else nan_answer["error"], "invalid_request", "histograms[0][1]", "NaN weight")
    answer = worker.call("compute", measure="MEC", x=xs, histograms=[[0] * 5, [1] * 5])
    errors = answer["result"].get("errors", []) if answer["ok"] else []
    check(answer["ok"] and answer["result"]["values"][0] is None and answer["result"]["values"][1] is not None
          and "At least one weight must be positive" in (errors[0] or "") and errors[1] is None,
          f"empty histogram: per-round package error, got {answer}")
    answer = worker.raw("this is not json")
    check(not answer["ok"] and answer["error"]["code"] == "invalid_request", f"malformed line: {answer}")
    answer = worker.call("nope")
    check(not answer["ok"] and answer["error"]["code"] == "invalid_request", f"unknown op: {answer}")
    answer = worker.call("version")
    check(answer["ok"], "the worker keeps answering after errors")

    code, stderr = worker.close()
    check(code == 0, f"worker exit code on stdin EOF: {code}")
    # The package prints warnings (VanDerEijkPol, k = 2 probe); they went to stderr, not the protocol.
    check("measure is not defined" in stderr, "package print() output is diverted to stderr")

    if failures:
        print(f"worker self-test: {len(failures)} of {checks} checks FAILED")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print(f"worker self-test: all {checks} checks passed "
          f"(pol_measures {package.get('version')} @ {commit}, numpy {version['result']['numpy']}, "
          f"scipy {version['result']['scipy']}, Python {version['result']['python']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
