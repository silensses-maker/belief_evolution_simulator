"""Small-world metrics at T0 and T_final (Phase 3, thesis Sec. 6.7.3) for the networks of Phases 1 and 2
(first execution). Writes <results_dir>/small_world_results.csv, read by figures/small_world.py.

usage: python small_world.py <results_dir>
Metrics on the undirected graph: average clustering C, average shortest path L on the largest connected component,
C_rand = <k>/N, L_rand = ln N / ln <k>, sigma = (C/C_rand) / (L/L_rand).
"""
import os
import sys

from thesis_db import connect, run_ids as campaign_run_ids

if len(sys.argv) != 2:
    sys.exit(__doc__)
RESULTS = sys.argv[1]
P1 = campaign_run_ids(os.path.join(RESULTS, 'edge_case_results_phase1.csv'))  # thesis: 196-200
P2 = campaign_run_ids(os.path.join(RESULTS, 'edge_case_results_phase2.csv'))  # thesis: 201-205

import networkx as nx
import numpy as np
import pandas as pd

conn = connect()
cur = conn.cursor()

runs = [P1[0], P1[1], P1[2], P1[3], P1[4], P2[0], P2[1], P2[2], P2[3], P2[4]]
scenario_names = {
    P1[0]: "Línea Base Uniforme (Prop. B)",
    P1[1]: "Caso 1A Bimodal (Prop. B - Homofílico)",
    P1[2]: "Caso 1A Bimodal (Prop. A - Uniforme)",
    P1[3]: "Caso 1B Radical (Prop. B - Homofílico)",
    P1[4]: "Caso 1B Radical (Prop. A - Uniforme)",
    P2[0]: "Línea Base Ref. (tau=0.10, Prop. B)",
    P2[1]: "Caso 2A Hubs Intol. (Prop. B - Homofílico)",
    P2[2]: "Caso 2A Hubs Intol. (Prop. A - Uniforme)",
    P2[3]: "Caso 2B Fluidez Abs. (Prop. B - Homofílico)",
    P2[4]: "Caso 2B Fluidez Abs. (Prop. A - Uniforme)"
}

records = []

for run_id in runs:
    cur.execute("SELECT id, final_round FROM networks WHERE run_id = %s ORDER BY id", (run_id,))
    nets = cur.fetchall()
    print(f"Processing Run {run_id} ({scenario_names[run_id]})...")
    
    for net_id, final_round in nets:
        # Get all agent ids
        cur.execute("SELECT id FROM agents WHERE network_id = %s", (net_id,))
        agents = [r[0] for r in cur.fetchall()]
        
        for rnd in [0, final_round]:
            cur.execute("""
                SELECT source, target 
                FROM neighbors 
                WHERE source IN (SELECT id FROM agents WHERE network_id = %s) AND round = %s
            """, (net_id, rnd))
            edges = cur.fetchall()
            
            G = nx.Graph()
            G.add_nodes_from(agents)
            G.add_edges_from(edges)
            
            N = G.number_of_nodes()
            E = G.number_of_edges()
            k_avg = (2.0 * E) / N if N > 0 else 0.0
            
            # Clustering coefficient
            C = nx.average_clustering(G)
            
            # Path length on largest connected component
            largest_cc = max(nx.connected_components(G), key=len)
            G_cc = G.subgraph(largest_cc)
            cc_size = len(largest_cc)
            L = nx.average_shortest_path_length(G_cc) if cc_size > 1 else 0.0
            
            # Random benchmarks
            C_rand = k_avg / N if N > 0 else 0.0
            L_rand = (np.log(N) / np.log(k_avg)) if k_avg > 1 else 0.0
            
            sigma = (C / C_rand) / (L / L_rand) if (C_rand > 0 and L > 0 and L_rand > 0) else 0.0
            
            records.append({
                "run_id": run_id,
                "scenario": scenario_names[run_id],
                "network_id": net_id,
                "round_type": "T0" if rnd == 0 else "Tfinal",
                "round": rnd,
                "N": N,
                "E": E,
                "k_avg": k_avg,
                "C": C,
                "L": L,
                "C_rand": C_rand,
                "L_rand": L_rand,
                "sigma": sigma,
                "cc_ratio": cc_size / N
            })

conn.close()

df = pd.DataFrame(records)
output_csv = os.path.join(RESULTS, 'small_world_results.csv')
df.to_csv(output_csv, index=False)
print(f"Saved {output_csv}")

# Print summary
summary = df.groupby(["run_id", "scenario", "round_type"])[["k_avg", "C", "L", "sigma", "C_rand", "L_rand", "cc_ratio"]].mean().reset_index()
print("\n=== Small-World Metrics Summary ===")
pd.set_option('display.max_columns', 15)
pd.set_option('display.width', 1000)
print(summary.to_string())

