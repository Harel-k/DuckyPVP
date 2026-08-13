package com.qducks.duckypvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class VoteManager {
    private final KitManager kits;
    private final Map<UUID, String> votes = new HashMap<>();
    private final Random random = new Random();
    private String queuedKit;

    public VoteManager(KitManager kits) {
        this.kits = kits;
    }

    public boolean castVote(UUID playerId, String kitId) {
        if (!kits.isValidKit(kitId)) {
            return false;
        }
        votes.put(playerId, kitId.toLowerCase());
        return true;
    }

    public String getVote(UUID playerId) {
        return votes.get(playerId);
    }

    public int getVoteCount(String kitId) {
        int count = 0;
        for (String votedKit : votes.values()) {
            if (votedKit.equalsIgnoreCase(kitId)) {
                count++;
            }
        }
        return count;
    }

    public int getTotalVotes() {
        return votes.size();
    }

    public boolean queueKit(String kitId) {
        if (!kits.isValidKit(kitId)) {
            return false;
        }
        queuedKit = kitId.toLowerCase();
        return true;
    }

    public void clearQueue() {
        queuedKit = null;
    }

    public String getQueuedKit() {
        return queuedKit;
    }

    public String resolveNextKit() {
        String next;
        if (queuedKit != null && kits.isValidKit(queuedKit)) {
            next = queuedKit;
        } else {
            next = resolveVoteWinner();
            if (next == null) {
                next = kits.pickRandomKitIdDifferentFromActive();
            }
        }

        queuedKit = null;
        votes.clear();
        return next;
    }

    private String resolveVoteWinner() {
        if (votes.isEmpty()) {
            return null;
        }

        Map<String, Integer> counts = new HashMap<>();
        for (String kitId : votes.values()) {
            if (kits.isValidKit(kitId)) {
                counts.merge(kitId, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }

        int best = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> tied = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == best) {
                tied.add(entry.getKey());
            }
        }
        return tied.get(random.nextInt(tied.size()));
    }

    public String getLeadingKit() {
        if (votes.isEmpty()) {
            return null;
        }
        String leader = null;
        int best = -1;
        for (String kitId : kits.getKitIds()) {
            int count = getVoteCount(kitId);
            if (count > best) {
                best = count;
                leader = kitId;
            }
        }
        return best <= 0 ? null : leader;
    }

    public void sanitizeAfterReload() {
        votes.entrySet().removeIf(entry -> !kits.isValidKit(entry.getValue()));
        if (queuedKit != null && !kits.isValidKit(queuedKit)) {
            queuedKit = null;
        }
    }
}
