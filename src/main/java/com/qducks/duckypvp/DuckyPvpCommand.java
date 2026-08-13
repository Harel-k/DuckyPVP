package com.qducks.duckypvp;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DuckyPvpCommand implements CommandExecutor, TabCompleter {
    private final DuckyPVP plugin;
    private final ArenaManager arena;
    private final KitManager kits;
    private final VoteManager votes;

    public DuckyPvpCommand(DuckyPVP plugin, ArenaManager arena, KitManager kits, VoteManager votes) {
        this.plugin = plugin;
        this.arena = arena;
        this.kits = kits;
        this.votes = votes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("duckypvp.admin")) {
            sender.sendMessage(color("&cYou do not have permission."));
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Region: &f" + arena.getWorldName() + ":" + arena.getRegionName()));
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Active kit: &f" + kits.getActiveDisplayName()));
                String queued = votes.getQueuedKit();
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Queued kit: &f" + (queued == null ? "None" : kits.getDisplayName(queued))));
                String leader = votes.getLeadingKit();
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Vote leader: &f" + (leader == null ? "None" : kits.getDisplayName(leader)) + " &7(" + votes.getTotalVotes() + " total votes)"));
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Changed blocks tracked: &f" + arena.getChangedBlockCount()));
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Next reset: &f" + ArenaManager.formatTime(arena.getSecondsUntilReset())));
            }
            case "forcereset" -> {
                arena.forceReset();
                sender.sendMessage(color("&aArena reset immediately. &7The timer and active kit were not changed."));
            }
            case "forcekit" -> {
                if (args.length < 2) {
                    sender.sendMessage(color("&e/duckypvp forcekit <kit>"));
                    return true;
                }
                if (!arena.forceKit(args[1])) {
                    sender.sendMessage(color("&cUnknown kit: &f" + args[1]));
                    return true;
                }
                sender.sendMessage(color("&aForced active kit to &f" + kits.getActiveDisplayName() + "&a. &7Reset timer unchanged."));
            }
            case "queuekit" -> {
                if (args.length < 2) {
                    sender.sendMessage(color("&e/duckypvp queuekit <kit>"));
                    return true;
                }
                if (!votes.queueKit(args[1])) {
                    sender.sendMessage(color("&cUnknown kit: &f" + args[1]));
                    return true;
                }
                sender.sendMessage(color("&aQueued &f" + kits.getDisplayName(args[1]) + " &afor the next natural arena reset."));
            }
            case "clearqueue" -> {
                votes.clearQueue();
                sender.sendMessage(color("&aCleared the queued kit. Player votes will decide the next kit."));
            }
            case "forcerandom" -> {
                String kitId = arena.forceRandomKit();
                sender.sendMessage(color("&aForced random active kit: &f" + kits.getDisplayName(kitId) + "&a. &7Reset timer unchanged."));
            }
            case "reload" -> {
                plugin.reloadDuckyPvp();
                sender.sendMessage(color("&aDuckyPVP configuration reloaded."));
            }
            default -> sender.sendMessage(color("&e/duckypvp <status|forcereset|forcekit|queuekit|clearqueue|forcerandom|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("duckypvp.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String option : List.of("status", "forcereset", "forcekit", "queuekit", "clearqueue", "forcerandom", "reload")) {
                if (option.startsWith(input)) {
                    matches.add(option);
                }
            }
            return matches;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("forcekit") || args[0].equalsIgnoreCase("queuekit"))) {
            String input = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String kitId : kits.getKitIds()) {
                if (kitId.toLowerCase(Locale.ROOT).startsWith(input)) {
                    matches.add(kitId);
                }
            }
            return matches;
        }
        return List.of();
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
