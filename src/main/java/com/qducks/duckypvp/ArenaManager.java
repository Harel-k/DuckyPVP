package com.qducks.duckypvp;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ArenaManager {
    private final DuckyPVP plugin;
    private final KitManager kitManager;
    private final VoteManager voteManager;
    private final Map<BlockKey, BlockData> originalBlocks = new HashMap<>();
    private final Set<UUID> playersInside = new HashSet<>();
    private final Set<EntityType> temporaryEntityTypes = new HashSet<>();
    private final Set<String> excludedRegionNames = new HashSet<>();

    private String worldName;
    private String regionName;
    private long resetIntervalTicks;
    private long nextResetAtMillis;
    private BukkitTask tickTask;
    private BossBar bossBar;
    private boolean resetting;

    public ArenaManager(DuckyPVP plugin, KitManager kitManager, VoteManager voteManager) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.voteManager = voteManager;
        loadSettings();
        rebuildBossBar();
    }

    public void start() {
        stopTaskOnly();
        nextResetAtMillis = System.currentTimeMillis() + resetIntervalTicks * 50L;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        updateBossBar();
    }

    public void stop() {
        stopTaskOnly();
        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    private void stopTaskOnly() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public void reload() {
        stopTaskOnly();
        if (bossBar != null) {
            bossBar.removeAll();
        }
        loadSettings();
        rebuildBossBar();
        start();
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncPlayer(player);
        }
    }

    private void loadSettings() {
        worldName = plugin.getConfig().getString("arena.world", "world");
        regionName = plugin.getConfig().getString("arena.region", "sandpvp").toLowerCase(Locale.ROOT);
        long seconds = Math.max(1L, plugin.getConfig().getLong("arena.reset-interval-seconds", 900L));
        resetIntervalTicks = seconds * 20L;

        excludedRegionNames.clear();
        for (String excluded : plugin.getConfig().getStringList("arena.excluded-regions")) {
            if (!excluded.isBlank()) {
                excludedRegionNames.add(excluded.toLowerCase(Locale.ROOT));
            }
        }

        temporaryEntityTypes.clear();
        for (String raw : plugin.getConfig().getStringList("reset.temporary-entities")) {
            try {
                temporaryEntityTypes.add(EntityType.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown temporary entity type in config.yml: " + raw);
            }
        }
    }

    private void rebuildBossBar() {
        if (!plugin.getConfig().getBoolean("ui.bossbar.enabled", true)) {
            bossBar = null;
            return;
        }

        BarColor barColor;
        BarStyle barStyle;
        try {
            barColor = BarColor.valueOf(plugin.getConfig().getString("ui.bossbar.color", "YELLOW").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            barColor = BarColor.YELLOW;
        }
        try {
            barStyle = BarStyle.valueOf(plugin.getConfig().getString("ui.bossbar.style", "SOLID").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            barStyle = BarStyle.SOLID;
        }
        bossBar = Bukkit.createBossBar("DuckyPVP", barColor, barStyle);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now >= nextResetAtMillis) {
            naturalReset();
        }
        updateBossBar();
    }

    public boolean isInArena(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equalsIgnoreCase(worldName)) {
            return false;
        }

        RegionManager manager = WorldGuard.getInstance()
                .getPlatform()
                .getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));
        if (manager == null) {
            return false;
        }

        BlockVector3 point = BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        ProtectedRegion arenaRegion = manager.getRegion(regionName);
        if (arenaRegion == null || !arenaRegion.contains(point)) {
            return false;
        }

        for (String excludedName : excludedRegionNames) {
            ProtectedRegion excluded = manager.getRegion(excludedName);
            if (excluded != null && excluded.contains(point)) {
                return false;
            }
        }
        return true;
    }

    public void syncPlayer(Player player) {
        boolean nowInside = isInArena(player.getLocation());
        boolean wasInside = playersInside.contains(player.getUniqueId());

        if (nowInside && !wasInside) {
            enterPlayer(player);
        } else if (!nowInside && wasInside) {
            leavePlayer(player);
        } else if (!nowInside && kitManager.hasBackup(player.getUniqueId())) {
            if (bossBar != null) {
                bossBar.removePlayer(player);
            }
            kitManager.restoreStaleBackup(player);
        } else if (nowInside && bossBar != null) {
            bossBar.addPlayer(player);
        }
    }

    public void handlePositionChange(Player player, Location destination) {
        if (destination == null) {
            return;
        }

        boolean nowInside = isInArena(destination);
        boolean wasInside = playersInside.contains(player.getUniqueId());
        if (nowInside == wasInside) {
            return;
        }

        if (nowInside) {
            enterPlayer(player);
        } else {
            leavePlayer(player);
        }
    }

    private void enterPlayer(Player player) {
        playersInside.add(player.getUniqueId());
        kitManager.enterArena(player);
        if (bossBar != null) {
            bossBar.addPlayer(player);
        }
        sendEntryTitle(player);
    }

    private void leavePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        playersInside.remove(uuid);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (isInArena(player.getLocation())) {
                playersInside.add(uuid);
                if (bossBar != null) {
                    bossBar.addPlayer(player);
                }
                return;
            }
            kitManager.leaveArena(player);
        }, 2L);
    }

    public void handleQuit(Player player) {
        playersInside.remove(player.getUniqueId());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
        if (kitManager.hasBackup(player.getUniqueId())) {
            kitManager.leaveArena(player);
        }
    }

    public boolean isTrackedInside(Player player) {
        return playersInside.contains(player.getUniqueId());
    }

    public void recordOriginal(Block block, BlockData originalData) {
        if (resetting || block == null || originalData == null || !isInArena(block.getLocation())) {
            return;
        }
        BlockKey key = new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        originalBlocks.putIfAbsent(key, originalData.clone());
    }

    public void naturalReset() {
        performArenaCleanup();

        String nextKit = voteManager.resolveNextKit();
        kitManager.activateKit(nextKit);
        rekitArenaPlayers(true);

        nextResetAtMillis = System.currentTimeMillis() + resetIntervalTicks * 50L;
        broadcastReset();
        updateBossBar();
    }

    public void forceReset() {
        performArenaCleanup();
        updateBossBar();
    }

    public boolean forceKit(String kitId) {
        if (!kitManager.activateKit(kitId)) {
            return false;
        }
        rekitArenaPlayers(true);
        updateBossBar();
        return true;
    }

    public String forceRandomKit() {
        String kitId = kitManager.pickRandomKitIdDifferentFromActive();
        forceKit(kitId);
        return kitId;
    }

    private void performArenaCleanup() {
        resetting = true;
        try {
            for (Map.Entry<BlockKey, BlockData> entry : originalBlocks.entrySet()) {
                Block block = entry.getKey().resolve();
                if (block != null) {
                    block.setBlockData(entry.getValue(), false);
                }
            }
            originalBlocks.clear();

            if (plugin.getConfig().getBoolean("reset.remove-temporary-entities", true)) {
                removeTemporaryEntities();
            }
        } finally {
            resetting = false;
        }
    }

    private void rekitArenaPlayers(boolean showTitle) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInArena(player.getLocation())) {
                playersInside.add(player.getUniqueId());
                kitManager.rekit(player);
                if (bossBar != null) {
                    bossBar.addPlayer(player);
                }
                if (showTitle) {
                    sendKitChangedTitle(player);
                }
            } else if (playersInside.remove(player.getUniqueId())) {
                if (bossBar != null) {
                    bossBar.removePlayer(player);
                }
                kitManager.leaveArena(player);
            }
        }
    }

    private void removeTemporaryEntities() {
        World world = Bukkit.getWorld(worldName);
        if (world == null || temporaryEntityTypes.isEmpty()) {
            return;
        }

        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (temporaryEntityTypes.contains(entity.getType()) && isInArena(entity.getLocation())) {
                entity.remove();
            }
        }
    }

    private void sendEntryTitle(Player player) {
        if (!plugin.getConfig().getBoolean("ui.entry-title.enabled", true)) {
            return;
        }
        String title = placeholders(plugin.getConfig().getString("ui.entry-title.title", "&6&lDUCKY PVP"));
        String subtitle = placeholders(plugin.getConfig().getString(
                "ui.entry-title.subtitle",
                "&7Kit: &f%kit% &8• &7Reset in &f%time%"
        ));
        int fadeIn = plugin.getConfig().getInt("ui.entry-title.fade-in", 10);
        int stay = plugin.getConfig().getInt("ui.entry-title.stay", 50);
        int fadeOut = plugin.getConfig().getInt("ui.entry-title.fade-out", 10);
        player.sendTitle(color(title), color(subtitle), fadeIn, stay, fadeOut);
    }

    private void sendKitChangedTitle(Player player) {
        if (!plugin.getConfig().getBoolean("ui.kit-change-title.enabled", true)) {
            return;
        }
        String title = placeholders(plugin.getConfig().getString("ui.kit-change-title.title", "&e&lNEW KIT"));
        String subtitle = placeholders(plugin.getConfig().getString("ui.kit-change-title.subtitle", "&f%kit%"));
        player.sendTitle(color(title), color(subtitle), 5, 35, 10);
    }

    private void updateBossBar() {
        if (bossBar == null) {
            return;
        }

        long remainingMillis = Math.max(0L, nextResetAtMillis - System.currentTimeMillis());
        long intervalMillis = Math.max(1000L, resetIntervalTicks * 50L);
        double progress = Math.max(0.0, Math.min(1.0, remainingMillis / (double) intervalMillis));
        bossBar.setProgress(progress);
        bossBar.setTitle(color(placeholders(plugin.getConfig().getString(
                "ui.bossbar.title",
                "&6Kit: &f%kit% &8• &eReset in &f%time%"
        ))));
    }

    private void broadcastReset() {
        if (!plugin.getConfig().getBoolean("arena.broadcast-reset", true)) {
            return;
        }
        String message = plugin.getConfig().getString(
                "arena.reset-message",
                "&6&lDUCKY PVP &8» &eArena reset! New kit: &f%kit%"
        );
        Bukkit.broadcastMessage(color(placeholders(message)));
    }

    private String placeholders(String input) {
        String queued = voteManager.getQueuedKit();
        String leading = voteManager.getLeadingKit();
        return (input == null ? "" : input)
                .replace("%kit%", kitManager.getActiveDisplayName())
                .replace("%time%", formatTime(getSecondsUntilReset()))
                .replace("%votes%", String.valueOf(voteManager.getTotalVotes()))
                .replace("%queued%", queued == null ? "None" : kitManager.getDisplayName(queued))
                .replace("%leading%", leading == null ? "None" : kitManager.getDisplayName(leading));
    }

    public String getRegionName() {
        return regionName;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getChangedBlockCount() {
        return originalBlocks.size();
    }

    public long getSecondsUntilReset() {
        return Math.max(0L, (nextResetAtMillis - System.currentTimeMillis() + 999L) / 1000L);
    }

    public static String formatTime(long totalSeconds) {
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private record BlockKey(String world, int x, int y, int z) {
        private Block resolve() {
            World bukkitWorld = Bukkit.getWorld(world);
            return bukkitWorld == null ? null : bukkitWorld.getBlockAt(x, y, z);
        }
    }
}
