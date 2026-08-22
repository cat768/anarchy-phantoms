package com.anarchyphantoms.phantomcontrol;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AnarchyPhantoms - Phantom Control
 *
 * Restricts Phantom spawning to The End dimension only, and only directly
 * above end stone or chorus plant/chorus flower blocks. Phantoms remain
 * passive (non-targeting, silent) until a player actually attacks one,
 * at which point that specific phantom becomes hostile and vocal.
 */
public final class AnarchyPhantomsPlugin extends JavaPlugin {

    private PluginSettings settings;
    private PhantomProvocationTracker provocationTracker;
    private PhantomDebugNotifier debugNotifier;
    private PhantomSpawnCauseTag spawnCauseTag;
    private BuildInfo buildInfo;
    private GitHistory gitHistory;
    private PluginUpdater pluginUpdater;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.settings = new PluginSettings(this);
        this.provocationTracker = new PhantomProvocationTracker(this);
        this.debugNotifier = new PhantomDebugNotifier(this);
        this.spawnCauseTag = new PhantomSpawnCauseTag(this);
        this.buildInfo = new BuildInfo(this);
        this.gitHistory = new GitHistory(this);
        this.pluginUpdater = new PluginUpdater(this);

        PhantomSpawnListener spawnListener = new PhantomSpawnListener(this);
        PhantomBehaviorListener behaviorListener = new PhantomBehaviorListener(this, provocationTracker);
        PhantomSoundListener soundListener = new PhantomSoundListener(this, provocationTracker);
        PhantomEndSpawner endSpawner = new PhantomEndSpawner(this);

        getServer().getPluginManager().registerEvents(spawnListener, this);
        getServer().getPluginManager().registerEvents(behaviorListener, this);
        getServer().getPluginManager().registerEvents(soundListener, this);
        getServer().getPluginManager().registerEvents(endSpawner, this);

        // PlayerJoinEvent only fires for players connecting after this point,
        // so anyone already online (e.g. this plugin was loaded via a
        // server-wide /reload rather than a fresh boot) needs their spawn
        // task started explicitly here too.
        for (Player player : getServer().getOnlinePlayers()) {
            endSpawner.startTaskForExisting(player);
        }

