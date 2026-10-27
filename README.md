# Opinion Dynamics Simulator (SiLEnSeSS)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A scalable, high-performance simulator for studying opinion dynamics in social networks considering different psychosocial phenomena. 
This implementation is based on our paper *"The Spiral of Silence in Multi-Agent DeGroot models"* where we examine how silence behaviors impact consensus formation in social networks, extended with an **adaptive coevolutionary network engine**.

## Overview

### Foundational Models:

- **Classical DeGroot**: Agents update their opinions by considering the weighted average of their neighbors.
- **FJ model (WIP)**: Agents update their opinions by considering their starting opinion and the weighted average of their neighbors, both weighted by some constant.
- **Bounded confidence (WIP)**: Agents update their opinions by only considering the weighted average of their neighbors inside a confidence range.

### Spiral of Silence Related Models:

- **Silence Opinion Memoryless (SOM-)**: Agents update their opinions by considering only non-silent neighbors' opinions. Silent agents are excluded from the opinion update process.
- **Silence Opinion Memory-based (SOM+)**: Agents update their opinions considering all neighbors, but for silent neighbors, only their most recent expressed opinion is used.
- **Confidence Silence Opinion Memoryless (CSOM-)**: Agents update their opinions by considering only non-silent neighbors' opinions. Silent agents are excluded from the opinion update process.
- **Confidence Silence Opinion Memory-based (CSOM+)**: Agents update their opinions considering all neighbors, but for silent neighbors, only their most recent expressed opinion is used.

These models capture the *"Spiral of Silence"* theory from political science, describing how individuals may withhold their opinions when they perceive themselves to be in the minority.

### Cognitive Biases:

Each agent can also have a different cognitive bias for each neighbor:

- **Authority Bias**: Agents blindly follow perceived authorities, adjusting their opinion maximally towards the authority's stance, ignoring the actual magnitude of disagreement.
- **Backfire Effect**: Agents react to disagreement, especially significant disagreement, by strengthening their original position, effectively moving away from the influencer's opinion.
- **Confirmation Bias**: Agents are more receptive to opinions closer to their own and pay less attention to or discount opinions that are significantly different.
- **Insular**: Agents completely ignore the opinions of others, remaining stubborn or closed-minded.

### Coevolutionary Opinion Dynamics (Adaptive Networks):

In addition to static graphs, the simulator supports **dynamic coevolutionary topologies** where the underlying social network rewires adaptively based on agent opinions and silencing dynamics:

Each round, for every agent $i$ (`core.simulation.topology.CoevolutionEngine.evolveTopology`):

- **Rupture.**
  - A visible neighbour $j$ is compared with $i$: a speaking neighbour by its belief, a silent SOM+ neighbour by its last public opinion. A silent SOM- neighbour is not evaluated.
  - If the distance $\hat{d}_{ij}^t$ exceeds $\tau_i$, the edge breaks with probability $P_{\text{break}} \cdot g_{ji}^t$, where $g_{ji}^t = 1 - e^{1 - \hat{d}_{ij}^t/\tau_i}$.
- **Bounded creation.** Every non-neighbour $k$ with $|B_k - B_i| \le \tau_i$ becomes a new contact with probability $P_{\text{create}}$.
- **Redistribution of the lost influence $W_{\text{lost}}$.** It preserves the row-stochastic invariant; self-influence is implicit, $I_{ii} = 1 - \sum_{j \ne i} I_{ji}$.
  - **Proposal A (uniform):** the lost weight is split equally among the new contacts.
  - **Proposal B (homophilic):** the lost weight is split in proportion to the similarity $1 - |B_k - B_i|$.
  - **Proposal C (pure creation):** nothing was lost but contacts were created, so the whole neighbourhood is renormalized by similarity, including the implicit self-influence with similarity 1.
  - **Isolation rule:** weight was lost and no candidate exists, so the lost weight returns to the implicit self-influence. No self-edge is created.

Static runs (no `CoevolutionConfig`) never call the engine, so the original SOM models behave exactly as before.

**Tolerance used by the coevolution rules.**
- Each agent has a tolerance radius $r_i$ and an offset $o_i$. Generated networks default to $r = 0.10$ and $o = 0.05$, and `asymmetricTolerance` uses $o = r/2$.
- The original SOM silence test accepts a neighbour when $B_j - B_i \in [o_i - r_i,\ o_i + r_i]$. With the defaults that is $[-0.05, 0.15]$, an asymmetric window. This is SiLEnSeSS's own behaviour and is left unchanged.
- Rupture and creation use the symmetric radius $	au_i = r_i$, which the coevolution hook recovers from the window bounds (`CoevolutionEngine.toleranceRadii`).
- Before 2026-10-14 the hook passed the window's upper bound $o_i + r_i$ instead, so the effective tolerance was 1.5 × the configured radius.

