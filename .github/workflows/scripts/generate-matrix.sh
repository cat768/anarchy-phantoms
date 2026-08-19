#!/usr/bin/env bash
# Queries PaperMC's Fill API for every Paper AND Folia Minecraft version that
# has at least one STABLE build, filters both lists down to versions >=
# MIN_VERSION, and emits a JSON matrix pairing (server_type, mc_version,
# channel) for GitHub Actions' `strategy.matrix.include`.
#
# Why 1.21.9 as a floor (not just "whatever Folia happens to support"):
# Folia lags Paper and only has builds for 1.21, 1.20, 1.19 today (per
# fill.papermc.io) - i.e. it doesn't even have per-patch granularity below
# the minor version. Floor is applied identically to both projects so the
# matrix only ever contains versions this plugin is meant to support, not
# "every version Folia happens to publish."
#
# PINNED_PAPER_VERSIONS (one-off carve-out, Paper only):
# 1.21.9 is the production version this plugin is actually deployed on.
# Verified directly against Fill (fill-ui.papermc.io/projects/paper/version/
# 1.21.9): 1.21.9 is marked UNSUPPORTED (since 07/10/2025) and EVERY build
# it ever had is on the ALPHA channel - it never received a STABLE build at
# all, apparently having been superseded by 1.21.10 within the same update
# cycle. That means dynamic discovery below (which only ever looks for
# STABLE) will NEVER find 1.21.9 on its own, pin or no pin.
#
# So this pin does two things a plain version-string pin couldn't:
#   1. Adds 1.21.9 to the Paper leg unconditionally, bypassing MIN_VERSION
#      and the STABLE-only discovery loop.
#   2. Carries an explicit allowed-channel override ("ALPHA") so the
#      smoke-test step knows to accept an ALPHA build for this one leg
#      instead of failing when it finds no STABLE build - which it
#      genuinely never will for this version.
# Every other, non-pinned matrix entry is untouched and still requires
# STABLE, exactly as before.
#
# Format: space-separated "mc_version:channel" pairs. Remove an entry once
# it's no longer relevant, to drop back to "only tested if Fill calls it
# STABLE".
PINNED_PAPER_VERSIONS="1.21.9:ALPHA"
#
# Usage: MIN_VERSION=1.21.9 ./generate-matrix.sh
# Output (stdout):
#   {"include":[{"server_type":"paper","mc_version":"1.21.9","channel":"ALPHA"}, ...]}
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
# ("paper" or "folia"), filtered to >= MIN_VERSION. Emits "mc_version:STABLE"
# lines so output shape matches the pinned-version format below.
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
      echo "${mc_version}:STABLE"
    fi
  done
}

echo "::group::Discovering stable Paper versions >= $MIN_VERSION" >&2
PAPER_ENTRIES=$(stable_versions_for_project "paper")
echo "${PAPER_ENTRIES:-<none>}" >&2
echo "::endgroup::" >&2

# Merge in the pinned versions (see PINNED_PAPER_VERSIONS above) regardless
# of what dynamic discovery found or whether they clear MIN_VERSION.
# Dedupe by mc_version, preferring a dynamically-discovered STABLE entry
# over a pinned non-STABLE one if the same version somehow appears in both
# (e.g. a currently-ALPHA-only pinned version that later gets a real STABLE
# build - the moment that happens, prefer the STABLE truth over the stale
# pin). This is additive-only: it can widen the Paper matrix beyond
# discovery, never narrow it.
if [ -n "$PINNED_PAPER_VERSIONS" ]; then
  echo "::group::Merging pinned Paper versions: $PINNED_PAPER_VERSIONS" >&2
  PAPER_ENTRIES=$(printf '%s\n%s\n' "$PAPER_ENTRIES" "$PINNED_PAPER_VERSIONS" \
    | tr ' ' '\n' | sed '/^$/d' \
    | awk -F: '{
        v=$1; c=$2;
        # First entry seen per version wins UNLESS a later one is STABLE
        # and the stored one is not - then the STABLE entry replaces it.
        if (!(v in seen) || (c == "STABLE" && chan[v] != "STABLE")) {
          seen[v]=1; chan[v]=c
        }
      } END { for (v in chan) print v ":" chan[v] }' \
    | sort -t: -k1,1 -V -r)
  echo "$PAPER_ENTRIES" >&2
  echo "::endgroup::" >&2
fi

echo "::group::Discovering stable Folia versions >= $MIN_VERSION" >&2
FOLIA_ENTRIES=$(stable_versions_for_project "folia" || true)
echo "${FOLIA_ENTRIES:-<none>}" >&2
echo "::endgroup::" >&2

if [ -z "$PAPER_ENTRIES" ]; then
  echo "::error::No stable Paper versions found >= $MIN_VERSION — refusing to emit an empty matrix" >&2
  exit 1
fi

# Build the JSON matrix with jq rather than string-concatenation, so
# quoting/escaping is never a hand-rolled risk. Each "mc_version:channel"
# entry becomes {"server_type":..., "mc_version":..., "channel":...}.
{
  printf '%s\n' "$PAPER_ENTRIES" \
    | jq -R -c 'split(":") | {server_type: "paper", mc_version: .[0], channel: .[1]}'
  if [ -n "$FOLIA_ENTRIES" ]; then
    printf '%s\n' "$FOLIA_ENTRIES" \
      | jq -R -c 'split(":") | {server_type: "folia", mc_version: .[0], channel: .[1]}'
  fi
} | jq -s -c '{include: .}'