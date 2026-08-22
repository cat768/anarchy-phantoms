package com.anarchyphantoms.phantomcontrol;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Backs "/ap update" and "/ap rollback <hash>".
 *
 * <p>Both commands fetch a GitHub Release by tag (Releases API), download its
 * single jar asset, and stage it into Paper/Folia's {@code plugins/update/}
 * directory - the standard mechanism both server types already watch and
 * swap in automatically on the *next* restart. This class never attempts to
 * replace the running jar live; nothing here touches the currently loaded
 * plugin classes.
 *
 * <p>Rollback safety is structural rather than a separate allow-list: CI
 * (see .github/workflows/build.yml) only publishes a {@code git-<sha>}
 * release AFTER every smoke-test matrix leg (Paper + Folia, all supported
 * versions) has passed for that commit. A hash that never passed CI simply
 * has no release to find - the GitHub API 404s, and that 404 is surfaced to
 * the admin as "no validated build for that hash", with no way to bypass it
 * from in-game. There is no code path here that stages an unvalidated jar.
 *
 * <p>All network I/O runs off-thread via {@link CompletableFuture}; every
 * callback that touches the sender or Bukkit state hops back through {@link
 * org.bukkit.Bukkit#getGlobalRegionScheduler()} first, since Bukkit API calls
 * aren't thread-safe. The global region scheduler (rather than
 * {@code BukkitScheduler}/{@code getScheduler()}) is used deliberately for
 * Folia compatibility, matching the rest of this codebase (see
 * PhantomEndSpawner/PhantomSoundListener) - nothing here is tied to any one
 * region since it only sends messages and does file I/O, so the global
 * scheduler is the correct (not just safe) choice, not a per-entity one.
 */
final class PluginUpdater {

