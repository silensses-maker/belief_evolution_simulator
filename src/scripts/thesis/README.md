# Reproducing the thesis experiments

*Extensión de los Modelos de Evolución de la Opinión SOM+ y SOM− a Redes Coevolutivas* (J. E. Puyo Mora,
Universidad del Valle, 2026). The dataset of the thesis is in [`results/puyo/thesis_2026-10/`](../../../results/puyo/thesis_2026-10/)
and, per round, in the `promueva_legacy` database under run ids 160–218. This folder holds what regenerates it.

## With Docker (recommended)

```bash
src/scripts/thesis/reproduce.sh                 # campaigns, case studies, tables and figures
src/scripts/thesis/reproduce.sh phase4          # only some campaigns
THESIS_RESULTS=./results/my_replica THESIS_JAVA_OPTS=-Xmx16g src/scripts/thesis/reproduce.sh
```

`docker-compose.thesis.yml` builds the simulator, a PostgreSQL 17 initialised with `db/init/` and a Python image
for the analysis. It has its own database volume and publishes no port, so it does not touch the main stack. The
benchmark goes up to N = 16 384, whose networks grow to tens of millions of edges: give Docker at least 28 GB of
memory, or lower `THESIS_JAVA_OPTS` and run a subset. Output in `results/replica/` (git-ignored):

| File | Content |
|---|---|
| `parametric_results.csv`, `edge_case_results_phase*.csv` | One line per run, same format and names as the thesis dataset |
| `case_studies_results.txt`, `case_studies.log` | Ch. 5 case studies (final states; per-round log) |
| `non_consensus_networks.csv`, `small_world_results.csv` | Tables of Sec. 6.7.5 and 6.7.3 |
| `figures/` | Every figure of Ch. 3, 5 and 6 |

Run ids are pinned (below), so a second replica needs a fresh database:
`docker compose -f docker-compose.thesis.yml down -v`.

## Step by step

In Git Bash on Windows, run `export MSYS_NO_PATHCONV=1` first: otherwise `/results` is rewritten as a Windows path
before it reaches the container (`reproduce.sh` does this itself).

```bash
docker compose -f docker-compose.thesis.yml run --rm experiments list
docker compose -f docker-compose.thesis.yml run --rm experiments benchmark --sizes 64,128 --out /results
docker compose -f docker-compose.thesis.yml run --rm case-studies > results/replica/case_studies.log
docker compose -f docker-compose.thesis.yml run --rm analysis /results
```

Without Docker: `sbt "runMain core.simulation.thesis.RunThesisCampaign <campaign> --out <dir>"` with
`APP_LEGACY_DB=true APP_SKIP_DATABASE=false APP_LOCAL_MODE=true APP_SKIP_WS=true` and the `DB_*` / `DB_*_LEGACY`
variables of the `.env.example` pointing to **empty** databases (the runner also writes run summaries to the new
schema, `DB_NAME`). Then `pip install -r requirements.txt` and `python analyze_all.py <dir>`.

## Campaigns

Defined once in `core.simulation.thesis.ThesisCampaigns`; `ThesisCampaignsSuite` checks them against the dataset.

| Campaign | Alias | Runs | Thesis | Content |
|---|---|---|---|---|
| `benchmark` | | 160–195 | 6.2–6.3 | N = 64 … 16 384; static, Prop. C (pure creation), Prop. A, Prop. B; limit 500 |
| `extreme-distributions` | `phase1` | 196–200 | 6.7.1 | uniform baseline, bimodal 50/50, 10 % radical minority; N = 512 |
| `tolerance-fluidity:1..3` | `phase2` | 201–215 | 6.7.2 | intolerant hubs, absolute fluidity; three executions on different networks |
| `small-world` | `phase3` | reads 196–205 | 6.7.3 | analysis only: `small_world.py` |
| `consensus-resistance` | `phase4` | 216–218 | 6.7.4 | bimodal baseline, ablation P_create = 0, sub-critical τ = 0.02 |
| `case-studies` | | — | Ch. 5, Annex A | three hand-built networks × 9 specs × 20 seeds, no database |

All generated runs use Barabási–Albert networks (m = 4, parameter 2.5), half SOM− (majority, memoryless) and half
DeGroot agents, ε = 0.001, three networks per run and base seed 42.

## What is reproduced exactly

A network's seed is `42 + runId + k` (k = 1, 2, 3). Before each run, `RunThesisCampaign` sets the database sequence
so that the run receives its thesis id (`DatabaseManager.pinNextLegacyRunId`); without a database it uses the thesis
id directly. On a fresh database this rebuilds the thesis networks. It refuses to run if the ids already exist
(`--no-pin` runs a new sample instead).

Checked on 2026-10-20 against `promueva_legacy`, network by network (final round, consensus, every row of
`network_coevolution_metrics`, and the final topology with its weights):

| Runs | Initial beliefs | Result |
|---|---|---|
| 160–163 (benchmark, N = 64), 196, 199–200, 201–205 | uniform or radical minority | **identical** (36 networks, up to 261 632 edges and 200 rounds) |
| 197–198, 216–218 | bimodal | **different** from the thesis, identical between replicas: see below |
| Case studies (180 runs) | hand-built | **identical** (`case_studies_results.txt` and every per-round speaking state); re-run on 2026-10-26 with the ICTAC matrices and the mixed round seed, see `results/puyo/thesis_2026-10/README.md` |
| Tables and figures | — | `non_consensus_networks.csv` byte-identical; all 17 figures pixel-identical on the platform that drew them (Windows, Python 3.12, matplotlib 3.9.0); the Linux analysis container draws the same data with small text-rendering differences |

The same check inside Docker (`docker-compose.thesis.yml`, JRE 21) on a fresh database: runs 160–163, 172–175 (N = 64
and 512) and 201–205 gave the thesis consensus counts and rounds, the final topologies of 201–205 have exactly the
thesis edge counts, and `case_studies_results.txt` and the non-consensus rows are identical.

**The bimodal runs of the thesis are reproduced statistically, not exactly.** When the thesis data were produced,
`BimodalDistribution` sampled with its own unseeded `scala.util.Random`, unlike the other distributions, which use
the run's seeded generator. The exact initial beliefs of runs 197, 198 and 216–218 (15 of the 177 networks) are
therefore only in the database. Since 2026-10-20 `AgentProcessor` passes its seeded generator to
`BimodalDistribution`, so replicas of these campaigns are deterministic among themselves (two runs of
`consensus-resistance` give the same results), with the same consensus pattern as the thesis (3/3, 1/3, 0/3).

Floating-point sums in the SIMD belief update depend on the vector width of the CPU, so a different processor may
change a rounding near a rupture threshold (thesis Sec. 4.2.1).

## Scripts

| Script | Output |
|---|---|
| `analyze_all.py <results>` | Runs everything below that the folder allows |
| `non_consensus.py <first> <last> <out.csv>` | Networks without consensus, components of the final topology, pattern a/b/c |
| `small_world.py <results>` | Small-world metrics at T0 and T_final (phase 3) |
| `case_seeds.py <log> <out.json>` | Final state and speaking pattern per case study and seed |
| `figures/*.py` | One script per thesis figure; `<results_dir> <figures_dir>` |
| `thesis_db.py` | Connection (`DB_*_LEGACY`), campaign CSV reader, SCC |
