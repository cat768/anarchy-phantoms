#!/usr/bin/env bash
# Dumps the last COUNT git commits (hash, abbrev, author epoch seconds,
# subject, body) into a .properties file for embedding in the plugin jar.
#
# Usage: export-git-history.sh <output-file> <count>
#
# Output format (one properties file):
#   git.history.count=<n>
#   git.history.0.hash=<full 40-char hash>
#   git.history.0.abbrev=<7-char abbrev>
#   git.history.0.time=<author epoch seconds, UTC>
#   git.history.0.subject=<first line of commit message>
#   git.history.0.body=<remaining lines, \n escaped as literal \n>
#   git.history.1.hash=...
#   ... up to git.history.<n-1>.*
#
# Entries are ordered newest-first (git log default order), matching
# `git log` itself so index 0 is always HEAD.
#
# Fails soft: if git is unavailable, this isn't a git repo, or there are
# zero commits, writes a file with just "git.history.count=0" and exits 0.
# The build must never fail because history export didn't work - the
# runtime side (GitHistory.java) already handles an empty/missing file.

set -uo pipefail

OUT_FILE="${1:?usage: export-git-history.sh <output-file> <count>}"
COUNT="${2:?usage: export-git-history.sh <output-file> <count>}"

mkdir -p "$(dirname "$OUT_FILE")"

write_empty() {
    printf 'git.history.count=0\n' > "$OUT_FILE"
}

if ! command -v git >/dev/null 2>&1; then
    echo "export-git-history: git not found on PATH, writing empty history" >&2
    write_empty
    exit 0
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "export-git-history: not a git repository, writing empty history" >&2
    write_empty
    exit 0
fi

# Unit separator (0x1F) between fields, record separator (0x1E) between
# commits. Neither can appear in normal commit text, so no escaping needed
# for hash/abbrev/time/subject. The body can contain literal newlines
# (multi-paragraph commit messages) - those ARE escaped to literal "\n"
# below since .properties files are line-oriented. Field-splitting is done
# in awk (not `cut`), because `cut -d` on a variable containing embedded
# real newlines does NOT reliably treat the whole multi-line value as one
# record - awk with RS/FS set explicitly does.
US=$'\x1f'
RS=$'\x1e'

RAW="$(git log -n "$COUNT" --date=unix --pretty=format:"%H${US}%h${US}%ad${US}%s${US}%b${RS}" 2>/dev/null)"

if [ -z "$RAW" ]; then
    echo "export-git-history: git log returned nothing (no commits?), writing empty history" >&2
    write_empty
    exit 0
fi

printf '%s' "$RAW" | awk \
    -v US="$US" -v RS_CHAR="$RS" \
    'BEGIN {
        RS = RS_CHAR
        FS = US
        idx = 0
    }
    {
        # A trailing RS after the last commit produces one empty trailing
        # record (awk sees zero fields) - skip it.
        if (NF < 4) next

        # git inserts a stray trailing newline after %b before the next
        # commits format output begins, which ends up as a leading "\n" on
        # this records hash field once RS-splitting reassembles records -
        # strip exactly one leading newline defensively on every field.
        hash    = $1; sub(/^\n/, "", hash)
        abbrev  = $2; sub(/^\n/, "", abbrev)
        time_ep = $3; sub(/^\n/, "", time_ep)
        subject = $4; sub(/^\n/, "", subject)
        body = ""
        for (i = 5; i <= NF; i++) {
            body = (i == 5) ? $i : body FS $i
        }
        sub(/^\n/, "", body)

        # Escape for .properties: backslash first, then real newlines, so
        # the two escape sequences never collide.
        gsub(/\\/, "\\\\", body)
        gsub(/\n/, "\\n", body)

        printf "git.history.%d.hash=%s\n", idx, hash
        printf "git.history.%d.abbrev=%s\n", idx, abbrev
        printf "git.history.%d.time=%s\n", idx, time_ep
        printf "git.history.%d.subject=%s\n", idx, subject
        printf "git.history.%d.body=%s\n", idx, body

        idx++
    }
    END {
        printf "git.history.count=%d\n", idx
    }' > "$OUT_FILE"

echo "export-git-history: wrote $(git log -n "$COUNT" --oneline 2>/dev/null | wc -l) commits to $OUT_FILE" >&2