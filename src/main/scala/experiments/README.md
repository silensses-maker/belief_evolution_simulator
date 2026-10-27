# experiments

Studies built on top of the simulator: campaign definitions, entry points and anything else that only one study
needs. The simulator itself (`core`, `io`, `utils`) never depends on this package.

| Package | Study | Entry point | Reproduction |
|---|---|---|---|
| `experiments.coevolution` | Coevolving SOM+/SOM− networks (J. E. Puyo Mora, Universidad del Valle, 2026) | `RunThesisCampaign` | [`src/scripts/thesis/README.md`](../../../scripts/thesis/README.md) |

## Adding a study

- One subpackage per study, named after its topic (`experiments.<topic>`), with its own entry point.
- Generic capabilities go in the simulator, behind options that are off by default. `CoevolutionConfig`,
  `CoevolutionEngine` and `DatabaseManager.pinNextLegacyRunId` are examples. Study-specific data and parameters
  stay in the subpackage.
- Keep the other artifacts of the study namespaced the same way: results under `results/<author>/<study>_<date>/`, a
  Docker stack as `docker-compose.<type>-<topic>.yml` with its own `name:`, so containers and volumes cannot collide.
- Once a study is published, treat its campaign definitions as frozen and tag the commit (e.g. `thesis-2026-10`). New
  experiments on the same topic go in a new object or subpackage, not in the published campaigns.
