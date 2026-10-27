"""Networks that ended without consensus, with the strongly connected components of their final topology
(thesis Sec. 5.6, Table "Las 32 redes sin consenso").

usage: python non_consensus.py <first_run_id> <last_run_id> <out.csv>
       (thesis: 160 218 non_consensus_networks.csv)

Columns:
  Stop                          stability (no belief moved more than eps/100 for two rounds) or limit (iteration limit)
  Kappa, Phi, Assortativity     last row of network_coevolution_metrics
  GiantSCC                      size of the largest strongly connected component of the final topology
  MinorSCCSizes                 the other components, largest first (space separated)
  MinorWithNoExplicitNeighbours minor components that are one agent with no explicit in-neighbour (isolation rule)
  ListenersOfMinor              per minor component, how many outside agents still listen to one of its members
  Pattern                       a: only isolated agents; b: small closed groups; c: several large communities (>= 50)
"""
import collections
import sys

from thesis_db import connect, scc, write_csv

LARGE_COMMUNITY = 50


def main(lo, hi, out):
    conn = connect()
    cur = conn.cursor()
    cur.execute("""select n.id, n.run_id, n.number_of_agents, n.final_round, n.simulation_outcome, r.iteration_limit
                   from networks n join runs r on r.id = n.run_id
                   where n.run_id between %s and %s and not n.simulation_outcome
                   order by n.run_id, n.id""", (lo, hi))
    rows = []
    for nid, run, n, final_round, _, limit in cur.fetchall():
        cur.execute("""select scc_count, fragmentation, assortativity from network_coevolution_metrics
                       where network_id = %s order by round_number desc limit 1""", (nid,))
        kappa, phi, r = cur.fetchone()
        cur.execute("select a.id from agents a where a.network_id = %s", (nid,))
        ids = [x[0] for x in cur.fetchall()]
        idx = {a: i for i, a in enumerate(ids)}
        k = len(ids)
        # neighbors: source = receiver i, target = emitter j; influence flows j -> i
        cur.execute("""select nb.source, nb.target from agents a join neighbors nb on nb.source = a.id and nb.round = %s
                       where a.network_id = %s""", (final_round, nid))
        adj, radj, indeg = collections.defaultdict(list), collections.defaultdict(list), [0] * k
        for s, t in cur.fetchall():
            if s in idx and t in idx and s != t:
                adj[idx[t]].append(idx[s])
                radj[idx[s]].append(idx[t])
                indeg[idx[s]] += 1
        groups = collections.defaultdict(list)
        for v, c in enumerate(scc(k, adj, radj)):
            groups[c].append(v)
        comps = sorted(groups.values(), key=len, reverse=True)
        giant, minors = comps[0], comps[1:]
        isolated = sum(1 for g in minors if len(g) == 1 and indeg[g[0]] == 0)
        listeners = []
        for g in minors:
            gs = set(g)
            listeners.append(len({u for v in g for u in adj[v] if u not in gs}))
        sizes = [len(g) for g in minors]
        pattern = 'a' if all(s == 1 for s in sizes) else ('c' if max(sizes) >= LARGE_COMMUNITY else 'b')
        stop = 'limit' if final_round >= limit else 'stability'
        rows.append([run, nid, n, final_round, stop, kappa, round(phi, 4), round(r, 3) if r is not None else '',
                     len(giant), ' '.join(map(str, sizes)), isolated, ' '.join(map(str, listeners)), pattern])
        print(run, final_round, stop, 'kappa', kappa, 'giant', len(giant), 'minors', sizes[:6], 'pattern', pattern, flush=True)
    write_csv(out, ['RunID', 'NetworkID', 'N', 'FinalRound', 'Stop', 'Kappa', 'Phi', 'Assortativity', 'GiantSCC',
                    'MinorSCCSizes', 'MinorWithNoExplicitNeighbours', 'ListenersOfMinor', 'Pattern'], rows)
    print(f'{len(rows)} networks without consensus -> {out}')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(int(sys.argv[1]), int(sys.argv[2]), sys.argv[3])
