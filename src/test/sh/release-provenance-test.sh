#!/usr/bin/env bash
set -euo pipefail

export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
verifier="$repo_root/scripts/verify-release-provenance.sh"
ci_verifier="$repo_root/scripts/verify-ci-provenance.sh"
test_repo="$(mktemp -d "${TMPDIR:-/tmp}/tessera-provenance-test.XXXXXX")"
stub_dir="$(mktemp -d "${TMPDIR:-/tmp}/tessera-gh-stub.XXXXXX")"
trap 'rm -rf "$test_repo" "$stub_dir"' EXIT

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
expect_failure "stable SemVer" main refs/heads/main
expect_failure "stable SemVer" v01.0.0 refs/heads/main
expect_failure "stable SemVer" "../heads/main" refs/heads/main

git -C "$test_repo" switch -q -c side
git -C "$test_repo" commit -q --allow-empty -m "side"
git -C "$test_repo" tag -a v0.1.2 -m "side release"
expect_failure "not contained" v0.1.2 refs/heads/main

make_gh_stub() {
  local name="$1"
  local body="$2"
  printf '#!/usr/bin/env bash\n%s\n' "$body" > "$stub_dir/$name"
  chmod +x "$stub_dir/$name"
}

make_gh_stub gh-one 'printf "%s\n" "$@" > "${GH_STUB_LOG:?}"; echo 1'
make_gh_stub gh-zero 'echo 0'
make_gh_stub gh-garbage 'echo not-a-number'
make_gh_stub gh-fails 'exit 1'

expect_ci_failure() {
  local expected="$1"
  shift
  local output
  if output=$("$@" 2>&1); then
    echo "expected ci-provenance failure for: $*" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "expected diagnostic containing '$expected', got: $output" >&2
    exit 1
  fi
}

sha_a="0000000000000000000000000000000000000000"
sha_b="1111111111111111111111111111111111111111"

stub_log="$stub_dir/query.log"
GH="$stub_dir/gh-one" GH_STUB_LOG="$stub_log" "$ci_verifier" "$sha_a" "$sha_a" example/repo
for required in "branch=main" "event=push" "status=success" "head_sha=$sha_a" \
  "/repos/example/repo/actions/workflows/ci.yml/runs"; do
  if ! grep -qF "$required" "$stub_log"; then
    echo "ci provenance query is missing '$required'" >&2
    exit 1
  fi
done
expect_ci_failure "does not match" env GH="$stub_dir/gh-one" "$ci_verifier" "$sha_a" "$sha_b" example/repo
expect_ci_failure "not found" env GH="$stub_dir/gh-zero" "$ci_verifier" "$sha_a" "$sha_a" example/repo
expect_ci_failure "not found" env GH="$stub_dir/gh-garbage" "$ci_verifier" "$sha_a" "$sha_a" example/repo
expect_ci_failure "could not query" env GH="$stub_dir/gh-fails" "$ci_verifier" "$sha_a" "$sha_a" example/repo
expect_ci_failure "usage" env GH="$stub_dir/gh-one" "$ci_verifier" "$sha_a" "$sha_a"

echo "release provenance tests: OK"
