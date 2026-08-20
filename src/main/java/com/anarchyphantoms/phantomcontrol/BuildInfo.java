package com.anarchyphantoms.phantomcontrol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;

/**
 * Reads build/version metadata (git commit hash, build timestamp, branch,
 * dirty-tree flag) that the {@code git-commit-id-maven-plugin} bakes into
 * {@code git.properties} on the classpath at compile time. Backs the
 * "/ap ver" command.
 *
 * <p>If the jar was built without that plugin running (e.g. a raw
 * {@code javac} compile, or a checkout with no {@code .git} directory),
 * {@code git.properties} won't exist on the classpath and every getter here
 * falls back to "unknown" rather than throwing.
 */
final class BuildInfo {

    private static final String UNKNOWN = "unknown";

    /** Canonical source repository for this plugin, surfaced by "/ap ver". */
    static final String REPO_URL = "https://github.com/cat768/anarchy-phantoms/";

    private final String commitAbbrev;
    private final String commitFull;
    private final String buildTime;
    private final String branch;
    private final boolean dirty;
    private final boolean loaded;

    BuildInfo(AnarchyPhantomsPlugin plugin) {
        Properties props = new Properties();
        boolean loadedOk = false;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            if (in != null) {
                props.load(in);
                loadedOk = true;
            } else {
                plugin.getLogger().warning(
                        "git.properties not found on classpath - this build did not run the "
                        + "git-commit-id-maven-plugin (or .git was unavailable at build time). "
                        + "/ap ver will report 'unknown'.");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read git.properties", e);
        }

        this.loaded = loadedOk;
        this.commitAbbrev = props.getProperty("git.commit.id.abbrev", UNKNOWN);
        this.commitFull = props.getProperty("git.commit.id.full", UNKNOWN);
        this.buildTime = props.getProperty("git.build.time", UNKNOWN);
        this.branch = props.getProperty("git.branch", UNKNOWN);
        this.dirty = Boolean.parseBoolean(props.getProperty("git.dirty", "false"));
    }

    /** Short (7-char) commit hash, e.g. "34ea8b1". */
    String getCommitAbbrev() {
        return commitAbbrev;
    }

    /** Full 40-char commit hash. */
    String getCommitFull() {
        return commitFull;
    }

    /** Build timestamp (UTC), formatted "yyyy-MM-dd HH:mm:ss z". */
    String getBuildTime() {
        return buildTime;
    }

    /** Branch the build was compiled from. */
    String getBranch() {
        return branch;
    }

    /** True if the working tree had uncommitted changes at build time. */
    boolean isDirty() {
        return dirty;
    }

    /** True if git.properties was actually found and loaded from the classpath. */
    boolean isLoaded() {
        return loaded;
    }

    /**
     * One-line summary for "/ap ver", e.g.:
     * "34ea8b1 (main, dirty) - built 2026-08-20 03:14:07 UTC"
     */
    String summary() {
        if (!loaded) {
            return "unknown (no build metadata embedded in this jar)";
        }
        String branchPart = dirty ? branch + ", dirty" : branch;
        return commitAbbrev + " (" + branchPart + ") - built " + buildTime;
    }
}