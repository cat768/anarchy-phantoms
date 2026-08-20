package com.anarchyphantoms.phantomcontrol;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;

/**
 * Reads the static commit log (last N commits: hash, timestamp, subject,
 * body) that {@code scripts/export-git-history.sh} bakes into
 * {@code git-history.properties} on the classpath at build time. Backs
 * "/ap git info <hash>" and "/ap git history".
 *
 * <p>This is a <b>snapshot frozen at build time</b> - unlike a live
 * {@code git log}, it cannot grow after the jar is built, and it only
 * contains whatever commits existed in the {@code .git} directory the
 * build ran in. If that build ran against a shallow clone (e.g. CI without
 * a sufficient {@code fetch-depth}), the list here will be shorter than
 * expected.
 *
 * <p>If {@code git-history.properties} wasn't found or is empty (no build
 * script ran, no {@code .git} available, zero commits), {@link #getEntries()}
 * returns an empty list rather than throwing - callers should handle that
 * case with a "no history available" message.
 */
final class GitHistory {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * One commit's worth of baked-in metadata.
     *
     * @param hash    full 40-char commit hash
     * @param abbrev  short (7-char) commit hash
     * @param epochSeconds author timestamp, seconds since epoch (UTC)
     * @param subject first line of the commit message
     * @param body    remaining lines of the commit message, may be empty
     */
    record CommitEntry(String hash, String abbrev, long epochSeconds, String subject, String body) {

        /** Timestamp formatted as "yyyy-MM-dd HH:mm:ss UTC". */
        String formattedTime() {
            return TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(epochSeconds)) + " UTC";
        }

        boolean hasBody() {
            return body != null && !body.isEmpty();
        }
    }

    private final List<CommitEntry> entries;
    private final boolean loaded;

    GitHistory(AnarchyPhantomsPlugin plugin) {
        Properties props = new Properties();
        boolean loadedOk = false;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("git-history.properties")) {
            if (in != null) {
                // Properties.load(InputStream) reads as ISO-8859-1 by
                // default, which mangles the non-ASCII characters that
                // commit subjects/bodies commonly contain (em dashes,
                // arrows, accented names, etc). export-git-history.sh
                // writes this file as UTF-8, so it must be read back the
                // same way via the Reader overload.
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                loadedOk = true;
            } else {
                plugin.getLogger().warning(
                        "git-history.properties not found on classpath - this build did not run "
                        + "export-git-history.sh (or .git was unavailable at build time). "
                        + "/ap git history and /ap git info will report no data.");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read git-history.properties", e);
        }

        this.loaded = loadedOk;
        this.entries = Collections.unmodifiableList(parse(props));
    }

    private static List<CommitEntry> parse(Properties props) {
        int count = parseIntOrZero(props.getProperty("git.history.count", "0"));
        List<CommitEntry> result = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            String prefix = "git.history." + i + ".";
            String hash = props.getProperty(prefix + "hash", "");
            String abbrev = props.getProperty(prefix + "abbrev", "");
            long epochSeconds = parseLongOrZero(props.getProperty(prefix + "time", "0"));
            String subject = props.getProperty(prefix + "subject", "");
            // The export script escapes real newlines/backslashes in the
            // body ("\n" / "\\") before writing so the .properties file
            // stays line-oriented. No manual unescaping needed here:
            // java.util.Properties#load already reverses exactly these two
            // escape sequences (and only these - it also passes through any
            // other backslash sequence as the raw following character,
            // rather than leaving the backslash) as part of the standard
            // .properties file format, by the time getProperty() returns.
            String body = props.getProperty(prefix + "body", "");

            if (hash.isEmpty()) {
                // Malformed/truncated entry (shouldn't normally happen) -
                // skip rather than surfacing a broken row to the player.
                continue;
            }
            result.add(new CommitEntry(hash, abbrev, epochSeconds, subject, body));
        }
        return result;
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLongOrZero(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** All baked-in commits, newest first. Empty if no history was embedded. */
    List<CommitEntry> getEntries() {
        return entries;
    }

    /** True if git-history.properties was found and loaded from the classpath. */
    boolean isLoaded() {
        return loaded;
    }

    /**
     * Looks up a commit by full or abbreviated hash. Accepts any unambiguous
     * prefix (like {@code git show <prefix>} does), not just the exact
     * 7-char abbrev baked in - e.g. both {@code d3291e7} and {@code d329}
     * match the same commit, provided the prefix isn't ambiguous within
     * the baked-in history.
     *
     * @return the matching entry, or empty if not found or the prefix
     *         matches more than one baked-in commit
     */
    Optional<CommitEntry> findByHash(String query) {
        if (query == null || query.isEmpty()) {
            return Optional.empty();
        }
        String needle = query.toLowerCase(java.util.Locale.ROOT);

        CommitEntry match = null;
        for (CommitEntry entry : entries) {
            if (entry.hash().toLowerCase(java.util.Locale.ROOT).startsWith(needle)) {
                if (match != null) {
                    // Ambiguous prefix - refuse to guess.
                    return Optional.empty();
                }
                match = entry;
            }
        }
        return Optional.ofNullable(match);
    }
}