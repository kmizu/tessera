#!/usr/bin/env bash
set -euo pipefail

# Verifies that the tagged commit is the commit this workflow runs for, and
# that a successful CI push run on main exists for it.
# The gh binary can be overridden with $GH for testing.

if [[ $# -ne 3 ]]; then
  echo "usage: verify-ci-provenance.sh <tagged-commit> <workflow-commit> <repository>" >&2
  exit 64
fi

tagged_commit="$1"
workflow_commit="$2"
repository="$3"
gh_bin="${GH:-gh}"

if [[ "$tagged_commit" != "$workflow_commit" ]]; then
  echo "tagged commit $tagged_commit does not match workflow commit $workflow_commit" >&2
  exit 65
fi

if ! successful_runs="$("$gh_bin" api \
  "/repos/$repository/actions/workflows/ci.yml/runs?branch=main&event=push&status=success&head_sha=$tagged_commit&per_page=1" \
  --jq '.total_count')"; then
  echo "could not query CI runs for $tagged_commit" >&2
  exit 66
fi

if [[ ! "$successful_runs" =~ ^[0-9]+$ ]] || (( successful_runs < 1 )); then
  echo "successful main push CI run not found for $tagged_commit" >&2
  exit 67
fi
