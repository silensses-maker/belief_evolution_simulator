"""Asymmetric tolerance and absolute fluidity (Phase 2, Sec. 6.7.2), first execution:
fig_edge_case_phase2_convergence.

usage: python tolerance_fluidity_convergence.py <results_dir> <figures_dir>
  results_dir  folder with the campaign CSVs (run ids are read from them)
  figures_dir  output folder (PDF and PNG)
Database: thesis_db.connect() (DB_*_LEGACY environment variables; default localhost promueva_legacy).
Ported from the scripts that produced the thesis figures; only inputs and outputs were parameterized.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from thesis_db import connect, run_ids as campaign_run_ids  # noqa: E402

if len(sys.argv) != 3:
    sys.exit(__doc__)
RESULTS, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
P2 = campaign_run_ids(os.path.join(RESULTS, 'edge_case_results_phase2.csv'))  # thesis: 201-205

import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

# Connect to database
conn = connect()

query_networks = f"""
SELECT 
    n.id as network_id,
    n.run_id,
    n.final_round,
    n.simulation_outcome as consensus_reached
FROM networks n
WHERE n.run_id IN ({', '.join(map(str, P2))})
ORDER BY n.run_id, n.id;
"""

df_net = pd.read_sql(query_networks, conn)
conn.close()

# Scenario map
scenario_map = {
    P2[0]: "Línea Base\n(tau=0.10, Prop. B)",
    P2[1]: "Config. 2A: Hubs Intol.\n(Homofílico, Prop. B)",
    P2[2]: "Config. 2A: Hubs Intol.\n(Uniforme, Prop. A)",
    P2[3]: "Config. 2B: Fluidez Abs.\n(Homofílico, Prop. B)",
    P2[4]: "Config. 2B: Fluidez Abs.\n(Uniforme, Prop. A)"
}

colors = ["#2b5c8f", "#d35400", "#f39c12", "#16a085", "#8e44ad"]

plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
fig, ax = plt.subplots(figsize=(10, 5), dpi=300)

grp = df_net.groupby('run_id')['final_round'].agg(['mean', 'std', 'min', 'max']).reset_index()
grp = grp.merge(df_net.groupby('run_id')['consensus_reached'].sum().reset_index(), on='run_id')
grp['label'] = grp['run_id'].map(scenario_map)

bars = ax.bar(grp['label'], grp['mean'], yerr=grp['std'].fillna(0), capsize=6, 
              color=colors, alpha=0.85, edgecolor='black', linewidth=1.2, width=0.55)

for bar, mean_val, max_val, c_reached in zip(bars, grp['mean'], grp['max'], grp['consensus_reached']):
    height = bar.get_height()
    ax.annotate(f'{mean_val:.1f} rds\n(máx: {int(max_val)}; consenso {int(c_reached)}/3)',
                xy=(bar.get_x() + bar.get_width() / 2, height),
                xytext=(0, 8),
                textcoords="offset points",
                ha='center', va='bottom', fontsize=9.5, fontweight='bold')

ax.set_title(r"Rondas Promedio de Simulación (con y sin consenso): Tolerancia Asimétrica y Fluidez ($N = 512, m = 4$)", fontsize=12, fontweight="bold")
ax.set_ylabel(r"Número de Rondas de Simulación", fontsize=11, fontweight="bold")
# rerun 2026-10-10: the limit follows the data (some networks now stop at the iteration limit)
ax.set_ylim(0, max(14, (grp['mean'] + grp['std'].fillna(0)).max() * 1.15))
ax.grid(True, linestyle="--", alpha=0.6, axis='y')

plt.tight_layout()

output_dir = OUT
pdf_path = f"{output_dir}/fig_edge_case_phase2_convergence.pdf"
png_path = f"{output_dir}/fig_edge_case_phase2_convergence.png"

plt.savefig(pdf_path, bbox_inches='tight')
plt.savefig(png_path, bbox_inches='tight')
plt.close()

print(f"Generated: {pdf_path}")
print(f"Generated: {png_path}")
