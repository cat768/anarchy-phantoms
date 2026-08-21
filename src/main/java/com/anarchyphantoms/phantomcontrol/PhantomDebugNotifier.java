package com.anarchyphantoms.phantomcontrol;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Single source of truth for AnarchyPhantoms' debug output.
 *
 * Every debug line goes to two places when debug mode is on:
 *  - The server log/console, always (as before).
 *  - In-game chat, but ONLY to players holding {@code anarchyphantoms.debug}.
 *    Everyone else - including regular ops without that specific node - sees
 *    nothing. {@code anarchyphantoms.admin} does NOT imply this permission;
 *    it's granted separately so debug-spam can be opted into per-player
 *    without also handing out reload/toggle access, and vice versa.
 *
 * This class intentionally has no knowledge of *why* a message is being
 * logged (spawn, target-change, etc.) - callers build a human-readable line
 * and hand it here. Keeping formatting decisions in the individual listeners
 * (which already know the domain context) and keeping delivery/fan-out logic
 * here avoids a god-class that both decides content and does dispatch.
 */
public final class PhantomDebugNotifier {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Permission required to receive debug lines in chat. Console always sees them regardless. */
    public static final String DEBUG_VIEW_PERMISSION = "anarchyphantoms.debug";

    private final AnarchyPhantomsPlugin plugin;

    public PhantomDebugNotifier(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Emits a debug line, prefixed with the current wall-clock time, to the
     * console and to every currently-online player holding the debug-view
     * permission. No-ops entirely (skips even the timestamp formatting) if
     * debug mode is currently off, so hot paths (target changes, per-tick
     * spawn attempts) don't pay any cost when debug is disabled.
     */
    public void debug(String message) {
        if (!plugin.getSettings().isDebugEnabled()) {
            return;
        }

        String timestamp = LocalTime.now().format(TIME_FORMAT);
        String line = "[" + timestamp + "] " + message;

        Logger logger = plugin.getLogger();
        logger.info("[AP-DEBUG] " + line);

        Component chatLine = Component.text("[AP-DEBUG] ", NamedTextColor.DARK_GRAY)
                .append(Component.text(line, NamedTextColor.GRAY));

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(DEBUG_VIEW_PERMISSION)) {
                player.sendMessage(chatLine);
            }
        }
    }

    /**
     * Convenience overload matching the previous per-player debug helper's
     * call sites (PhantomEndSpawner's periodic per-player eligibility scan),
     * just prefixing the player's name onto the message.
     */
    public void debug(Player player, String message) {
        debug(player.getName() + ": " + message);
    }

    /**
     * Describes a phantom spawn: exact location, world, and - critically -
     * what caused it and what surface it spawned above. Used by both the
     * passive CreatureSpawnEvent listener (PhantomSpawnListener, natural/
     * vanilla-routed causes) and the active End spawner (PhantomEndSpawner,
     * always attributable to a specific player).
     *
     * @param location   where the phantom appeared.
     * @param causeLabel human-readable cause, e.g. "player Notch (insomnia)"
     *                   or "spawn reason NATURAL" or "spawn reason COMMAND".
     */
    public void spawn(Location location, String causeLabel) {
        if (!plugin.getSettings().isDebugEnabled()) {
            return;
        }

        World world = location.getWorld();
        String worldName = world != null ? world.getName() : "unknown-world";
        String coords = location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
        String surface = describeSurfaceBelow(location);

        debug("phantom spawned at " + coords + " in " + worldName
                + " | cause: " + causeLabel
                + " | surface: " + surface);
    }

    /**
     * Walks downward from the spawn point to find and describe the first
     * non-air block, e.g. "END_STONE (3 blocks below)". Mirrors the same
     * downward-scan approach PhantomSpawnListener uses for its veto check,
     * but purely for reporting - this never cancels or influences anything.
     */
    private String describeSurfaceBelow(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return "unknown (no world)";
        }

        int startY = location.getBlockY();
        int minY = world.getMinHeight();
        int maxScan = plugin.getSettings().getSurfaceCheckDepth();

        for (int i = 1; i <= maxScan; i++) {
            int y = startY - i;
            if (y < minY) {
                break;
            }
            Block block = world.getBlockAt(location.getBlockX(), y, location.getBlockZ());
            Material type = block.getType();
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                continue;
            }
            return type + " (" + i + " block" + (i == 1 ? "" : "s") + " below)";
        }
        return "none found within " + maxScan + " blocks below";
    }

    /**
     * Reports a passive -> aggressive transition (a phantom was just
     * provoked). Includes what provoked it and where, so log/chat output
     * reads as a timeline of a specific phantom's life rather than opaque
     * boolean flips.
     */
    public void becameAggressive(Location location, String provokedByLabel) {
        if (!plugin.getSettings().isDebugEnabled()) {
            return;
        }
        String coords = location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
        debug("phantom turned AGGRESSIVE at " + coords + " | provoked by: " + provokedByLabel);
    }

    /**
     * Reports an aggressive -> passive transition (a phantom's provocation
     * window expired and it reverted to passive/silent).
     */
    public void becamePassive(Location location, String reasonLabel) {
        if (!plugin.getSettings().isDebugEnabled()) {
            return;
        }
        String coords = location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
        debug("phantom reverted to PASSIVE at " + coords + " | reason: " + reasonLabel);
    }
}