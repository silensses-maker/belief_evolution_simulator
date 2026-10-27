#!/usr/bin/env bash
# Reproduce the thesis experiments with Docker: campaigns on a fresh database, case studies, tables and figures.
#
#   src/scripts/thesis/reproduce.sh                  # everything (several hours: the benchmark goes up to N = 16 384)
#   src/scripts/thesis/reproduce.sh phase4           # only some campaigns (then the tables/figures they allow)
#   THESIS_RESULTS=./results/my_replica src/scripts/thesis/reproduce.sh
#
# Uses docker-compose.thesis-coevolution.yml (its own database volume; nothing published, nothing shared with the main stack).
# Run ids are pinned to the thesis ones, so a second replica needs a fresh database:
#   docker compose -f docker-compose.thesis-coevolution.yml down -v
set -euo pipefail
cd "$(dirname "$0")/../../.."
# Git Bash on Windows rewrites arguments such as /results into Windows paths; container paths must stay as they are.
export MSYS_NO_PATHCONV=1

export THESIS_RESULTS="${THESIS_RESULTS:-./results/replica}"
mkdir -p "$THESIS_RESULTS"
chmod a+rwx "$THESIS_RESULTS" 2>/dev/null || true   # the containers write as a non-root user

compose() { docker compose -f docker-compose.thesis-coevolution.yml "$@"; }

compose build
compose up -d postgres
compose run --rm experiments "${@:-all}" --out /results
if [ "$#" -eq 0 ]; then
  compose run --rm case-studies > "$THESIS_RESULTS/case_studies.log"
fi
compose run --rm analysis /results
echo "Results, tables and figures in $THESIS_RESULTS"
