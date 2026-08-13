package com.qducks.duckypvp;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatCommandListener implements Listener {
    private final ArenaManager arena;
    private final CombatManager combat;
    private final Map<UUID, Location> lastArenaPosition = new HashMap<>();
    private final Map<UUID, EntityDamageEvent> lastDamageSeen = new HashMap<>();
    private final Set<UUID> insideLastTick = new HashSet<>();

    public CombatCommandListener(DuckyPVP plugin, ArenaManager arena, CombatManager combat) {
        this.arena = arena;
        this.combat = combat;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (combat.shouldBlockCommand(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
            combat.sendBlockedCommand(event.getPlayer(), combat.extractCommandLabel(event.getMessage()));
        }
    }

    private void tick() {
        Set<UUID> online = new HashSet<>();

        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            online.add(uuid);

            if (player.isDead()) {
                combat.handleDeath(player);
                insideLastTick.remove(uuid);
                lastArenaPosition.remove(uuid);
                lastDamageSeen.remove(uuid);
                continue;
            }

            checkNewPlayerDamage(player);

            boolean inside = arena.isInArena(player.getLocation());
            boolean wasInside = insideLastTick.contains(uuid);

            if (inside && !wasInside) {
                combat.onArenaEnter(player);
            }

            if (inside) {
                insideLastTick.add(uuid);
                lastArenaPosition.put(uuid, player.getLocation().clone());
                combat.updateLastArenaLocation(player, player.getLocation());
            } else if (combat.isLocked(player)) {
                Location last = lastArenaPosition.get(uuid);
                if (last != null && last.getWorld() != null) {
                    player.teleport(last, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    insideLastTick.add(uuid);
                    combat.sendBlockedExit(player);
                }
            } else {
                insideLastTick.remove(uuid);
                lastArenaPosition.remove(uuid);
            }
        }

        insideLastTick.removeIf(uuid -> !online.contains(uuid));
        lastArenaPosition.keySet().removeIf(uuid -> !online.contains(uuid));
        lastDamageSeen.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    private void checkNewPlayerDamage(Player victim) {
        EntityDamageEvent damage = victim.getLastDamageCause();
        if (damage == null || lastDamageSeen.get(victim.getUniqueId()) == damage) {
            return;
        }
        lastDamageSeen.put(victim.getUniqueId(), damage);

        Entity causingEntity = damage.getDamageSource().getCausingEntity();
        if (causingEntity instanceof Player attacker
                && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            combat.tagPvp(attacker, victim);
        }
    }
}
