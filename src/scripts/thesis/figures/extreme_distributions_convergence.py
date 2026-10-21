"""Extreme initial distributions (Phase 1, Sec. 6.7.1): fig_edge_case_phase1_convergence.

usage: python extreme_distributions_convergence.py <results_dir> <figures_dir>
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
P1 = campaign_run_ids(os.path.join(RESULTS, 'edge_case_results_phase1.csv'))  # thesis: 196-200

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
WHERE n.run_id IN ({', '.join(map(str, P1))})
ORDER BY n.run_id, n.id;
"""

df_net = pd.read_sql(query_networks, conn)
conn.close()

# Map run IDs to scenario labels
scenario_map = {
    P1[0]: "Línea Base\n(Uniforme, Prop. B)",
    P1[1]: "Caso 1A: Bimodal\n(Homofílico, Prop. B)",
    P1[2]: "Caso 1A: Bimodal\n(Uniforme, Prop. A)",
    P1[3]: "Caso 1B: Minoría Rad.\n(Homofílico, Prop. B)",
    P1[4]: "Caso 1B: Minoría Rad.\n(Uniforme, Prop. A)"
}

df_net['Scenario'] = df_net['run_id'].map(scenario_map)

# Palette
colors = ["#2b5c8f", "#c0392b", "#e67e22", "#27ae60", "#8e44ad"]

plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
fig, ax = plt.subplots(figsize=(10, 5), dpi=300)

grp = df_net.groupby('run_id')['final_round'].agg(['mean', 'std', 'min', 'max']).reset_index()
grp = grp.merge(df_net.groupby('run_id')['consensus_reached'].sum().reset_index(), on='run_id')
grp['label'] = grp['run_id'].map(scenario_map)

bars = ax.bar(grp['label'], grp['mean'], yerr=grp['std'].fillna(0), capsize=6, 
              color=colors, alpha=0.85, edgecolor='black', linewidth=1.2, width=0.55)

# Add value labels on top of bars
for bar, mean_val, max_val, c_reached in zip(bars, grp['mean'], grp['max'], grp['consensus_reached']):
    height = bar.get_height()
    ax.annotate(f'{mean_val:.1f} rds\n(máx: {int(max_val)}; consenso {int(c_reached)}/3)',
                xy=(bar.get_x() + bar.get_width() / 2, height),
                xytext=(0, 8),  # 8 points vertical offset
                textcoords="offset points",
                ha='center', va='bottom', fontsize=9.5, fontweight='bold')

ax.set_title(r"Rondas Promedio de Simulación (redes con y sin consenso; $N = 512, m = 4, \epsilon = 0.001$)", fontsize=12, fontweight="bold")
ax.set_ylabel(r"Número de Rondas de Simulación", fontsize=11, fontweight="bold")
# rerun 2026-10-14: the limit follows the data and the labels show how many networks reached consensus
ax.set_ylim(0, max(32, (grp['mean'] + grp['std'].fillna(0)).max() * 1.2))
ax.grid(True, linestyle="--", alpha=0.6, axis='y')

plt.tight_layout()

output_dir = OUT
pdf_path = f"{output_dir}/fig_edge_case_phase1_convergence.pdf"
png_path = f"{output_dir}/fig_edge_case_phase1_convergence.png"

plt.savefig(pdf_path, bbox_inches='tight')
plt.savefig(png_path, bbox_inches='tight')
plt.close()

print(f"Generated: {pdf_path}")
print(f"Generated: {png_path}")
