#!/usr/bin/env bash
set -euo pipefail

# Prints the sbt project version as a bare X.Y.Z line.
# sbt startup noise and ANSI control sequences (supershell progress,
# first-launch output) are filtered out; fails when no stable-SemVer
# line remains rather than passing garbage downstream.

stable_semver='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

version="$( {
  sbt --error -Dsbt.supershell=false -Dsbt.log.noformat=true 'print version' |
    tr -d '\r' |
    sed $'s/\x1b\\[[0-9;?]*[a-zA-Z]//g' |
    grep -E "$stable_semver" |
    tail -n 1
} || true)"

if [[ -z "$version" ]]; then
  echo "could not determine project version from sbt output" >&2
  exit 1
fi

printf '%s\n' "$version"
