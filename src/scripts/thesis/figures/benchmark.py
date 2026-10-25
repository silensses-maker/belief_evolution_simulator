"""Benchmark figures (thesis Sec. 6.2-6.3): fig_benchmark_scalability, fig_coevolution_assortativity,
fig_coevolution_fragmentation_scc. Panel (b) of the scalability figure averages only the networks that reached
consensus and labels the points where fewer than 3 of 3 did.

usage: python benchmark.py <results_dir> <figures_dir>
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


import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import pandas as pd
import numpy as np

output_dir = OUT

# 1. Parse CSV
lines = open(os.path.join(RESULTS, 'parametric_results.csv'), encoding='utf-8').readlines()[1:]
records = []
for line in lines:
    line = line.strip()
    if not line: continue
    parts = line.split(',')
    records.append({
        'PopulationSize': int(parts[0]),
        'Scenario': ','.join(parts[1:-4]),
        'RunID': int(parts[-4]),
        'TotalDurationMs': int(parts[-3]),
        'ConsensusCount': int(parts[-2]),
        'AvgRounds': float(parts[-1])
    })
df_csv = pd.DataFrame(records)
# Labels as in Table 6.2 of the thesis (the CSV keeps the runner's English scenario names)
LABELS = {
    'Baseline (Static)': 'Baseline (Estática)',
    'Scenario A (Pure Creation, Prop C)': 'Escenario A (Creación Pura, Prop. C)',
    'Scenario B (Uniform, Prop A)': 'Escenario B (Uniforme, Prop. A)',
    'Scenario B (Homophilic, Prop B)': 'Escenario B (Homofílico, Prop. B)',
}

palette = {
    'Baseline (Static)': '#1f77b4',
    'Scenario A (Pure Creation, Prop C)': '#2ca02c',
    'Scenario B (Uniform, Prop A)': '#ff7f0e',
    'Scenario B (Homophilic, Prop B)': '#d62728'
}
markers = {
    'Baseline (Static)': 'o',
    'Scenario A (Pure Creation, Prop C)': 's',
    'Scenario B (Uniform, Prop A)': '^',
    'Scenario B (Homophilic, Prop B)': 'D'
}

# FIGURE 1: Scalability & Convergence Rounds
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5.5), dpi=300)

for scenario in df_csv['Scenario'].unique():
    sub = df_csv[df_csv['Scenario'] == scenario].sort_values('PopulationSize')
    ax1.plot(sub['PopulationSize'], sub['TotalDurationMs'], 
             label=LABELS[scenario], color=palette[scenario], marker=markers[scenario], 
             linewidth=2.2, markersize=7)

ax1.set_xscale('log', base=2)
ax1.set_yscale('log')
ax1.set_xlabel('Tamaño de Población N (Escala log₂)', fontsize=11, fontweight='bold')
ax1.set_ylabel('Tiempo Total de Ejecución (ms, Escala log₁₀)', fontsize=11, fontweight='bold')
ax1.set_title('(a) Escalabilidad Computacional (3 redes por configuración)', fontsize=12, fontweight='bold')
ax1.set_xticks(df_csv['PopulationSize'].unique())
ax1.get_xaxis().set_major_formatter(ticker.ScalarFormatter())
ax1.grid(True, which='both', linestyle='--', alpha=0.5)
ax1.legend(loc='upper left', frameon=True, fontsize=9)

_c = connect()
_cur = _c.cursor()
_cur.execute('select run_id, final_round, simulation_outcome from networks where run_id = any(%s)', (list(map(int, df_csv['RunID'])),))
_per = pd.DataFrame(_cur.fetchall(), columns=['RunID', 'final_round', 'consensus']); _c.close()
_agg = _per.groupby('RunID').apply(lambda g: pd.Series({'n_cons': int(g['consensus'].sum()), 'n': len(g),
       'rounds_cons': g.loc[g['consensus'], 'final_round'].median(),
       'rounds_min': g.loc[g['consensus'], 'final_round'].min(),
       'rounds_max': g.loc[g['consensus'], 'final_round'].max()})).reset_index()
df_csv = df_csv.merge(_agg, on='RunID')
# small horizontal offset per scenario (log2 units) so that the min-max bars do not overlap
DODGE = {'Baseline (Static)': -0.09, 'Scenario A (Pure Creation, Prop C)': -0.03,
         'Scenario B (Uniform, Prop A)': 0.03, 'Scenario B (Homophilic, Prop B)': 0.09}
for scenario in df_csv['Scenario'].unique():
    sub = df_csv[df_csv['Scenario'] == scenario].sort_values('PopulationSize')
    sub = sub.assign(x=sub['PopulationSize'] * 2 ** DODGE[scenario])
    for _, _r in sub[sub['n_cons'] < sub['n']].iterrows():
        ax2.plot(_r['x'], _r['rounds_cons'], marker=markers[scenario], markersize=13,
                 markerfacecolor='none', markeredgecolor=palette[scenario], markeredgewidth=1.6, linestyle='none')
        ax2.annotate(f"{int(_r['n_cons'])}/{int(_r['n'])}", (_r['x'], _r['rounds_cons']),
                     textcoords='offset points', xytext=(7, 7), fontsize=8.5, fontweight='bold', color=palette[scenario])
    ax2.errorbar(sub['x'], sub['rounds_cons'],
                 yerr=[sub['rounds_cons'] - sub['rounds_min'], sub['rounds_max'] - sub['rounds_cons']],
                 label=LABELS[scenario], color=palette[scenario], marker=markers[scenario],
                 linewidth=2.2, markersize=7, capsize=3, elinewidth=1, alpha=0.95)

ax2.set_xscale('log', base=2)
ax2.set_xlabel('Tamaño de Población N (Escala log₂)', fontsize=11, fontweight='bold')
ax2.set_ylabel('Rondas hasta el consenso (mediana)', fontsize=11, fontweight='bold')
ax2.set_title('(b) Rondas hasta el Consenso (mediana; barras: mín.–máx.)', fontsize=12, fontweight='bold')
ax2.set_xticks(df_csv['PopulationSize'].unique())
ax2.get_xaxis().set_major_formatter(ticker.ScalarFormatter())
ax2.grid(True, which='both', linestyle='--', alpha=0.5)
ax2.set_ylim(6, 44)
ax2.legend(loc='upper left', frameon=True, fontsize=8.5, ncol=2)

plt.tight_layout()
plt.savefig(f'{output_dir}/fig_benchmark_scalability.png', bbox_inches='tight')
plt.savefig(f'{output_dir}/fig_benchmark_scalability.pdf', bbox_inches='tight')
plt.close()

# 2. Query Postgres for Metrics
conn = connect()
df_metrics = pd.read_sql('''
    SELECT r.id as run_id, n.number_of_agents as n_agents, m.network_id, m.round_number, m.assortativity, m.fragmentation, m.scc_count
    FROM network_coevolution_metrics m
    JOIN networks n ON m.network_id = n.id
    JOIN runs r ON n.run_id = r.id
    ORDER BY r.id, m.network_id, m.round_number
''', conn)
conn.close()

run_to_scenario = dict(zip(df_csv['RunID'], df_csv['Scenario']))
df_metrics['Scenario'] = df_metrics['run_id'].map(run_to_scenario)

# FIGURE 2: Assortativity Trajectories across Scales
fig, axes = plt.subplots(1, 3, figsize=(16, 5), dpi=300, sharey=True)
target_sizes = [512, 2048, 16384]

for idx, N in enumerate(target_sizes):
    ax = axes[idx]
    sub_metrics = df_metrics[df_metrics['n_agents'] == N]
    
    for scenario in ['Scenario A (Pure Creation, Prop C)', 'Scenario B (Uniform, Prop A)', 'Scenario B (Homophilic, Prop B)']:
        scen_data = sub_metrics[sub_metrics['Scenario'] == scenario]
        if scen_data.empty: continue
        
        grouped = scen_data.groupby('round_number')['assortativity'].agg(['mean', 'std']).reset_index()
        ax.plot(grouped['round_number'], grouped['mean'], label=LABELS[scenario], 
                color=palette[scenario], marker=markers[scenario], linewidth=2)
        if grouped['std'].notnull().any():
            ax.fill_between(grouped['round_number'], 
                            grouped['mean'] - grouped['std'].fillna(0), 
                            grouped['mean'] + grouped['std'].fillna(0), 
                            color=palette[scenario], alpha=0.15)
            
    ax.set_title(f'N = {N} Agentes', fontsize=12, fontweight='bold')
    ax.set_xlabel('Ronda de Simulación t', fontsize=11, fontweight='bold')
    if idx == 0:
        ax.set_ylabel('Asortatividad de Opinión rᵗ', fontsize=11, fontweight='bold')
    ax.grid(True, linestyle='--', alpha=0.5)
    ax.set_ylim(-0.15, 0.95)
    if idx == 2:
        ax.legend(loc='upper right', frameon=True, fontsize=8.5)

plt.tight_layout()
plt.savefig(f'{output_dir}/fig_coevolution_assortativity.png', bbox_inches='tight')
plt.savefig(f'{output_dir}/fig_coevolution_assortativity.pdf', bbox_inches='tight')
plt.close()

# FIGURE 3: SCC and Fragmentation over Time
fig, (ax_scc, ax_frag) = plt.subplots(1, 2, figsize=(14, 5), dpi=300)

for scenario in ['Scenario A (Pure Creation, Prop C)', 'Scenario B (Uniform, Prop A)', 'Scenario B (Homophilic, Prop B)']:
    scen_data = df_metrics[(df_metrics['n_agents'] == 4096) & (df_metrics['Scenario'] == scenario)]
    if scen_data.empty: continue
    
    grouped = scen_data.groupby('round_number').agg({
        'scc_count': 'mean',
        'fragmentation': 'mean'
    }).reset_index()
    
    ax_scc.plot(grouped['round_number'], grouped['scc_count'], label=LABELS[scenario],
                color=palette[scenario], marker=markers[scenario], linewidth=2)
    ax_frag.plot(grouped['round_number'], grouped['fragmentation'], label=LABELS[scenario],
                 color=palette[scenario], marker=markers[scenario], linewidth=2)

ax_scc.set_title('(a) Componentes Fuertemente Conexas κ(Gₜ) (N=4096)', fontsize=12, fontweight='bold')
ax_scc.set_xlabel('Ronda de Simulación t', fontsize=11, fontweight='bold')
ax_scc.set_ylabel('Número de SCCs κ(Gₜ)', fontsize=11, fontweight='bold')
ax_scc.grid(True, linestyle='--', alpha=0.5)
ax_scc.legend(loc='upper right', frameon=True, fontsize=9)
ax_scc.set_ylim(0.5, 2.5)

ax_frag.set_title('(b) Índice de Fragmentación Normalizado Φₜ (N=4096)', fontsize=12, fontweight='bold')
ax_frag.set_xlabel('Ronda de Simulación t', fontsize=11, fontweight='bold')
ax_frag.set_ylabel('Fragmentación Φₜ ∈ [0, 1]', fontsize=11, fontweight='bold')
ax_frag.grid(True, linestyle='--', alpha=0.5)
ax_frag.legend(loc='upper right', frameon=True, fontsize=9)
ax_frag.set_ylim(-0.05, 0.5)

plt.tight_layout()
plt.savefig(f'{output_dir}/fig_coevolution_fragmentation_scc.png', bbox_inches='tight')
plt.savefig(f'{output_dir}/fig_coevolution_fragmentation_scc.pdf', bbox_inches='tight')
plt.close()
print('Refreshed figures cleanly')
