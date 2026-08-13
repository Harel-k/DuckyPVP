package com.qducks.duckypvp;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class CombatCommandListener implements Listener {
    private final DuckyPVP plugin;
    private final ArenaManager arena;
    private final CombatManager combat;

    public CombatCommandListener(DuckyPVP plugin, ArenaManager arena, CombatManager combat) {
        this.plugin = plugin;
        this.arena = arena;
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event)) return;

        Player player = event.getPlayer();
        boolean fromArena = arena.isInArena(event.getFrom());
        boolean toArena = arena.isInArena(event.getTo());

        if (fromArena && !toArena && combat.isLocked(player)) {
            event.setCancelled(true);
            combat.sendBlockedExit(player);
            return;
        }

        if (!fromArena && toArena) {
            combat.onArenaEnter(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;

        Player player = event.getPlayer();
        boolean fromArena = arena.isInArena(event.getFrom());
        boolean toArena = arena.isInArena(event.getTo());

        if (fromArena && !toArena && combat.isLocked(player)) {
            event.setCancelled(true);
            combat.sendBlockedExit(player);
            return;
        }

        if (!fromArena && toArena) {
            combat.onArenaEnter(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        combat.tagPvp(attacker, victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!combat.shouldBlockCommand(event.getPlayer(), event.getMessage())) return;
        event.setCancelled(true);
        combat.sendBlockedCommand(event.getPlayer(), combat.extractCommandLabel(event.getMessage()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (arena.isInArena(event.getPlayer().getLocation())) combat.onArenaEnter(event.getPlayer());
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        combat.handleDeath(event.getPlayer());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (arena.isInArena(event.getPlayer().getLocation())) combat.onArenaEnter(event.getPlayer());
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        combat.handleDeath(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        combat.handleQuit(event.getPlayer());
    }

    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private static boolean sameBlock(PlayerMoveEvent event) {
        return event.getFrom().getWorld() == event.getTo().getWorld()
                && event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ();
    }
}
