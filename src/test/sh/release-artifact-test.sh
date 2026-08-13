#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
output_dir="$(mktemp -d /tmp/tessera-release-test.XXXXXX)"
trap 'rm -rf "$output_dir"' EXIT

bash "$repo_root/scripts/build-release.sh" "$output_dir"

jar="$output_dir/tessera-0.1.0.jar"
checksum="$jar.sha256"
test -f "$jar"
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