### Stopping Rules

A network stops at the first of:

- consensus, when $\max B - \min B < \epsilon$;
- the iteration limit, which counts as dissensus;
- stability, also dissensus: no belief changed by more than $\epsilon/100$ in two consecutive rounds. This rule comes from the original simulator.
- optional structural stops: `assortivityStopThreshold` (stop when $r^t$ reaches it) and `fragmentationStop` (stop when $\Phi_t > 0$).

### Structural Convergence Metrics:

During coevolutionary runs, the engine computes per-round graph invariants persisted to the relational database (`network_coevolution_metrics`):

- **Weighted Opinion Assortativity ($r^t \in [-1, 1]$):** a weighted Pearson correlation of emitter and receiver beliefs over the influence edges $j \ne i$. It adapts Newman's (2003) scalar assortativity to directed, weighted influence matrices; with a symmetric matrix it reduces to Eq. 6 of Djurdjevac Conrad et al. (2024). It is NaN at consensus.
- **Normalized Fragmentation Index ($\Phi_t \in [0, 1]$):** Measures macro-level network disintegration $\Phi_t = \frac{\kappa(G_t) - 1}{N - 1}$.
- **Component Count ($\kappa(G_t)$):** Number of strongly connected components identified at each iteration.

---

## Features

- Simulate opinion dynamics in networks of up to 134* million agents ($2^{27}$)
- Coevolutionary rewiring tested up to 16,384 agents. A full benchmark run at that size takes about 26–34 s without database persistence and 41–200 s with it. The candidate search is $O(N)$ per agent and the network densifies, so the cost grows roughly quadratically.
- Generate networks with small-world properties and power-law degree distributions
- Parallel computation support via Scala and Akka Actors
- Configurable parameters:
  - Tolerance radius & majority threshold
  - Creation & break probabilities ($P_{\text{create}}$, $P_{\text{break}}$)
  - Cognitive biases & silence strategies
  - Initial opinion distributions
- Comprehensive results logging and per-round structural metrics persistence

