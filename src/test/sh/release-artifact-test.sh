#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"

if [[ $# -gt 1 ]]; then
  echo "usage: release-artifact-test.sh [staged-dir]" >&2
  exit 64
fi

if [[ $# -eq 1 ]]; then
  # Verify an existing staged directory so the tested jar is the shipped jar.
  output_dir="$1"
  if [[ ! -d "$output_dir" ]]; then
    echo "staged directory not found: $output_dir" >&2
    exit 65
  fi
else
  output_dir="$(mktemp -d "${TMPDIR:-/tmp}/tessera-release-test.XXXXXX")"
  trap 'rm -rf "$output_dir"' EXIT
  bash "$repo_root/scripts/build-release.sh" "$output_dir"
fi

jars=("$output_dir"/tessera-*.jar)
if [[ ${#jars[@]} -ne 1 || ! -f "${jars[0]}" ]]; then
  echo "expected exactly one tessera-*.jar in $output_dir, found: ${jars[*]}" >&2
  exit 66
fi
jar="${jars[0]}"
checksum="$jar.sha256"
test -f "$checksum"

(
  cd "$output_dir"
  sha256sum -c "$(basename "$checksum")"
)

actual="$(java -jar "$jar" check "$repo_root/examples/Constants.tes")"
expected=$'id: OK\nalias: OK\ninferredAlias: OK'
if [[ "$actual" != "$expected" ]]; then
  echo "unexpected executable JAR output:" >&2
  printf '%s\n' "$actual" >&2
  exit 1
fi

echo "release artifact tests: OK"
