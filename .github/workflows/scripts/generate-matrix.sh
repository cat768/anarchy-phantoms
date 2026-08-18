#!/usr/bin/env bash
# Queries PaperMC's Fill API for every Paper AND Folia Minecraft version that
# has at least one STABLE build, filters both lists down to versions >=
# MIN_VERSION, and emits a JSON matrix pairing (server_type, version) for
# GitHub Actions' `strategy.matrix.include`.
#
# Why 1.21.9 as a floor (not just "whatever Folia happens to support"):
# Folia lags Paper and only has builds for 1.21, 1.20, 1.19 today (per
# fill.papermc.io) - i.e. it doesn't even have per-patch granularity below
# the minor version. Floor is applied identically to both projects so the
# matrix only ever contains versions this plugin is meant to support, not
# "every version Folia happens to publish."
#
# Usage: MIN_VERSION=1.21.9 ./generate-matrix.sh
# Output (stdout): {"include":[{"server_type":"paper","mc_version":"1.21.9"}, ...]}
set -euo pipefail

MIN_VERSION="${MIN_VERSION:-1.21.9}"
UA="${UA:-anarchy-phantoms-ci/1.0}"
FILL_BASE="https://fill.papermc.io/v3/projects"

# Returns 0/1 for "version A >= version B", comparing as dotted integer
# tuples (not lexicographic — "1.9" must sort below "1.10", and "26.1"
# must be treated as its own line, not folded into the "1.x" line).
version_ge() {
  # sort -V handles both "1.21.9"-style and "26.1"-style correctly.
  [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" = "$1" ]
}

# Fetches every version with a STABLE build for a given Fill project
# ("paper" or "folia"), filtered to >= MIN_VERSION.
#
# Two-step Fill query per version group, mirroring the existing build.yml
# logic: (1) list all known versions, (2) for each, check whether it has a
# build on the STABLE channel — the versions endpoint alone doesn't say
# whether a version's builds are stable, experimental-only, etc.
stable_versions_for_project() {
  local project="$1"
  local all_versions
  all_versions=$(curl -sf -H "User-Agent: $UA" "${FILL_BASE}/${project}" \
    | jq -r '.versions | to_entries[] | .value[]' | sort -V -r) || {
    echo "::error::Could not fetch version list for project '$project' from Fill API" >&2
    return 1
  }

  local mc_version has_stable
  for mc_version in $all_versions; do
    # Skip pre-releases/RCs (e.g. "1.21.9-pre2") — Fill lists these as
    # their own queryable versions alongside plain releases, but a
    # release-stability smoke-test matrix shouldn't include them. This also
    # sidesteps sort -V/version_ge being unreliable on non-numeric suffixes.
    case "$mc_version" in
      *-*) continue ;;
    esac
    if ! version_ge "$mc_version" "$MIN_VERSION"; then
      continue
    fi
    has_stable=$(curl -sf -H "User-Agent: $UA" \
      "${FILL_BASE}/${project}/versions/${mc_version}/builds" \
      | jq -r 'map(select(.channel == "STABLE")) | length > 0') || has_stable="false"
    if [ "$has_stable" = "true" ]; then
      echo "$mc_version"
    fi
  done
}

echo "::group::Discovering stable Paper versions >= $MIN_VERSION" >&2
PAPER_VERSIONS=$(stable_versions_for_project "paper")
echo "$PAPER_VERSIONS" >&2
echo "::endgroup::" >&2

echo "::group::Discovering stable Folia versions >= $MIN_VERSION" >&2
FOLIA_VERSIONS=$(stable_versions_for_project "folia" || true)
echo "${FOLIA_VERSIONS:-<none>}" >&2
echo "::endgroup::" >&2

if [ -z "$PAPER_VERSIONS" ]; then
  echo "::error::No stable Paper versions found >= $MIN_VERSION — refusing to emit an empty matrix" >&2
  exit 1
fi

# Build the JSON matrix with jq rather than string-concatenation, so
# quoting/escaping is never a hand-rolled risk.
{
  printf '%s\n' "$PAPER_VERSIONS" | jq -R -c '{server_type: "paper", mc_version: .}'
  if [ -n "$FOLIA_VERSIONS" ]; then
    printf '%s\n' "$FOLIA_VERSIONS" | jq -R -c '{server_type: "folia", mc_version: .}'
  fi
} | jq -s -c '{include: .}'