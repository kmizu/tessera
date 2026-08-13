#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
verifier="$repo_root/scripts/verify-release-provenance.sh"
test_repo="$(mktemp -d /tmp/tessera-provenance-test.XXXXXX)"
trap 'rm -rf "$test_repo"' EXIT

git -C "$test_repo" init -q -b main
git -C "$test_repo" config user.name "Tessera Test"
git -C "$test_repo" config user.email "tessera@example.invalid"
git -C "$test_repo" commit -q --allow-empty -m "base"
git -C "$test_repo" tag -a v0.1.0 -m "annotated release"
git -C "$test_repo" tag v0.1.1

expect_success() {
  (
    cd "$test_repo"
    "$verifier" "$1" "$2"
  )
}

expect_failure() {
  local expected="$1"
  shift
  local output
  if output=$(cd "$test_repo" && "$verifier" "$@" 2>&1); then
    echo "expected failure for: $*" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "expected diagnostic containing '$expected', got: $output" >&2
    exit 1
  fi
}

expected_commit="$(git -C "$test_repo" rev-parse HEAD)"
actual_commit="$(expect_success v0.1.0 refs/heads/main)"
if [[ "$actual_commit" != "$expected_commit" ]]; then
  echo "expected tagged commit $expected_commit, got: $actual_commit" >&2
  exit 1
fi

expect_failure "annotated" v0.1.1 refs/heads/main
expect_failure "not found" v9.9.9 refs/heads/main
expect_failure "usage" v0.1.0

git -C "$test_repo" switch -q -c side
git -C "$test_repo" commit -q --allow-empty -m "side"
git -C "$test_repo" tag -a v0.1.2 -m "side release"
expect_failure "not contained" v0.1.2 refs/heads/main

echo "release provenance tests: OK"
