package com.qducks.duckypvp;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class CombatCommandListener implements Listener {
    private final CombatManager combat;

    public CombatCommandListener(CombatManager combat) {
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (combat.shouldBlockCommand(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
            combat.sendBlockedCommand(event.getPlayer(), combat.extractCommandLabel(event.getMessage()));
        }
    }
}
