"""Regenerate the Chapter 5 case figures in the style of VisualizationTool.py.
T0: exact I^0 from the thesis. T_final: final topology/opinions from the SiLEnSeSS harness runs.
Arrows go from emitter j to receiver i (direction of influence); width ~ weight; diagonal not drawn."""
import json, sys, os
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import networkx as nx

seeds_json, outdir = sys.argv[1], sys.argv[2]
runs = json.load(open(seeds_json))

I1 = [[0.6, 0.4, 0, 0, 0], [0.4, 0.4, 0.2, 0, 0], [0.1, 0.1, 0.6, 0.1, 0.1], [0, 0, 0.2, 0.4, 0.4], [0, 0, 0, 0.4, 0.6]]
I2 = [[0.95, 0, 0, 0.05, 0, 0, 0, 0], [0, 0.80, 0, 0.20, 0, 0, 0, 0], [0, 0, 0.90, 0.10, 0, 0, 0, 0],
      [0.20, 0.10, 0.20, 0, 0.50, 0, 0, 0], [0, 0, 0, 0.25, 0.25, 0.20, 0.10, 0.20], [0, 0, 0, 0, 0.10, 0.90, 0, 0],
      [0, 0, 0, 0, 0.20, 0, 0.80, 0], [0, 0, 0, 0, 0.05, 0, 0, 0.95]]
I3 = [[0.30, 0.15, 0.40, 0.15], [0.15, 0.30, 0.15, 0.40], [0.40, 0.15, 0.30, 0.15], [0.15, 0.40, 0.15, 0.30]]
B1, B2, B3 = [1.0, 0.8, 0.5, 0.2, 0.0], [0.1, 0.2, 0.15, 1.0, 0.85, 0.25, 0.3, 0.05], [1.0, 0.9, 0.1, 0.0]


def draw(edges, opinions, silent, title, path, offset=0):
    n = len(opinions)
    G = nx.DiGraph()
    for i in range(n):
        G.add_node(i)
    for emitter, receiver, w in edges:
        if emitter != receiver and w > 0:
            G.add_edge(emitter, receiver, weight=w)
    pos = nx.circular_layout(G)
    plt.figure(figsize=(8, 6))
    for shape in ('o', '^'):
        nodes = [i for i in range(n) if ('^' if silent[i] else 'o') == shape]
        if nodes:
            nx.draw_networkx_nodes(G, pos, nodelist=nodes, node_shape=shape, node_size=700,
                                   node_color=[opinions[i] for i in nodes], cmap=plt.cm.coolwarm,
                                   vmin=0, vmax=1, edgecolors='black')
    nx.draw_networkx_labels(G, pos, labels={i: str(i + offset) for i in range(n)}, font_color='white', font_weight='bold')
    nx.draw_networkx_edges(G, pos, edge_color='gray', width=[G[u][v]['weight'] * 5 for u, v in G.edges()],
                           arrowsize=20, connectionstyle='arc3,rad=0.1', node_size=700)
    sm = plt.cm.ScalarMappable(cmap=plt.cm.coolwarm, norm=plt.Normalize(vmin=0, vmax=1))
    sm.set_array([])
    plt.colorbar(sm, ax=plt.gca(), label='Valor de Opinión', orientation='vertical', shrink=0.8)
    plt.title(title)
    plt.axis('off')
    plt.tight_layout()
    plt.savefig(path, dpi=150)
    plt.close()
    print('wrote', path)


def t0_edges(I):
    return [(j, i, I[i][j]) for i in range(len(I)) for j in range(len(I)) if i != j and I[i][j] > 0]


def final(spec, seed):
    r = runs[spec][str(seed)]
    edges = [(emitter, receiver, w) for receiver, emitter, w in r['edges']]
    silent = [s == 0 for s in r['speaking']]
    return edges, r['public'], silent


draw(t0_edges(I1), B1, [False] * 5, 'Caso 1 ($T_0$): grafo fuertemente conexo', os.path.join(outdir, 'Figure_1.png'))
e, b, s = final('caso1:coev:1.0:1.0:1', 3)
draw(e, b, s, 'Caso 1 ($T_{final}$): fragmentación estructural', os.path.join(outdir, 'Figure_1_final.png'))
draw(t0_edges(I2), B2, [False] * 8, 'Caso 2 ($T_0$): minoría vocal asimétrica', os.path.join(outdir, 'Figure_2.png'), offset=1)
e, b, s = final('caso2:coev:1.0:1.0:1', 1)
draw(e, b, s, 'Caso 2 ($T_{final}$): absorción de la minoría', os.path.join(outdir, 'Figure_2_final.png'), offset=1)
draw(t0_edges(I3), B3, [False] * 4, 'Caso 3 ($T_0$): cliqué (SOM$^+$)', os.path.join(outdir, 'Figure_3.png'))
e, b, s = final('caso3:coev:1.0:1.0:1', 1)
draw(e, b, s, 'Caso 3 ($T_{final}$): sub-cliqués con habla reactivada', os.path.join(outdir, 'Figure_3_final.png'))