        getLogger().info("AnarchyPhantoms phantom control enabled: End-only spawns, endstone/chorus surface required, passive until attacked.");
    }

    @Override
    public void onDisable() {
        getLogger().info("AnarchyPhantoms phantom control disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("anarchyphantoms")) {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelp(sender, label);
                return true;
            }
            if (args[0].equalsIgnoreCase("ver") || args[0].equalsIgnoreCase("version")) {
                sendVersion(sender);
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("git")) {
                handleGitCommand(sender, args);
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("anarchyphantoms.admin")) {
                    sender.sendMessage("You do not have permission to do that.");
                    return true;
                }
                reloadConfig();
                settings.reload();
                sender.sendMessage("[AnarchyPhantoms] Configuration reloaded.");
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("update")) {
                if (!sender.hasPermission("anarchyphantoms.admin")) {
                    sender.sendMessage("You do not have permission to do that.");
                    return true;
                }
                pluginUpdater.update(sender);
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("rollback")) {
                if (!sender.hasPermission("anarchyphantoms.admin")) {
                    sender.sendMessage("You do not have permission to do that.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms rollback <hash>");
                    return true;
                }
                pluginUpdater.rollback(sender, args[1]);
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
                if (!sender.hasPermission("anarchyphantoms.admin")) {
                    sender.sendMessage("You do not have permission to do that.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("[AnarchyPhantoms] Debug logging is currently "
                            + (settings.isDebugEnabled() ? "ON" : "OFF") + ".");
                    sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms debug <on|off>");
                    return true;
                }
                String state = args[1];
                if (state.equalsIgnoreCase("on")) {
                    settings.setDebugRuntimeOverride(true);
                    sender.sendMessage("[AnarchyPhantoms] Debug logging enabled.");
                } else if (state.equalsIgnoreCase("off")) {
                    settings.setDebugRuntimeOverride(false);
                    sender.sendMessage("[AnarchyPhantoms] Debug logging disabled.");
                } else {
                    sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms debug <on|off>");
                }
                return true;
            }
            sender.sendMessage("[AnarchyPhantoms] Unknown subcommand '" + args[0] + "'. Run /" + label + " help for a list of commands.");
            return true;
        }
        return false;
    }

    /** "/ap" or "/ap help" - lists all subcommands, with admin-only ones marked. */
    private void sendHelp(CommandSender sender, String label) {
        boolean isAdmin = sender.hasPermission("anarchyphantoms.admin");

        sender.sendMessage("[AnarchyPhantoms] Commands:");
        sender.sendMessage("  /" + label + " help - Shows this list.");
        sender.sendMessage("  /" + label + " ver - Shows the running build's version/commit info.");
        sender.sendMessage("  /" + label + " git - Shows the current build's commit, with full message.");
        sender.sendMessage("  /" + label + " git info <hash> - Shows full detail for a specific baked-in commit.");
        sender.sendMessage("  /" + label + " git history [page] - Lists baked-in commit history, newest first.");
        if (isAdmin) {
            sender.sendMessage("  /" + label + " reload - Reloads config.yml without a restart. (admin)");
            sender.sendMessage("  /" + label + " debug <on|off> - Toggles debug logging at runtime. (admin)");
            sender.sendMessage("  /" + label + " update - Stages the latest CI-validated build. Requires a restart to apply. (admin)");
            sender.sendMessage("  /" + label + " rollback <hash> - Stages a specific past CI-validated build. Requires a restart to apply. (admin)");
        }
        if (sender.hasPermission(PhantomDebugNotifier.DEBUG_VIEW_PERMISSION)) {
            sender.sendMessage("[AnarchyPhantoms] You have '" + PhantomDebugNotifier.DEBUG_VIEW_PERMISSION
                    + "' - debug lines (spawns, aggro changes) will show in your chat while debug mode is on.");
        }
    }

    /**
     * "/ap ver" - the plain-text build summary (commit/branch/build time),
     * followed by a clickable, hoverable link to the plugin's source
     * repository. The link is sent as its own Adventure component (rather
     * than folded into the summary line) so every {@link CommandSender} -
     * players and console alike - gets a properly clickable entry, with a
     * plain fallback for anything console-side that doesn't render click
     * events.
     */
    private void sendVersion(CommandSender sender) {
        sender.sendMessage("[AnarchyPhantoms] " + buildInfo.summary());

        Component repoLine = Component.text("Source: ", NamedTextColor.GRAY)
                .append(Component.text(BuildInfo.REPO_URL, NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(BuildInfo.REPO_URL))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Click to open the repository", NamedTextColor.GRAY))));
        sender.sendMessage(repoLine);
    }

    /**
     * Handles "/ap git", "/ap git info <hash>", and "/ap git history [page]".
     * Split out of {@link #onCommand} to keep that method's top-level
     * dispatch readable now that the git subtree has its own arg parsing.
     */
    private void handleGitCommand(CommandSender sender, String[] args) {
        // args[0] is "git" itself; args[1] (if present) is the git subcommand.
        String sub = args.length > 1 ? args[1] : null;

        if (sub == null) {
            sendGitCurrentCommit(sender);
            return;
        }

        if (sub.equalsIgnoreCase("info")) {
            if (args.length < 3) {
                sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms git info <hash>");
                return;
            }
            sendGitInfo(sender, args[2]);
            return;
        }

        if (sub.equalsIgnoreCase("history")) {
            int page = 1;
            if (args.length >= 3) {
                try {
                    page = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms git history [page]");
                    return;
                }
            }
            sendGitHistory(sender, page);
            return;
        }

        sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms git [info <hash>|history [page]]");
    }

    /** "/ap git" - current build's commit, expanded with the full commit message. */
    private void sendGitCurrentCommit(CommandSender sender) {
        sender.sendMessage("[AnarchyPhantoms] " + buildInfo.summary());

        if (!buildInfo.isLoaded()) {
            return;
        }

        // The current build's own commit may or may not be in the baked-in
        // history list (e.g. history export failed, or ran with a shallow
        // clone while git-commit-id still found the HEAD commit fine via
        // its own separate lookup) - fall back gracefully if it's absent.
        Optional<GitHistory.CommitEntry> current = gitHistory.findByHash(buildInfo.getCommitFull());
        if (current.isEmpty()) {
            sender.sendMessage("[AnarchyPhantoms] (Full commit message unavailable - not in baked-in history.)");
            return;
        }

        sendCommitDetail(sender, current.get());
    }

    /** "/ap git info <hash>" - full detail for one specific baked-in commit. */
    private void sendGitInfo(CommandSender sender, String hashQuery) {
        if (!gitHistory.isLoaded() || gitHistory.getEntries().isEmpty()) {
            sender.sendMessage("[AnarchyPhantoms] No commit history is embedded in this build.");
            return;
        }

        Optional<GitHistory.CommitEntry> entry = gitHistory.findByHash(hashQuery);
        if (entry.isEmpty()) {
            sender.sendMessage("[AnarchyPhantoms] No commit matching '" + hashQuery
                    + "' found in the last " + gitHistory.getEntries().size() + " baked-in commits "
                    + "(either it doesn't exist, or the prefix is ambiguous).");
            return;
        }

        sendCommitDetail(sender, entry.get());
    }

    private static final int HISTORY_PAGE_SIZE = 8;

    /** "/ap git history [page]" - paginated list of baked-in commits, newest first. */
    private void sendGitHistory(CommandSender sender, int page) {
        List<GitHistory.CommitEntry> entries = gitHistory.getEntries();

        if (!gitHistory.isLoaded() || entries.isEmpty()) {
            sender.sendMessage("[AnarchyPhantoms] No commit history is embedded in this build.");
            return;
        }

        int totalPages = Math.max(1, (entries.size() + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE);
        if (page < 1 || page > totalPages) {
            sender.sendMessage("[AnarchyPhantoms] Invalid page " + page + " (valid range: 1-" + totalPages + ").");
            return;
        }

        int fromIndex = (page - 1) * HISTORY_PAGE_SIZE;
        int toIndex = Math.min(fromIndex + HISTORY_PAGE_SIZE, entries.size());

        sender.sendMessage("[AnarchyPhantoms] Commit history (page " + page + "/" + totalPages + "):");
        for (GitHistory.CommitEntry entry : entries.subList(fromIndex, toIndex)) {
            sender.sendMessage("  " + entry.abbrev() + " - " + entry.subject() + " (" + entry.formattedTime() + ")");
        }
        if (page < totalPages) {
            sender.sendMessage("[AnarchyPhantoms] Use /anarchyphantoms git history " + (page + 1) + " for more.");
        }
    }

    /** Shared full-detail rendering used by both "/ap git" and "/ap git info <hash>". */
    private void sendCommitDetail(CommandSender sender, GitHistory.CommitEntry entry) {
        sender.sendMessage("[AnarchyPhantoms] " + entry.abbrev() + " (" + entry.hash() + ")");
        sender.sendMessage("  Date: " + entry.formattedTime());
        sender.sendMessage("  Subject: " + entry.subject());
        if (entry.hasBody()) {
            sender.sendMessage("  Description:");
            for (String line : entry.body().split("\n", -1)) {
                sender.sendMessage("    " + line);
            }
        }
    }

    public PluginSettings getSettings() {
        return settings;
    }

    public PhantomProvocationTracker getProvocationTracker() {
        return provocationTracker;
    }

    public PhantomDebugNotifier getDebugNotifier() {
        return debugNotifier;
    }

    public PhantomSpawnCauseTag getSpawnCauseTag() {
        return spawnCauseTag;
    }

    public BuildInfo getBuildInfo() {
        return buildInfo;
    }

    public GitHistory getGitHistory() {
        return gitHistory;
    }
}