"""Extreme initial distributions (Phase 1, Sec. 5.5.1): fig_edge_case_phase1_assortativity.

usage: python extreme_distributions_assortativity.py <results_dir> <figures_dir>
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

query = f"""
SELECT 
    m.round_number, 
    m.assortativity, 
    m.fragmentation, 
    m.scc_count, 
    n.run_id, 
    n.id as network_id,
    n.final_round
FROM network_coevolution_metrics m
JOIN networks n ON m.network_id = n.id
WHERE n.run_id IN ({', '.join(map(str, P1))})
ORDER BY n.run_id, n.id, m.round_number;
"""

df = pd.read_sql(query, conn)
conn.close()

# Map run IDs to scenario labels
scenario_map = {
    P1[0]: "Línea Base: Uniforme U(0,1) (Prop. B)",
    P1[1]: "Config. 1A: Polarización Extrema (Homofílica, Prop. B)",
    P1[2]: "Config. 1A: Polarización Extrema (Uniforme, Prop. A)",
    P1[3]: "Config. 1B: Minoría Radical (Homofílica, Prop. B)",
    P1[4]: "Config. 1B: Minoría Radical (Uniforme, Prop. A)"
}

df['Scenario'] = df['run_id'].map(scenario_map)

# Color and style definitions matching existing thesis figures
palette = {
    P1[0]: "#2b5c8f", # Classic steel blue
    P1[1]: "#c0392b", # Crimson red for extreme polarization
    P1[2]: "#e67e22", # Orange for uniform polarization
    P1[3]: "#27ae60", # Emerald green for radical minority homophilic
    P1[4]: "#8e44ad"  # Purple for radical minority uniform
}

markers = {
    P1[0]: "o",
    P1[1]: "s",
    P1[2]: "^",
    P1[3]: "D",
    P1[4]: "v"
}

plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 5.5), dpi=300)

# Panel 1: Case 1A vs Baseline (Assortativity trajectories)
for rid in [P1[0], P1[2], P1[1]]:
    sub = df[df['run_id'] == rid]
    grp = sub.groupby('round_number')['assortativity'].agg(['mean', 'std']).reset_index()
    
    label = scenario_map[rid]
    color = palette[rid]
    marker = markers[rid]
    
    ax1.plot(grp['round_number'], grp['mean'], label=label, color=color, marker=marker, linewidth=2.2, markersize=5)
    if grp['std'].notnull().any():
        ax1.fill_between(grp['round_number'], 
                         np.clip(grp['mean'] - grp['std'].fillna(0), -0.1, 1.05),
                         np.clip(grp['mean'] + grp['std'].fillna(0), -0.1, 1.05),
                         color=color, alpha=0.15)

ax1.set_title(r"(a) Configuración 1A: Polarización Bimodal ($B \in \{0.05, 0.95\}$)", fontsize=12, fontweight="bold")
ax1.set_xlabel(r"Ronda de Simulación $t$", fontsize=11, fontweight="bold")
ax1.set_ylabel(r"Asortatividad de Opinión Dirigida $r^t$", fontsize=11, fontweight="bold")
ax1.set_ylim(-0.05, 1.05)
ax1.grid(True, linestyle="--", alpha=0.6)
ax1.legend(loc="upper right", frameon=True, fontsize=9.5)  # arriba queda libre

# Panel 2: Case 1B vs Baseline
for rid in [P1[0], P1[4], P1[3]]:
    sub = df[df['run_id'] == rid]
    grp = sub.groupby('round_number')['assortativity'].agg(['mean', 'std']).reset_index()
    
    label = scenario_map[rid]
    color = palette[rid]
    marker = markers[rid]
    
    ax2.plot(grp['round_number'], grp['mean'], label=label, color=color, marker=marker, linewidth=2.2, markersize=5)
    if grp['std'].notnull().any():
        ax2.fill_between(grp['round_number'], 
                         np.clip(grp['mean'] - grp['std'].fillna(0), -0.1, 1.05),
                         np.clip(grp['mean'] + grp['std'].fillna(0), -0.1, 1.05),
                         color=color, alpha=0.15)

ax2.set_title(r"(b) Configuración 1B: Mayoría Neutral (90%) vs. Minoría Radical (10%)", fontsize=12, fontweight="bold")
ax2.set_xlabel(r"Ronda de Simulación $t$", fontsize=11, fontweight="bold")
ax2.set_ylabel(r"Asortatividad de Opinión Dirigida $r^t$", fontsize=11, fontweight="bold")
ax2.set_ylim(-0.05, 0.65)
ax2.grid(True, linestyle="--", alpha=0.6)
ax2.legend(loc="upper right", frameon=True, fontsize=9.5)

plt.tight_layout()

# Save output to Thesis Notes directory
output_dir = OUT
pdf_path = f"{output_dir}/fig_edge_case_phase1_assortativity.pdf"
png_path = f"{output_dir}/fig_edge_case_phase1_assortativity.png"

plt.savefig(pdf_path, bbox_inches='tight')
plt.savefig(png_path, bbox_inches='tight')
plt.close()

print(f"Generated: {pdf_path}")
print(f"Generated: {png_path}")
