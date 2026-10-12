# Thesis results (October 2026)

This folder holds the dataset behind Chapters 5 and 6 of *Extensión de los Modelos de Evolución de la Opinión SOM+ y
SOM− a Redes Coevolutivas* (J. E. Puyo Mora, Universidad del Valle, 2026). It was produced with the code at commit
`6c91936` of `release/v2.0-coevolution`. The full per-round data (agents, neighbours and coevolution metrics) is in
the `promueva_legacy` PostgreSQL database, under the run ids below.

| File | Content | Run ids | Produced by |
|---|---|---|---|
| `parametric_results.csv` | Benchmark, N = 64…16 384: four regimes × 3 networks (static; Prop. C pure creation; Prop. A; Prop. B) | 91–126 | `core.simulation.ParametricTestRunner` |
| `edge_case_results_phase1.csv` | Extreme initial distributions: uniform baseline, bimodal 50/50, radical minority; Prop. A and B | 127–131 | Phase 1 scenarios (see the thesis, Sec. 6.7.1) |
| `edge_case_results_phase2.csv` | Asymmetric tolerance (intolerant hubs) and absolute fluidity (P_break = P_create = 1) | 132–136 | `core.simulation.EdgeCaseRunner` |
| `edge_case_results_phase4.csv` | Resistance to consensus: bimodal baseline, ablation P_create = 0, sub-critical τ = 0.02 | 137–139 | `core.simulation.EdgeCaseRunnerPhase4` |
| `parametric_results_no_db_timing.csv` | The benchmark rerun without database persistence, for timing only (new networks) | — | `ParametricTestRunner` with `APP_SKIP_DATABASE=true` |
| `case_studies_results.txt` | Final state of the Ch. 5 case studies: three hand-built networks, static and coevolutionary, 20 seeds each | — | Custom-network runs through `Monitor` (`RunCustomNetwork`) |

## CSV columns

- `PopulationSize`, `Scenario`
- `RunID`: the database run id
- `TotalDurationMs`: wall-clock time of the configuration, including persistence
- `ConsensusCount`: networks that reached consensus, out of 3
- `AvgRounds`: mean final round

## Notes

- Scenario names contain commas and are not quoted. Read `RunID` as the fourth field from the end.
- Each network's seed is `42 + runId + networkIndex`, so rerunning a campaign produces new networks. Rounds and
  consensus counts vary accordingly. Under absolute fluidity (Phase 2, Case 2B) they vary a lot between executions.
- Times include PostgreSQL persistence of every network's final topology, tens of millions of rows at N = 16 384.
  Compare strategies with the no-database timing file, not with `TotalDurationMs`.