\* The max number of agents varies depending on agent type and system RAM (64GB in our test case).  
\*\* The current hard limit of agent network size is $2^{31} - 1$ (2,147,483,647) using 32-bit integer indexing.

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/) (v2 recommended)
- A [Firebase](https://firebase.google.com/) project with **Authentication** enabled (if running in authenticated server mode)
- The Firebase service account JSON downloaded from **Firebase Console → Project Settings → Service Accounts → Generate new private key**

---

## Setup with Docker

### 1. Clone the repository

```bash
git clone https://github.com/YOUR_USERNAME/belief_evolution_simulator.git
cd belief_evolution_simulator
```

### 2. Create the `.env` file

Copy the template below into a file named `.env` at the project root and fill in your environment variables:

```dotenv
# ── PostgreSQL ────────────────────────────────────────────────────────────────
POSTGRES_DB=promueva
POSTGRES_USER=postgres
POSTGRES_PASSWORD=<choose-a-strong-password>

POSTGRES_DB_LEGACY=promueva_legacy
POSTGRES_USER_LEGACY=postgres
POSTGRES_PASSWORD_LEGACY=<choose-a-strong-password>

# ── Backend → DB ──────────────────────────────────────────────────────────────
DB_HOST=postgres
DB_PORT=5432
DB_HOST_LEGACY=postgres
DB_PORT_LEGACY=5432

# ── Server ────────────────────────────────────────────────────────────────────
SERVER_HOST=0.0.0.0
SERVER_PORT=9000

# ── App flags ─────────────────────────────────────────────────────────────────
APP_SKIP_DATABASE=false
APP_SERVER_MODE=true
APP_LOCAL_MODE=false
APP_LEGACY_DB=false
APP_SERVER_LOGS=false
APP_GENERAL_LOGS=true
APP_SIMULATION_LOGS=false
APP_SKIP_WS=false

# ── Firebase ──────────────────────────────────────────────────────────────────
GOOGLE_APPLICATION_CREDENTIALS=/secrets/firebase-sa.json
FIREBASE_PROJECT_ID=<your-firebase-project-id>
BOOTSTRAP_ADMIN_EMAILS=<your-email@domain.com>
FIREBASE_SA_HOST_PATH=/absolute/path/to/firebase-sa.json
```

### 3. Build and start the stack

```bash
docker compose down          # stop any old containers
docker compose build         # compile the Scala app
docker compose up -d         # start postgres + backend in the background
```

Watch the logs to confirm startup:

```bash
docker compose logs -f backend
```

### 4. Database Migrations

Database migrations run **automatically and idempotently** on every startup via `db/init_db.sh`:

| File | What it does |
|---|---|
| `db/init/schema.sql` | Base schema for the primary DB (`promueva`) |
| `db/init/legacy_schema.sql` | Base schema for the legacy DB (`promueva_legacy`) |
| `db/init/migrations/*.sql` | Incremental migrations (lifecycle status, coevolution metrics `006_coevolution_metrics.sql`, etc.) |

### 5. API Documentation

- **Swagger UI:** `http://localhost:9000/docs`
- **OpenAPI Specification:** `http://localhost:9000/openapi.yaml`

---

## Running Simulations

### CLI Mode (Standalone)

You can launch the CLI directly via SBT:

```bash
sbt run
```

#### Commands:
- `help`: Display list of available commands and parameters.
- `run [numNetworks] [numAgents] [density] [iterationLimit] [stopThreshold] [saveMode]`: Execute procedural network generation and simulation.
  - Example: `run 10 50 5 1000 0.001 standard`
- `run-specific`: Interactively configure a custom network agent-by-agent with individual biases, tolerance, and silence strategies.
- `exit` / `quit` / `q`: Close the simulator.

---

## Reproducibility & Empirical Benchmarks

Studies built on the simulator live in `src/main/scala/experiments/`, one subpackage per study; its
[README](src/main/scala/experiments/README.md) describes the layout for adding one.

The experiments of J. E. Puyo's thesis are defined once in `experiments.coevolution.ThesisCampaigns` and run with a
single entry point. The whole pipeline (campaigns on a fresh database, case studies, tables and figures) runs in
Docker:

```bash
src/scripts/thesis/reproduce.sh                                          # everything
docker compose -f docker-compose.thesis-coevolution.yml run --rm experiments list    # the campaigns and their run ids
```

or with sbt against a PostgreSQL of your own:

```bash
sbt "runMain experiments.coevolution.RunThesisCampaign list"
sbt "runMain experiments.coevolution.RunThesisCampaign benchmark --out results/replica"
```

| Campaign | Alias | Run ids | Thesis |
|---|---|---|---|
| `benchmark` (N = 64 … 16 384, four regimes) | | 160–195 | Sec. 5.3–5.4 |
| `extreme-distributions` | `phase1` | 196–200 | Sec. 5.5.1 |
| `tolerance-fluidity[:1\|2\|3]` (three executions) | `phase2` | 201–215 | Sec. 5.5.2 |
| `small-world` (analysis, no simulation) | `phase3` | reads 196–205 | Sec. 5.5.3 |
| `consensus-resistance` | `phase4` | 216–218 | Sec. 5.5.4 |
| `case-studies` (no database) | | — | Sec. 5.1, Annex A |

Each network's seed is `seed + runId + k`. The runner pins every run to its thesis run id, so on a fresh database it
rebuilds the networks of the thesis; see [`src/scripts/thesis/README.md`](src/scripts/thesis/README.md) for what is
reproduced exactly and what is not. The thesis dataset (run ids 160–218, October 2026) is in
[`results/puyo/thesis_2026-10/`](results/puyo/thesis_2026-10/). Each author keeps their datasets under `results/<author>/`.

## Tests

```bash
sbt test
```

`CoevolutionEngineSuite` and `ConvergenceMetricsSuite` check:

- the rupture and silence rules;
- Proposals A, B and C, and the isolation rule;
- the row-stochastic invariant over random networks;
- the assortativity coefficient, including its reduction to Djurdjevac Conrad et al.'s Eq. 6;
- the SCC count and the fragmentation index.

`ThesisCampaignsSuite` checks that the campaign catalogue matches the thesis dataset run by run (ids, scenarios,
population sizes).

---

## Citation

If you use this simulator in your research, please cite the paper:

```bibtex
@inproceedings{aranda2025spiral,
  title     = {The Spiral of Silence in Multi-Agent Models for Opinion Formation},
  author    = {Jes{\'u}s Aranda and Juan Francisco D{\'i}az and David Gaona and Frank Valencia},
  booktitle = {Theoretical Aspects of Computing -- ICTAC 2025},
  series    = {Lecture Notes in Computer Science},
  pages     = {417--434},
  year      = {2025},
  publisher = {Springer},
  doi       = {10.1007/978-3-032-11176-0_24},
  note      = {Preprint: arXiv:2410.19685}
}
```

The coevolutionary extension (adaptive topology, Proposals A/B/C, structural metrics) was developed by Julián Ernesto Puyo Mora as an undergraduate thesis at Universidad del Valle (EISC), *Extensión de los Modelos de Evolución de la Opinión SOM+ y SOM− a Redes Coevolutivas* (2026).

## License

This project is licensed under the MIT License - see the [LICENSE](https://opensource.org/licenses/MIT) file for details.