    private static final String REPO = "cat768/anarchy-phantoms";
    private static final String API_BASE = "https://api.github.com/repos/" + REPO + "/releases/tags/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // Matches a short (7+) or full (40) hex commit hash - same shape GitHistory
    // already accepts for "/ap git info <hash>", kept consistent here.
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-fA-F]{7,40}$");

    private final AnarchyPhantomsPlugin plugin;
    private final HttpClient httpClient;

    PluginUpdater(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /** "/ap update" - stages the latest CI-published build. */
    void update(CommandSender sender) {
        sender.sendMessage("[AnarchyPhantoms] Checking for the latest build...");
        fetchAndStage(sender, "latest", "latest");
    }

    /** "/ap rollback <hash>" - stages a specific, previously-validated build. */
    void rollback(CommandSender sender, String hashQuery) {
        if (!HASH_PATTERN.matcher(hashQuery).matches()) {
            sender.sendMessage("[AnarchyPhantoms] '" + hashQuery
                    + "' doesn't look like a git commit hash (expected 7-40 hex characters).");
            return;
        }
        sender.sendMessage("[AnarchyPhantoms] Looking up validated build for commit " + hashQuery + "...");
        fetchAndStage(sender, "git-" + hashQuery, hashQuery);
    }

    /**
     * Shared fetch/stage pipeline for both commands. {@code tag} is the exact
     * GitHub release tag to query ("latest" or "git-<hash>"); {@code label} is
     * what gets shown to the admin in messages.
     */
    private void fetchAndStage(CommandSender sender, String tag, String label) {
        CompletableFuture
                .supplyAsync(() -> fetchRelease(tag))
                .thenAccept(release -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    if (release == null) {
                        sender.sendMessage("[AnarchyPhantoms] No validated build found for '" + label
                                + "'. Either the hash is wrong, or it never passed CI - only commits "
                                + "that passed every smoke-test leg on GitHub Actions get published.");
                        return;
                    }
                    stageAsync(sender, release);
                }))
                .exceptionally(ex -> {
                    Bukkit.getGlobalRegionScheduler().execute(plugin, () ->
                            sender.sendMessage("[AnarchyPhantoms] Update check failed: "
                                    + rootCauseMessage(ex) + ". Server network may be restricted; "
                                    + "check console for details."));
                    plugin.getLogger().warning("Update/rollback lookup for '" + label + "' failed: " + ex);
                    return null;
                });
    }

    /** Runs on a background thread (via supplyAsync). Blocking HTTP is fine here. */
    private ReleaseInfo fetchRelease(String tag) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + tag))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "anarchy-phantoms-plugin")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("network error contacting GitHub: " + e.getMessage(), e);
        }

        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("GitHub API returned HTTP " + response.statusCode());
        }

        return ReleaseInfo.parse(response.body());
    }

    /** Downloads the jar asset and writes it into plugins/update/, off the main thread. */
    private void stageAsync(CommandSender sender, ReleaseInfo release) {
        if (release.assetUrl == null) {
            sender.sendMessage("[AnarchyPhantoms] Release '" + release.tagName
                    + "' has no jar asset attached - this shouldn't happen for a CI-published build.");
            return;
        }

        sender.sendMessage("[AnarchyPhantoms] Found " + release.tagName
                + " - downloading and staging (server restart required to apply)...");

        CompletableFuture
                .supplyAsync(() -> downloadAndStage(release))
                .thenAccept(stagedPath -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    sender.sendMessage("[AnarchyPhantoms] Staged " + release.tagName
                            + " to " + stagedPath + ".");
                    sender.sendMessage("[AnarchyPhantoms] This will replace the running jar on next restart "
                            + "(Paper/Folia's update-folder mechanism) - it is NOT applied live.");
                    if (release.body != null && !release.body.isBlank()) {
                        for (String line : release.body.strip().split("\\R")) {
                            sender.sendMessage("  " + line);
                        }
                    }
                }))
                .exceptionally(ex -> {
                    Bukkit.getGlobalRegionScheduler().execute(plugin, () ->
                            sender.sendMessage("[AnarchyPhantoms] Staging failed: " + rootCauseMessage(ex)
                                    + ". No files were replaced; the currently running build is unaffected."));
                    plugin.getLogger().warning("Staging " + release.tagName + " failed: " + ex);
                    return null;
                });
    }

    /** Runs on a background thread. Downloads to a temp file first, then atomically moves it into place. */
    private Path downloadAndStage(ReleaseInfo release) {
        Path updateDir = plugin.getDataFolder().toPath().getParent().resolve("update");
        try {
            Files.createDirectories(updateDir);
        } catch (IOException e) {
            throw new RuntimeException("could not create plugins/update/ directory: " + e.getMessage(), e);
        }

        // Same file name every time: Paper/Folia identify which currently-loaded
        // plugin an update-folder jar replaces by matching the plugin.yml `name`
        // inside it, not the file name - but keeping a stable name here avoids
        // accumulating stale jars from previous /ap update or /ap rollback runs.
        Path dest = updateDir.resolve("AnarchyPhantoms.jar");
        Path tmp = updateDir.resolve("AnarchyPhantoms.jar.download");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(release.assetUrl))
                .header("User-Agent", "anarchy-phantoms-plugin")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new RuntimeException("download returned HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            // Atomic-ish move into the final name only after the full download
            // succeeded, so a failed/partial download never leaves a corrupt
            // AnarchyPhantoms.jar sitting in plugins/update/ for the server to
            // pick up on next restart.
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            throw new RuntimeException("download failed: " + e.getMessage(), e);
        }

        return dest;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }

    /** Minimal hand-parsed subset of the GitHub release JSON response. */
    private static final class ReleaseInfo {
        final String tagName;
        final String body;
        final String assetUrl;

        private ReleaseInfo(String tagName, String body, String assetUrl) {
            this.tagName = tagName;
            this.body = body;
            this.assetUrl = assetUrl;
        }

        /**
         * No JSON library is on the classpath (see pom.xml) and pulling one in
         * solely for two string fields isn't worth the added shaded dependency,
         * so this extracts "tag_name", "body", and the first asset's
         * "browser_download_url" with targeted regexes instead of a full parse.
         * GitHub's response shape for this endpoint is stable and documented;
         * if a field is missing the corresponding getter just comes back null/blank
         * rather than throwing, consistent with BuildInfo's fail-soft style.
         */
        static ReleaseInfo parse(String json) {
            String tagName = extractString(json, "tag_name");
            String body = extractString(json, "body");
            String assetUrl = extractString(json, "browser_download_url");
            return new ReleaseInfo(tagName, body, assetUrl);
        }

        private static String extractString(String json, String field) {
            Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
            Matcher m = p.matcher(json);
            if (!m.find()) {
                return null;
            }
            return m.group(1)
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
    }
}