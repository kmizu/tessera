#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
output_dir="${1:-$repo_root/dist}"
temporary_runtime=""

cleanup() {
  if [[ -n "$temporary_runtime" ]]; then
    rm -rf "$temporary_runtime"
  fi
}
trap cleanup EXIT

if [[ -z "${XDG_RUNTIME_DIR:-}" || ! -d "$XDG_RUNTIME_DIR" ||
      ! -w "$XDG_RUNTIME_DIR" ]]; then
  temporary_runtime="$(mktemp -d /tmp/tessera-runtime.XXXXXX)"
  export XDG_RUNTIME_DIR="$temporary_runtime"
fi

cd "$repo_root"
mkdir -p "$output_dir"

version="$(sbt --error 'print version' | tail -n 1)"
artifact_name="tessera-$version.jar"
artifact="$output_dir/$artifact_name"
checksum="$artifact.sha256"

rm -f "$artifact" "$checksum"
sbt test assembly

assembly_path="$(sbt --error 'print assembly / assemblyOutputPath' | tail -n 1)"
if [[ ! -f "$assembly_path" ]]; then
  echo "assembly output not found: $assembly_path" >&2
  exit 1
fi

smoke_output="$(java -jar "$assembly_path" check examples/Constants.tes)"
expected=$'id: OK\nalias: OK\ninferredAlias: OK'
if [[ "$smoke_output" != "$expected" ]]; then
  echo "release JAR smoke test failed" >&2
  printf '%s\n' "$smoke_output" >&2
  exit 1
fi

cp "$assembly_path" "$artifact"
(
  cd "$output_dir"
  sha256sum "$artifact_name" > "$artifact_name.sha256"
  sha256sum -c "$artifact_name.sha256"
)

printf '%s\n%s\n' "$artifact" "$checksum"
