#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: verify-release-tag.sh <tag> <version>" >&2
  exit 64
fi

tag="$1"
version="$2"
stable_semver='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

if [[ ! "$version" =~ $stable_semver ]] || [[ "$tag" != v* ]] ||
   [[ ! "${tag#v}" =~ $stable_semver ]]; then
  echo "tag and version must use stable SemVer: tag=vX.Y.Z version=X.Y.Z" >&2
  exit 65
fi

expected="v$version"
if [[ "$tag" != "$expected" ]]; then
  echo "tag $tag does not match project version $version (expected $expected)" >&2
  exit 66
fi

echo "release tag verified: $tag"
