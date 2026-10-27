"""Small-world evolution (Phase 3, Sec. 5.5.3): fig_edge_case_phase3_smallworld. Reads
<results_dir>/small_world_results.csv, written by ../small_world.py.

usage: python small_world.py <results_dir> <figures_dir>
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
P2 = campaign_run_ids(os.path.join(RESULTS, 'edge_case_results_phase2.csv'))  # thesis: 201-205

import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

# Load data
csv_path = os.path.join(RESULTS, 'small_world_results.csv')
df = pd.read_csv(csv_path)

# Runs 200 and 203 (Prop. A of Cases 1B and 2A) are left out: two of their three networks are the same networks as in
# runs 199 and 202 (consecutive seeds), with identical results, and the third differs by less than 1%.
# 196: Línea Base Fase 1    197/198: Config. 1A (Prop. B / A)    199: Config. 1B (Prop. B)
# 201: Línea Base Fase 2    202: Config. 2A (Prop. B)            204/205: Config. 2B (Prop. B / A)
selected_runs = [P1[0], P1[1], P1[2], P1[3], P2[0], P2[1], P2[3], P2[4]]
label_map = {
    P1[0]: "Línea Base\nFase 1 (B)",
    P1[1]: "Config. 1A\nProp. B",
    P1[2]: "Config. 1A\nProp. A",
    P1[3]: "Config. 1B\nProp. B",
    P2[0]: "Línea Base\nFase 2 (B)",
    P2[1]: "Config. 2A\nProp. B",
    P2[3]: "Config. 2B\nProp. B",
    P2[4]: "Config. 2B\nProp. A"
}

df_sub = df[df['run_id'].isin(selected_runs)].copy()
df_sub['label'] = df_sub['run_id'].map(label_map)

# Summary by run and round_type
grp = df_sub.groupby(['run_id', 'label', 'round_type']).agg({
    'C': ['mean', 'std'],
    'L': ['mean', 'std'],
    'k_avg': ['mean', 'std'],
    'sigma': ['mean', 'std']
}).reset_index()

# Flatten multiindex
grp.columns = ['run_id', 'label', 'round_type', 'C_mean', 'C_std', 'L_mean', 'L_std', 'k_mean', 'k_std', 'sigma_mean', 'sigma_std']

# Split T0 and Tfinal
t0 = grp[grp['round_type'] == 'T0'].sort_values('run_id')
tf = grp[grp['round_type'] == 'Tfinal'].sort_values('run_id')

labels = t0['label'].values
x = np.arange(len(labels))
width = 0.35

plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(11, 13), dpi=300)

# --- Subplot 1: Clustering Coefficient C ---
rects1 = ax1.bar(x - width/2, t0['C_mean'], width, yerr=t0['C_std'].fillna(0),
                 label=r'Inicial ($T_0$)', color='#3498db', alpha=0.85, edgecolor='black', capsize=4)
rects2 = ax1.bar(x + width/2, tf['C_mean'], width, yerr=tf['C_std'].fillna(0),
                 label=r'Final ($T_{final}$)', color='#e74c3c', alpha=0.85, edgecolor='black', capsize=4)

ax1.set_title(r"(a) Coeficiente de Clustering $C$", fontsize=12, fontweight='bold')
ax1.set_ylabel(r"Clustering Promedio $C$", fontsize=11, fontweight='bold')
ax1.set_xticks(x)
ax1.set_xticklabels(labels, fontsize=9)
ax1.legend(loc='upper left', frameon=True)
ax1.set_ylim(0, 1.2)
ax1.grid(True, linestyle='--', alpha=0.6, axis='y')

# Annotations on C
for i, rect in enumerate(rects2):
    h = rect.get_height()
    err = tf['C_std'].fillna(0).iloc[i]
    ax1.annotate(f"{h:.3f}", xy=(rect.get_x() + rect.get_width()/2, h + err),
                 xytext=(0, 4), textcoords="offset points", ha='center', va='bottom', fontsize=8, fontweight='bold')

# --- Subplot 2: Average Shortest Path Length L ---
rects3 = ax2.bar(x - width/2, t0['L_mean'], width, yerr=t0['L_std'].fillna(0),
                 label=r'Inicial ($T_0$)', color='#2ecc71', alpha=0.85, edgecolor='black', capsize=4)
rects4 = ax2.bar(x + width/2, tf['L_mean'], width, yerr=tf['L_std'].fillna(0),
                 label=r'Final ($T_{final}$)', color='#f39c12', alpha=0.85, edgecolor='black', capsize=4)

ax2.set_title(r"(b) Longitud de Camino Promedio $L$", fontsize=12, fontweight='bold')
ax2.set_ylabel(r"Distancia Promedio $L$ (saltos)", fontsize=11, fontweight='bold')
ax2.set_xticks(x)
ax2.set_xticklabels(labels, fontsize=9)
ax2.legend(loc='upper right', frameon=True)
ax2.grid(True, linestyle='--', alpha=0.6, axis='y')
ax2.set_ylim(0, 3.8)

# Annotations on L
for i, rect in enumerate(rects4):
    h = rect.get_height()
    err = tf['L_std'].fillna(0).iloc[i]
    ax2.annotate(f"{h:.2f}", xy=(rect.get_x() + rect.get_width()/2, h + err),
                 xytext=(0, 4), textcoords="offset points", ha='center', va='bottom', fontsize=8, fontweight='bold')

# --- Subplot 3: Small-World Index sigma, initial and final ---
bars = ax3.bar(x - width/2, t0['sigma_mean'], width, yerr=t0['sigma_std'].fillna(0),
               label=r'Inicial ($T_0$)', color='#9b59b6', alpha=0.85, edgecolor='black', capsize=4)
bars_f = ax3.bar(x + width/2, tf['sigma_mean'], width, yerr=tf['sigma_std'].fillna(0),
                 label=r'Final ($T_{final}$)', color='#95a5a6', alpha=0.85, edgecolor='black', capsize=4)
ax3.axhline(1.0, color='red', linestyle='--', linewidth=1.5, label=r'Umbral de Mundo Pequeño ($\sigma = 1.0$)')

ax3.set_title(r"(c) Coeficiente de Mundo Pequeño $\sigma$", fontsize=12, fontweight='bold')
ax3.set_ylabel(r"Índice $\sigma = \frac{C/C_{rand}}{L/L_{rand}}$", fontsize=11, fontweight='bold')
ax3.set_xticks(x)
ax3.set_xticklabels(labels, fontsize=9)
ax3.legend(loc='upper center', ncol=3, frameon=True)
ax3.grid(True, linestyle='--', alpha=0.6, axis='y')
ax3.set_ylim(0, 4.4)

for series, col in ((bars, t0), (bars_f, tf)):
    for i, bar in enumerate(series):
        h = bar.get_height()
        err = col['sigma_std'].fillna(0).iloc[i]
        ax3.annotate(f"{h:.2f}", xy=(bar.get_x() + bar.get_width()/2, h + err),
                     xytext=(0, 4), textcoords="offset points", ha='center', va='bottom', fontsize=8, fontweight='bold',
                     bbox=dict(boxstyle='round,pad=0.1', fc='white', ec='none', alpha=0.85))

plt.tight_layout()

output_dir = OUT
pdf_path = f"{output_dir}/fig_edge_case_phase3_smallworld.pdf"
png_path = f"{output_dir}/fig_edge_case_phase3_smallworld.png"

plt.savefig(pdf_path, bbox_inches='tight')
plt.savefig(png_path, bbox_inches='tight')
plt.close()

print(f"Generated: {pdf_path}")
print(f"Generated: {png_path}")
