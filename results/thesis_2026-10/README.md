# Thesis results (October 2026)

This folder holds the dataset behind Chapters 5 and 6 of *Extensión de los Modelos de Evolución de la Opinión SOM+ y
SOM− a Redes Coevolutivas* (J. E. Puyo Mora, Universidad del Valle, 2026). It was produced on 2026-10-14 with the code
of `release/v2.0-coevolution` that includes the symmetric-tolerance fix (`CoevolutionEngine.toleranceRadii`, see the
note below). The full per-round data (agents, neighbours and coevolution metrics) is in the `promueva_legacy`
PostgreSQL database, under the run ids below.

| File | Content | Run ids | Produced by |
|---|---|---|---|
| `parametric_results.csv` | Benchmark, N = 64…16 384: four regimes × 3 networks (static; Prop. C pure creation; Prop. A; Prop. B), iteration limit 500 | 160–195 | `core.simulation.ParametricTestRunner` |
| `edge_case_results_phase1.csv` | Extreme initial distributions, N = 512: uniform baseline, bimodal 50/50, radical minority; Prop. A and B; limit 200 | 196–200 | Phase 1 scenarios (see the thesis, Sec. 6.7.1) |
| `edge_case_results_phase2.csv` | Asymmetric tolerance (intolerant hubs) and absolute fluidity (P_break = P_create = 1), N = 512; official execution | 201–205 | `core.simulation.EdgeCaseRunner` |
| `edge_case_results_phase2_exec2.csv`, `..._exec3.csv` | The same Phase 2 campaign run twice more, on different networks | 206–210, 211–215 | `core.simulation.EdgeCaseRunner` |
| `edge_case_results_phase4.csv` | Resistance to consensus, N = 512: bimodal baseline, ablation P_create = 0, sub-critical τ = 0.02 | 216–218 | `core.simulation.EdgeCaseRunnerPhase4` |
| `parametric_results_no_db_timing.csv` | The benchmark rerun without database persistence, for timing only (new networks) | — | `ParametricTestRunner` with `APP_SKIP_DATABASE=true` |
| `non_consensus_networks.csv` | The 32 networks (of 177) that ended without consensus: stop reason, final κ, Φ and r, strongly connected components of the final topology | 160–218 | Final-round topology read from the database |
| `case_studies_results.txt` | Final state of the Ch. 5 case studies: three hand-built networks, static and coevolutionary, 20 seeds each | — | Custom-network runs through `Monitor` (`RunCustomNetwork`) |

## CSV columns

- `PopulationSize`, `Scenario`
- `RunID`: the database run id
- `TotalDurationMs`: wall-clock time of the configuration, including persistence
- `ConsensusCount`: networks that reached consensus, out of 3
- `AvgRounds`: mean final round over the three networks, **including those that stopped without consensus**

`non_consensus_networks.csv`: `Stop` is `stability` (no belief moved more than ε/100 for two rounds) or `limit`
(iteration limit). `GiantSCC` is the size of the largest strongly connected component; `MinorSCCSizes` lists the
others. `MinorWithNoExplicitNeighbours` counts minor components that are a single agent with no explicit in-neighbour
(isolation rule: all of its weight went back to self-influence). `ListenersOfMinor` is, per minor component, how many
outside agents still listen to it. `Pattern`: `a` isolated agents, `b` small closed groups, `c` several large
communities (see the thesis, Sec. 6.7.5).

## Notes

- Scenario names contain commas and are not quoted. Read `RunID` as the fourth field from the end.
- Each network's seed is `42 + runId + k` with `k = 1, 2, 3` (`networks.name` is `Nk`), so rerunning a campaign
  produces new networks, and consecutive runs share networks shifted by one index (run 202 N3 and run 203 N2 are the
  same network). Rounds and consensus counts vary accordingly.
- Structural stop rules (`assortivityStopThreshold`, `fragmentationStop`) were disabled in every run; runs end by
  consensus, stability or the iteration limit.
- Times include PostgreSQL persistence of every network's final topology, tens of millions of rows at N = 16 384.
  Compare strategies with the no-database timing file, not with `TotalDurationMs`.

## Tolerance used by the coevolution rules (fix of 2026-10-14)

SiLEnSeSS stores each agent's tolerance as a radius ρ and an offset o, and on the first update replaces them by the
bounds of the silence window `[o − ρ, o + ρ]` (on `B_j − B_i`). Generated networks use ρ = 0.10 and o = 0.05, so the
silence window is `[−0.05, 0.15]`. Before 2026-10-14 the coevolution hook passed the upper bound (`o + ρ`, i.e. 1.5 τ)
as the rupture/creation tolerance τ. The hook now passes `τ = (upper − lower) / 2`. All campaigns above were rerun
after the fix; the earlier run ids 91–159 are superseded. The case studies (offset 0) gave identical results before
and after. The asymmetric silence window itself belongs to the original engine and was left unchanged.
