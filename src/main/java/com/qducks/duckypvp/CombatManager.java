package com.qducks.duckypvp;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatManager {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final DuckyPVP plugin;
    private final ArenaManager arena;
    private final Map<UUID, Long> lockedUntil = new HashMap<>();
    private final Map<UUID, Location> lastArenaLocations = new HashMap<>();
    private final Set<String> blockedCommands = new HashSet<>();

    private boolean enabled;
    private long entryLockMillis;
    private long pvpLockMillis;
    private String bypassPermission;
    private String actionBarFormat;
    private String blockedCommandMessage;
    private String blockedExitMessage;
    private String releasedMessage;
    private BukkitTask tickTask;

    public CombatManager(DuckyPVP plugin, ArenaManager arena) {
        this.plugin = plugin;
        this.arena = arena;
        loadSettings();
    }

    public void start() {
        stopTaskOnly();
        if (enabled) {
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        }
    }

    public void stop() {
        stopTaskOnly();
        lockedUntil.clear();
        lastArenaLocations.clear();
    }

    public void reload() {
        stopTaskOnly();
        loadSettings();
        start();
    }

    private void stopTaskOnly() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void loadSettings() {
        enabled = plugin.getConfig().getBoolean("combat.enabled", true);
        entryLockMillis = Math.max(0L, plugin.getConfig().getLong("combat.entry-lock-seconds", 10L)) * 1000L;
        pvpLockMillis = Math.max(0L, plugin.getConfig().getLong("combat.pvp-lock-seconds", 20L)) * 1000L;
        bypassPermission = plugin.getConfig().getString("combat.bypass-permission", "duckypvp.combat.bypass");
        actionBarFormat = plugin.getConfig().getString("combat.ui.actionbar", "&c&lCOMBAT &8» &f%time%s &7• &cYou cannot leave the arena");
        blockedCommandMessage = plugin.getConfig().getString("combat.messages.command-blocked", "&c&lCOMBAT &8» &7You cannot use &f/%command% &7for another &c%time%s&7.");
        blockedExitMessage = plugin.getConfig().getString("combat.messages.exit-blocked", "&c&lCOMBAT &8» &7You cannot leave the PvP arena for another &c%time%s&7.");
        releasedMessage = plugin.getConfig().getString("combat.messages.released", "&a&lCOMBAT &8» &7You can now leave the PvP arena.");

        blockedCommands.clear();
        for (String raw : plugin.getConfig().getStringList("combat.blocked-commands")) {
            if (raw != null && !raw.isBlank()) {
                blockedCommands.add(normalizeCommand(raw));
            }
        }
        if (blockedCommands.isEmpty()) {
            blockedCommands.addAll(Set.of("spawn", "rtp", "wild", "home", "back", "warp", "tpa", "tpahere", "tpaccept"));
        }
    }

    public void onArenaEnter(Player player) {
        if (!enabled || hasBypass(player)) {
            return;
        }
        lastArenaLocations.put(player.getUniqueId(), player.getLocation().clone());
        lockFor(player, entryLockMillis);
    }

    public void onArenaLeave(Player player) {
        lockedUntil.remove(player.getUniqueId());
        lastArenaLocations.remove(player.getUniqueId());
    }

    public void handleQuit(Player player) {
        lockedUntil.remove(player.getUniqueId());
        lastArenaLocations.remove(player.getUniqueId());
    }

    public void handleDeath(Player player) {
        release(player, false);
    }

    public void tagPvp(Player attacker, Player victim) {
        if (!enabled || attacker == null || victim == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (arena.isInArena(attacker.getLocation()) && !hasBypass(attacker)) {
            lastArenaLocations.put(attacker.getUniqueId(), attacker.getLocation().clone());
            lockFor(attacker, pvpLockMillis);
        }
        if (arena.isInArena(victim.getLocation()) && !hasBypass(victim)) {
            lastArenaLocations.put(victim.getUniqueId(), victim.getLocation().clone());
            lockFor(victim, pvpLockMillis);
        }
    }

    private void lockFor(Player player, long durationMillis) {
        if (durationMillis <= 0L) {
            release(player, false);
            return;
        }
        lockedUntil.put(player.getUniqueId(), System.currentTimeMillis() + durationMillis);
        showActionBar(player);
    }

    public boolean isLocked(Player player) {
        if (!enabled || player == null || hasBypass(player)) {
            return false;
        }
        Long until = lockedUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public long getRemainingSeconds(Player player) {
        Long until = lockedUntil.get(player.getUniqueId());
        if (until == null) {
            return 0L;
        }
        long millis = Math.max(0L, until - System.currentTimeMillis());
        return millis == 0L ? 0L : (millis + 999L) / 1000L;
    }

    public boolean shouldBlockExit(Player player, Location from, Location to) {
        return isLocked(player) && from != null && to != null && arena.isInArena(from) && !arena.isInArena(to);
    }

    public void updateLastArenaLocation(Player player, Location location) {
        if (isLocked(player) && location != null && arena.isInArena(location)) {
            lastArenaLocations.put(player.getUniqueId(), location.clone());
        }
    }

    public boolean shouldBlockCommand(Player player, String rawMessage) {
        if (!isLocked(player) || rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String label = rawMessage.trim();
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        if (label.isBlank()) {
            return false;
        }
        label = label.split("\\s+", 2)[0];
        return blockedCommands.contains(normalizeCommand(label));
    }

    public String extractCommandLabel(String rawMessage) {
        String label = rawMessage == null ? "command" : rawMessage.trim();
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        if (label.isBlank()) {
            return "command";
        }
        return normalizeCommand(label.split("\\s+", 2)[0]);
    }

    public void sendBlockedCommand(Player player, String command) {
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', replace(blockedCommandMessage, player).replace("%command%", command)));
    }

    public void sendBlockedExit(Player player) {
        player.sendActionBar(LEGACY.deserialize(replace(blockedExitMessage, player)));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(lockedUntil.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                lockedUntil.remove(uuid);
                lastArenaLocations.remove(uuid);
                continue;
            }
            Long until = lockedUntil.get(uuid);
            if (hasBypass(player) || until == null || until <= now) {
                release(player, true);
                continue;
            }
            if (arena.isInArena(player.getLocation())) {
                lastArenaLocations.put(uuid, player.getLocation().clone());
            }
            showActionBar(player);
        }
    }

    private void release(Player player, boolean notify) {
        boolean wasLocked = lockedUntil.remove(player.getUniqueId()) != null;
        lastArenaLocations.remove(player.getUniqueId());
        if (notify && wasLocked && releasedMessage != null && !releasedMessage.isBlank()) {
            player.sendActionBar(LEGACY.deserialize(releasedMessage));
        }
    }

    private void showActionBar(Player player) {
        if (isLocked(player) && actionBarFormat != null && !actionBarFormat.isBlank()) {
            player.sendActionBar(LEGACY.deserialize(replace(actionBarFormat, player)));
        }
    }

    private String replace(String input, Player player) {
        return (input == null ? "" : input).replace("%time%", String.valueOf(getRemainingSeconds(player)));
    }

    private boolean hasBypass(Player player) {
        return bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission);
    }

    private static String normalizeCommand(String input) {
        String result = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (result.startsWith("/")) {
            result = result.substring(1);
        }
        int colon = result.lastIndexOf(':');
        return colon >= 0 ? result.substring(colon + 1) : result;
    }
}
