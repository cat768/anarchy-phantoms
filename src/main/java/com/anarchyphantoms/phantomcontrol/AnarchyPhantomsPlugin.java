package com.anarchyphantoms.phantomcontrol;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.settings = new PluginSettings(this);
        this.provocationTracker = new PhantomProvocationTracker(this);

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
            sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms reload | debug <on|off>");
            return true;
        }
        return false;
    }

    public PluginSettings getSettings() {
        return settings;
    }

    public PhantomProvocationTracker getProvocationTracker() {
        return provocationTracker;
    }
}