#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: verify-release-provenance.sh <tag> <main-ref>" >&2
  exit 64
fi

tag="$1"
main_ref="$2"
stable_semver='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

if [[ "$tag" != v* ]] || [[ ! "${tag#v}" =~ $stable_semver ]]; then
  echo "release tag must use stable SemVer (vX.Y.Z): $tag" >&2
  exit 65
fi

tag_ref="refs/tags/$tag"

if ! git show-ref --verify --quiet "$tag_ref"; then
  echo "release tag not found: $tag" >&2
  exit 67
fi

if [[ "$(git cat-file -t "$tag_ref")" != "tag" ]]; then
  echo "release tag must be annotated: $tag" >&2
  exit 68
fi

if ! tagged_commit="$(git rev-parse --verify "${tag_ref}^{commit}")"; then
  echo "release tag does not resolve to a commit: $tag" >&2
  exit 69
fi

if ! main_commit="$(git rev-parse --verify "${main_ref}^{commit}")"; then
  echo "main ref not found: $main_ref" >&2
  exit 70
fi

if ! git merge-base --is-ancestor "$tagged_commit" "$main_commit"; then
  echo "tagged commit $tagged_commit is not contained in $main_ref" >&2
  exit 71
fi

printf '%s\n' "$tagged_commit"
