"""Shared helpers for the thesis analysis scripts.

Connection settings are read from the same environment variables the simulator uses, so the scripts work against
the Docker database (inside compose: DB_HOST_LEGACY=postgres) and against a local PostgreSQL (defaults below).
"""
import csv
import os

import psycopg2


def connect():
    return psycopg2.connect(
        host=os.environ.get('DB_HOST_LEGACY', 'localhost'),
        port=int(os.environ.get('DB_PORT_LEGACY', '5432')),
        dbname=os.environ.get('DB_NAME_LEGACY', 'promueva_legacy'),
        user=os.environ.get('DB_USER_LEGACY', 'postgres'),
        password=os.environ.get('DB_PASSWORD_LEGACY', 'postgres'),
    )


def read_campaign_csv(path):
    """Rows of a campaign CSV. Scenario names contain unquoted commas, so the last four fields are read from the end."""
    rows = []
    with open(path, encoding='utf-8') as f:
        next(f)
        for line in f:
            line = line.rstrip('\n')
            if not line.strip():
                continue
            head, run_id, ms, cons, rounds = line.rsplit(',', 4)
            size, scenario = head.split(',', 1)
            rows.append({'PopulationSize': int(size), 'Scenario': scenario, 'RunID': int(run_id),
                         'TotalDurationMs': int(ms), 'ConsensusCount': int(cons), 'AvgRounds': float(rounds)})
    return rows


def run_ids(path):
    return [r['RunID'] for r in read_campaign_csv(path)]


def scc(k, adj, radj):
    """Strongly connected components (iterative Kosaraju). adj/radj: dict node -> list of nodes. Returns comp ids."""
    seen = [False] * k
    order = []
    for v in range(k):
        if seen[v]:
            continue
        stack = [(v, 0)]
        seen[v] = True
        while stack:
            u, i = stack[-1]
            if i < len(adj[u]):
                stack[-1] = (u, i + 1)
                w = adj[u][i]
                if not seen[w]:
                    seen[w] = True
                    stack.append((w, 0))
            else:
                order.append(u)
                stack.pop()
    comp = [-1] * k
    cid = 0
    for v in reversed(order):
        if comp[v] != -1:
            continue
        stack = [v]
        comp[v] = cid
        while stack:
            u = stack.pop()
            for w in radj[u]:
                if comp[w] == -1:
                    comp[w] = cid
                    stack.append(w)
        cid += 1
    return comp


def write_csv(path, header, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f, lineterminator='\n')
        w.writerow(header)
        w.writerows(rows)
