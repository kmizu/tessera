#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
verifier="$repo_root/scripts/verify-release-tag.sh"

expect_success() {
  "$verifier" "$1" "$2"
}

expect_failure() {
  local expected="$1"
  shift
  local output
  if output=$("$verifier" "$@" 2>&1); then
    echo "expected failure for: $*" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "expected diagnostic containing '$expected', got: $output" >&2
    exit 1
  fi
}

expect_success "v0.1.0" "0.1.0"
expect_success "v12.34.56" "12.34.56"
expect_failure "stable SemVer" "0.1.0" "0.1.0"
expect_failure "stable SemVer" "v0.1" "0.1.0"
expect_failure "stable SemVer" "v01.1.0" "01.1.0"
expect_failure "does not match" "v0.1.1" "0.1.0"
expect_failure "usage" "v0.1.0"

echo "release tag validation tests: OK"
