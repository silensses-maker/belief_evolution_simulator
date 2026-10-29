"""Resistance to consensus (Phase 4, Sec. 5.5.4): fig_edge_case_phase4_resistance,
fig_edge_case_phase4_convergence.

usage: python consensus_resistance.py <results_dir> <figures_dir>
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
P4 = campaign_run_ids(os.path.join(RESULTS, 'edge_case_results_phase4.csv'))  # thesis: 216-218

import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

def generate_phase4_plots(run_ids, output_dir_thesis, output_dir_notes):
    """
    run_ids: tuple/list of 3 IDs: (baseline_run_id, ablation_run_id, subcritical_run_id)
    """
    conn = connect()
    
    rid_base, rid_ablation, rid_subcrit = run_ids
    
    # 1. Fetch Metrics
    query_metrics = f"""
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
    WHERE n.run_id IN ({rid_base}, {rid_ablation}, {rid_subcrit})
    ORDER BY n.run_id, n.id, m.round_number;
    """
    df_metrics = pd.read_sql(query_metrics, conn)
    
    # 2. Fetch Network summary
    query_networks = f"""
    SELECT 
        n.id as network_id,
        n.run_id,
        n.final_round,
        n.simulation_outcome as consensus_reached
    FROM networks n
    WHERE n.run_id IN ({rid_base}, {rid_ablation}, {rid_subcrit})
    ORDER BY n.run_id, n.id;
    """
    df_networks = pd.read_sql(query_networks, conn)
    conn.close()
    
    scenario_map = {
        rid_base: r"Línea Base: Bimodal ($\tau=0.20, P_{\mathrm{create}}=0.05$)",
        rid_ablation: r"Configuración 4A: Ablación ($P_{\mathrm{create}}=0.00, \tau=0.20$)",
        rid_subcrit: r"Configuración 4B: Sub-Crítica ($\tau=0.02, P_{\mathrm{create}}=0.05$)"
    }
    
    palette = {
        rid_base: "#2b5c8f",      # Steel blue
        rid_ablation: "#c0392b",  # Crimson red (fracture / ablation)
        rid_subcrit: "#8e44ad"    # Amethyst purple (metastable bimodal)
    }
    
    markers = {
        rid_base: "o",
        rid_ablation: "s",
        rid_subcrit: "^"
    }
    
    plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
    
    # -------------------------------------------------------------
    # FIGURE 1: Two panels - (a) Fragmentation / SCCs, (b) Assortativity
    # -------------------------------------------------------------
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 5.5), dpi=300)
    
    # Panel (a): Fragmentation Index Phi_t & SCCs
    for rid in [rid_base, rid_subcrit, rid_ablation]:
        sub = df_metrics[df_metrics['run_id'] == rid]
        grp = sub.groupby('round_number')['fragmentation'].agg(['mean', 'std']).reset_index()
        grp_scc = sub.groupby('round_number')['scc_count'].agg(['mean']).reset_index()
        
        label = scenario_map[rid]
        color = palette[rid]
        marker = markers[rid]
        
        ax1.plot(grp['round_number'], grp['mean'], label=label, color=color, marker=marker, linewidth=2.2, markersize=5)
        if grp['std'].notnull().any():
            ax1.fill_between(grp['round_number'],
                             np.clip(grp['mean'] - grp['std'].fillna(0), 0, 1.0),
                             np.clip(grp['mean'] + grp['std'].fillna(0), 0, 1.0),
                             color=color, alpha=0.15)
                             
    ax1.set_title(r"(a) Índice de Fragmentación Topológica $\Phi_t = (\kappa - 1)/(N - 1)$", fontsize=12, fontweight="bold")
    ax1.set_xlabel(r"Ronda de Simulación $t$", fontsize=11, fontweight="bold")
    ax1.set_ylabel(r"Fragmentación $\Phi_t$", fontsize=11, fontweight="bold")
    ax1.set_ylim(-0.02, max(0.25, df_metrics['fragmentation'].max() * 1.15))
    ax1.grid(True, linestyle="--", alpha=0.6)
    ax1.legend(loc="upper left", frameon=True, fontsize=9.5)
    
    # Panel (b): Assortativity
    for rid in [rid_base, rid_ablation, rid_subcrit]:
        sub = df_metrics[df_metrics['run_id'] == rid]
        grp = sub.groupby('round_number')['assortativity'].agg(['mean', 'std']).reset_index()
        
        label = scenario_map[rid]
        color = palette[rid]
        marker = markers[rid]
        
        ax2.plot(grp['round_number'], grp['mean'], label=label, color=color, marker=marker, linewidth=2.2, markersize=5)
        if grp['std'].notnull().any():
            ax2.fill_between(grp['round_number'],
                             np.clip(grp['mean'] - grp['std'].fillna(0), -0.05, 1.05),
                             np.clip(grp['mean'] + grp['std'].fillna(0), -0.05, 1.05),
                             color=color, alpha=0.15)
                             
    ax2.set_title(r"(b) Firma de Asortatividad Dirigida $r^t$ (Segregación vs. Consenso)", fontsize=12, fontweight="bold")
    ax2.set_xlabel(r"Ronda de Simulación $t$", fontsize=11, fontweight="bold")
    ax2.set_ylabel(r"Asortatividad de Opinión $r^t$", fontsize=11, fontweight="bold")
    ax2.set_ylim(-0.05, 1.05)
    ax2.grid(True, linestyle="--", alpha=0.6)
    ax2.legend(loc="lower right" if df_metrics['assortativity'].iloc[-1] < 0.5 else "center right", frameon=True, fontsize=9.5)
    
    plt.tight_layout()
    
    fig1_pdf_notes = f"{output_dir_notes}/fig_edge_case_phase4_resistance.pdf"
    fig1_png_notes = f"{output_dir_notes}/fig_edge_case_phase4_resistance.png"
    fig1_pdf_thesis = f"{output_dir_thesis}/fig_edge_case_phase4_resistance.pdf"
    fig1_png_thesis = f"{output_dir_thesis}/fig_edge_case_phase4_resistance.png"
    
    fig.savefig(fig1_pdf_thesis, bbox_inches='tight')
    fig.savefig(fig1_png_thesis, bbox_inches='tight')
    plt.close(fig)
    print(f"[Plots] Saved Figure 1 to {fig1_png_notes} and {fig1_png_thesis}")
    
    # -------------------------------------------------------------
    # FIGURE 2: Bar chart of Convergence & Consensus status
    # -------------------------------------------------------------
    fig, ax = plt.subplots(figsize=(10, 5.5), dpi=300)
    grp_net = df_networks.groupby('run_id')['final_round'].agg(['mean', 'std', 'max']).reset_index()
    grp_cons = df_networks.groupby('run_id')['consensus_reached'].sum().reset_index()
    grp_net = grp_net.merge(grp_cons, on='run_id')
    grp_net['label'] = grp_net['run_id'].map({
        rid_base: "Línea Base\n(Bimodal, Coev. Activa)",
        rid_ablation: "Configuración 4A: Ablación $P_{create}=0$\n(Desgaste sin Sanación)",
        rid_subcrit: "Configuración 4B: Sub-Crítica $\\tau=0.02$\n(Cuello de Botella)"
    })
    
    colors = [palette[rid] for rid in grp_net['run_id']]
    bars = ax.bar(grp_net['label'], grp_net['mean'], yerr=grp_net['std'].fillna(0), capsize=6,
                  color=colors, alpha=0.88, edgecolor='black', linewidth=1.2, width=0.5)
                  
    for bar, rid, mean_val, max_val, c_reached in zip(bars, grp_net['run_id'], grp_net['mean'], grp_net['max'], grp_net['consensus_reached']):
        height = bar.get_height()
        status_txt = f"Consenso: {int(c_reached)}/3" if c_reached > 0 else ("Disenso: 0/3\n(límite de iteraciones)" if max_val >= 200 else "Disenso: 0/3\n(parada por estabilidad)")  # rerun 2026-10-14
        ax.annotate(f'{mean_val:.1f} rds\n({status_txt})',
                    xy=(bar.get_x() + bar.get_width() / 2, height),
                    xytext=(0, 8),
                    textcoords="offset points",
                    ha='center', va='bottom', fontsize=9.5, fontweight='bold')
                    
    ax.set_title(r"Rondas hasta la Terminación y Estatus de Consenso ($N = 512, m = 4$)", fontsize=12, fontweight="bold")
    ax.set_ylabel(r"Rondas de Simulación", fontsize=11, fontweight="bold")
    ax.set_ylim(0, max(50, (grp_net['mean'] + grp_net['std'].fillna(0)).max() * 1.15))  # rerun 2026-10-10: keep error bars inside
    ax.grid(True, linestyle="--", alpha=0.6, axis='y')
    
    plt.tight_layout()
    fig2_pdf_notes = f"{output_dir_notes}/fig_edge_case_phase4_convergence.pdf"
    fig2_png_notes = f"{output_dir_notes}/fig_edge_case_phase4_convergence.png"
    fig2_pdf_thesis = f"{output_dir_thesis}/fig_edge_case_phase4_convergence.pdf"
    fig2_png_thesis = f"{output_dir_thesis}/fig_edge_case_phase4_convergence.png"
    
    fig.savefig(fig2_pdf_thesis, bbox_inches='tight')
    fig.savefig(fig2_png_thesis, bbox_inches='tight')
    plt.close(fig)
    print(f"[Plots] Saved Figure 2 to {fig2_png_notes} and {fig2_png_thesis}")

if __name__ == "__main__":
    generate_phase4_plots(P4, OUT, OUT)
