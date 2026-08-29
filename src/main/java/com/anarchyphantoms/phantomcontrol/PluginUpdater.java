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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Backs "/ap update" and "/ap rollback <hash>".
 *
 * <p>Both commands fetch a GitHub Release by tag (Releases API), download its
 * jar asset, verify it against the SHA-256 digest CI published alongside it,
 * and - only if that verification passes - stage it into Paper/Folia's
 * {@code plugins/update/} directory: the standard mechanism both server types
 * already watch and swap in automatically on the *next* restart. This class
 * never attempts to replace the running jar live; nothing here touches the
 * currently loaded plugin classes.
 *
 * <p><b>Two independent layers, not one.</b> It's tempting to think "CI only
 * publishes a {@code git-<sha>} release after every smoke-test leg passes, so
 * a hash without a release 404s and that's the safety" - but that only
 * establishes that a release *exists* for a validated commit, not that the
 * bytes downloaded here *are* what CI built and tested. A release can be
 * edited or have its asset swapped after the fact, a token can be
 * compromised, a download can be silently truncated. So:
 * <ul>
 *   <li>{@code fetchRelease} finding a release tells us a build for this
 *       hash/label passed CI at some point (existence check).</li>
 *   <li>{@code downloadVerifyAndStage} recomputing SHA-256 over the
 *       downloaded bytes and comparing it against the {@code .sha256} file CI
 *       published in the SAME release tells us the bytes on disk are exactly
 *       the bytes CI produced (integrity check).</li>
 * </ul>
 * Both must pass. A release with a jar but no matching digest asset, or a
 * digest that doesn't match, is treated identically to a 404: nothing is
 * staged, and the admin is told why.
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

    // A SHA-256 digest is exactly 64 hex characters - anything else in the
    // .sha256 asset is treated as malformed/untrustworthy, not "close enough".
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final AnarchyPhantomsPlugin plugin;
    private final HttpClient httpClient;

    PluginUpdater(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
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
        // Fail closed: no digest asset means we have nothing to verify the
        // download against, so this is treated the same as "not found" rather
        // than silently staging an unverified jar. A genuine CI-published
        // release always has the matching *.jar.sha256 asset (see build.yml);
        // its absence means either an older pre-checksum release, a manually
        // edited release, or a tampered one - none of which should be staged.
        if (release.digestAssetUrl == null) {
            sender.sendMessage("[AnarchyPhantoms] Release '" + release.tagName
                    + "' has a jar but no matching .sha256 digest asset - refusing to stage an "
                    + "unverifiable build. If this is an older release published before checksum "
                    + "verification was added, it cannot be used with /ap update or /ap rollback.");
            return;
        }

        sender.sendMessage("[AnarchyPhantoms] Found " + release.tagName
                + " - downloading and verifying (server restart required to apply)...");

        CompletableFuture
                .supplyAsync(() -> downloadVerifyAndStage(release))
                .thenAccept(stagedPath -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    sender.sendMessage("[AnarchyPhantoms] Verified checksum and staged " + release.tagName
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

    /**
     * Runs on a background thread. Downloads the published digest, downloads
     * the jar into a temp file while hashing it in the same pass, compares
     * the two, and ONLY on a match moves the temp file into its final name in
     * plugins/update/. Any failure - network error, HTTP error, malformed
     * digest, or (most importantly) a hash mismatch - leaves plugins/update/
     * untouched and throws, so the exceptionally() handler above reports it
     * without anything ever being staged.
     */
    private Path downloadVerifyAndStage(ReleaseInfo release) {
        String expectedDigest = fetchDigest(release.digestAssetUrl);

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

            MessageDigest sha256 = newSha256();
            // DigestInputStream updates the digest with every byte read as
            // Files.copy streams through it, so the hash is computed in the
            // same pass as the write - no need to re-read the file afterward.
            try (InputStream raw = response.body();
                    DigestInputStream in = new DigestInputStream(raw, sha256)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            String actualDigest = HexFormat.of().formatHex(sha256.digest());
            if (!actualDigest.equalsIgnoreCase(expectedDigest)) {
                throw new RuntimeException("checksum mismatch for " + release.tagName
                        + " - expected " + expectedDigest + " but downloaded jar hashes to " + actualDigest
                        + ". The download may be corrupt, or the release asset may not match its "
                        + "published digest. Nothing was staged.");
            }

            // Only after the digest check passes does the verified temp file
            // get moved into the name Paper/Folia actually watch. A failed
            // download or a checksum mismatch never leaves anything at `dest`.
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
        } catch (RuntimeException e) {
            // Covers the checksum-mismatch throw above too: never leave a
            // failed/unverified download sitting in plugins/update/.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            throw e;
        }

        return dest;
    }

    /** Downloads the .sha256 asset and returns its digest, validated as exactly 64 hex chars. */
    private String fetchDigest(String digestAssetUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(digestAssetUrl))
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
            throw new RuntimeException("could not download checksum file: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("checksum download returned HTTP " + response.statusCode());
        }

        // sha256sum's default output is "<hex>  <filename>"; CI here writes
        // just the hex digest (see build.yml), but tolerate the standard
        // format too by taking only the first whitespace-delimited token.
        String body = response.body().strip();
        String candidate = body.isEmpty() ? body : body.split("\\s+", 2)[0];

        if (!SHA256_PATTERN.matcher(candidate).matches()) {
            throw new RuntimeException("checksum file did not contain a valid SHA-256 digest "
                    + "(expected 64 hex characters) - refusing to trust it");
        }
        return candidate;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every conforming JVM (see
            // MessageDigest's javadoc); this is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
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
        final String digestAssetUrl;

        private ReleaseInfo(String tagName, String body, String assetUrl, String digestAssetUrl) {
            this.tagName = tagName;
            this.body = body;
            this.assetUrl = assetUrl;
            this.digestAssetUrl = digestAssetUrl;
        }

        /**
         * No JSON library is on the classpath (see pom.xml) and pulling one in
         * solely for a handful of string fields isn't worth the added shaded
         * dependency, so this extracts "tag_name", "body", and the two assets
         * we care about with targeted regexes instead of a full parse.
         * GitHub's response shape for this endpoint is stable and documented;
         * if a field is missing the corresponding getter just comes back null/blank
         * rather than throwing, consistent with BuildInfo's fail-soft style.
         *
         * <p>Assets are matched by NAME, not by taking "the first asset" - CI
         * (build.yml) always uploads exactly a {@code *.jar} and a matching
         * {@code *.jar.sha256} per release, but asset order in the API
         * response isn't a contract worth relying on, and matching by name
         * means a release with extra/renamed assets fails closed (both URLs
         * come back null -> "no jar asset" / digest-missing message) instead
         * of silently grabbing the wrong file.
         */
        static ReleaseInfo parse(String json) {
            String tagName = extractString(json, "tag_name");
            String body = extractString(json, "body");
            String assetUrl = extractAssetUrlByNameSuffix(json, ".jar");
            String digestAssetUrl = extractAssetUrlByNameSuffix(json, ".jar.sha256");
            return new ReleaseInfo(tagName, body, assetUrl, digestAssetUrl);
        }

        private static String extractString(String json, String field) {
            Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
            Matcher m = p.matcher(json);
            if (!m.find()) {
                return null;
            }
            return unescape(m.group(1));
        }

        /**
         * Scans each {"name": "...", ... "browser_download_url": "..."}
         * object in the "assets" array for one whose name ends with
         * {@code suffix}, and returns that asset's download URL. Walks the
         * raw JSON with a per-asset regex rather than a full parse, same
         * trade-off as {@link #extractString}.
         */
        private static String extractAssetUrlByNameSuffix(String json, String suffix) {
            Pattern assetPattern = Pattern.compile(
                    "\\{[^{}]*?\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[^{}]*?"
                    + "\"browser_download_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[^{}]*?\\}");
            Matcher m = assetPattern.matcher(json);
            while (m.find()) {
                String name = unescape(m.group(1));
                if (name != null && name.endsWith(suffix)) {
                    return unescape(m.group(2));
                }
            }
            // Field order within an asset object isn't guaranteed either;
            // retry with the two fields swapped before giving up.
            Pattern swapped = Pattern.compile(
                    "\\{[^{}]*?\"browser_download_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[^{}]*?"
                    + "\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[^{}]*?\\}");
            Matcher m2 = swapped.matcher(json);
            while (m2.find()) {
                String name = unescape(m2.group(2));
                if (name != null && name.endsWith(suffix)) {
                    return unescape(m2.group(1));
                }
            }
            return null;
        }

        private static String unescape(String raw) {
            if (raw == null) {
                return null;
            }
            return raw
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
    }
}