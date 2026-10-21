"""Asymmetric tolerance and absolute fluidity (Phase 2, Sec. 6.7.2), first execution:
fig_edge_case_phase2_tolerance.

usage: python tolerance_fluidity_assortativity.py <results_dir> <figures_dir>
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
WHERE n.run_id IN ({', '.join(map(str, P2))})
ORDER BY n.run_id, n.id, m.round_number;
"""

df = pd.read_sql(query, conn)
conn.close()

# Scenario map
scenario_map = {
    P2[0]: "Línea Base: Tolerancia Homogénea (tau=0.10, Prop. B)",
    P2[1]: "Caso 2A: Hubs Intolerantes (Homofílico, Prop. B)",
    P2[2]: "Caso 2A: Hubs Intolerantes (Uniforme, Prop. A)",
    P2[3]: "Caso 2B: Fluidez Absoluta (Homofílica, Prop. B)",
    P2[4]: "Caso 2B: Fluidez Absoluta (Uniforme, Prop. A)"
}

palette = {
    P2[0]: "#2b5c8f", # Steel blue
    P2[1]: "#d35400", # Rust orange
    P2[2]: "#f39c12", # Gold orange
    P2[3]: "#16a085", # Teal
    P2[4]: "#8e44ad"  # Purple
}

markers = {
    P2[0]: "o",
    P2[1]: "s",
    P2[2]: "^",
    P2[3]: "D",
    P2[4]: "v"
}

plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 5.5), dpi=300)

# Panel 1: Case 2A (Intolerant Hubs vs Baseline)
for rid in [P2[0], P2[1], P2[2]]:
    sub = df[df['run_id'] == rid]
    grp = sub.groupby('round_number')['assortativity'].agg(['mean', 'std']).reset_index()
    
    label = scenario_map[rid]
    color = palette[rid]
    marker = markers[rid]
    
    ax1.plot(grp['round_number'], grp['mean'], label=label, color=color, marker=marker, linewidth=2.2, markersize=5)
    if grp['std'].notnull().any():
        ax1.fill_between(grp['round_number'], 
                         np.clip(grp['mean'] - grp['std'].fillna(0), -0.05, 1.0),
                         np.clip(grp['mean'] + grp['std'].fillna(0), -0.05, 1.0),
                         color=color, alpha=0.15)

ax1.set_title(r"(a) Caso 2A: Hubs Intolerantes ($\tau_{\text{hub}}=0.01$) vs. Periferia ($\tau_{\text{perif}}=0.50$)", fontsize=12, fontweight="bold")
ax1.set_xlabel(r"Ronda de Simulación $t$", fontsize=11, fontweight="bold")
ax1.set_ylabel(r"Asortatividad de Opinión Dirigida $r^t$", fontsize=11, fontweight="bold")
ax1.set_ylim(-0.05, 1.05)  # rerun 2026-10-14: r reaches 1.0 in echo chambers
ax1.grid(True, linestyle="--", alpha=0.6)
ax1.legend(loc="upper right", frameon=True, fontsize=9.5)

# Panel 2: Case 2B (Absolute Fluidity)
for rid in [P2[0], P2[3], P2[4]]:
    sub = df[df['run_id'] == rid]
    grp = sub.groupby('round_number')['assortativity'].agg(['mean', 'std']).reset_index()
    
    label = scenario_map[rid]
    color = palette[rid]
    marker = markers[rid]
    
    ax2.plot(grp['round_number'], grp['mean'], label=label, color=color, marker=marker, linewidth=2.2, markersize=5)
    if grp['std'].notnull().any():
        ax2.fill_between(grp['round_number'], 
                         np.clip(grp['mean'] - grp['std'].fillna(0), -0.05, 1.0),
                         np.clip(grp['mean'] + grp['std'].fillna(0), -0.05, 1.0),
                         color=color, alpha=0.15)

ax2.set_title(r"(b) Caso 2B: Régimen de Fluidez Absoluta ($P_{\text{break}} = 1.0, P_{\text{create}} = 1.0$)", fontsize=12, fontweight="bold")
ax2.set_xlabel(r"Ronda de Simulación $t$", fontsize=11, fontweight="bold")
ax2.set_ylabel(r"Asortatividad de Opinión Dirigida $r^t$", fontsize=11, fontweight="bold")
ax2.set_ylim(-0.05, 1.05)
ax2.grid(True, linestyle="--", alpha=0.6)
ax2.legend(loc="upper right", frameon=True, fontsize=9.5)

plt.tight_layout()

# Save output to Thesis Notes directory
output_dir = OUT
pdf_path = f"{output_dir}/fig_edge_case_phase2_tolerance.pdf"
png_path = f"{output_dir}/fig_edge_case_phase2_tolerance.png"

plt.savefig(pdf_path, bbox_inches='tight')
plt.savefig(png_path, bbox_inches='tight')
plt.close()

print(f"Generated: {pdf_path}")
print(f"Generated: {png_path}")
