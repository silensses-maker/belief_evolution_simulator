"""Regenerates the tables and figures of thesis Ch. 3, 5 and 6 from a results folder and the database.

usage: python analyze_all.py <results_dir> [--figures DIR]

<results_dir> holds the campaign CSVs written by RunThesisCampaign (results/puyo/thesis_2026-10/ for the thesis
itself) and, optionally, case_studies.log (stdout of `RunThesisCampaign case-studies` with
APP_SIMULATION_LOGS=true). Outputs, in <results_dir> unless --figures is given for the figures:
  non_consensus_networks.csv   networks without consensus and their components (Sec. 6.7.5)
  small_world_results.csv      small-world metrics, phase 3 (Sec. 6.7.3)
  figures/*.pdf|png            every figure of Ch. 3 (rupture), Ch. 5 (cases) and Ch. 6 (experiments)
The database is read through thesis_db.connect() (DB_*_LEGACY variables).
"""
import argparse
import os
import subprocess
import sys

from thesis_db import run_ids

HERE = os.path.dirname(os.path.abspath(__file__))
CAMPAIGN_CSVS = ['parametric_results.csv', 'edge_case_results_phase1.csv', 'edge_case_results_phase2.csv',
                 'edge_case_results_phase2_exec2.csv', 'edge_case_results_phase2_exec3.csv',
                 'edge_case_results_phase4.csv']
FIGURES = [  # (script, CSVs it needs)
    ('benchmark.py', ['parametric_results.csv']),
    ('extreme_distributions_assortativity.py', ['edge_case_results_phase1.csv']),
    ('extreme_distributions_convergence.py', ['edge_case_results_phase1.csv']),
    ('tolerance_fluidity_assortativity.py', ['edge_case_results_phase2.csv']),
    ('tolerance_fluidity_convergence.py', ['edge_case_results_phase2.csv']),
    ('small_world.py', ['small_world_results.csv']),
    ('consensus_resistance.py', ['edge_case_results_phase4.csv']),
]


def step(*cmd):
    print('>>', ' '.join(os.path.basename(c) if i < 3 else c for i, c in enumerate(cmd)), flush=True)
    subprocess.run([sys.executable, '-X', 'utf8', *cmd], check=True, cwd=HERE)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('results')
    ap.add_argument('--figures')
    a = ap.parse_args()
    results = os.path.abspath(a.results)
    figures = os.path.abspath(a.figures or os.path.join(results, 'figures'))
    have = lambda name: os.path.exists(os.path.join(results, name))

    ids = [i for name in CAMPAIGN_CSVS if have(name) for i in run_ids(os.path.join(results, name))]
    if ids:
        step('non_consensus.py', str(min(ids)), str(max(ids)), os.path.join(results, 'non_consensus_networks.csv'))
    if have('edge_case_results_phase1.csv') and have('edge_case_results_phase2.csv'):
        step('small_world.py', results)
    for script, needs in FIGURES:
        if all(have(n) for n in needs):
            step(os.path.join('figures', script), results, figures)
        else:
            print(f'-- skipped {script}: missing {", ".join(n for n in needs if not have(n))}')
    step(os.path.join('figures', 'rupture_probability.py'), figures)
    log = os.path.join(results, 'case_studies.log')
    if have('case_studies.log') and '=== RESULT' in open(log, encoding='utf-8', errors='replace').read():
        seeds = os.path.join(results, 'case_seeds.json')
        step('case_seeds.py', os.path.join(results, 'case_studies.log'), seeds)
        step(os.path.join('figures', 'case_studies.py'), seeds, figures)
    else:
        print('-- skipped case-study figures: no case_studies.log with results')
    print('done:', figures)


if __name__ == '__main__':
    main()
