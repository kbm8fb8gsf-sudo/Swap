package dev.swap.session;

import dev.swap.Modifier;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** State of the one active main-world session. */
final class Session {
    final UUID primaryId;
    final Modifier modifier; // null when no modifier was rolled
    final Set<UUID> participants = new LinkedHashSet<>();
    final Map<UUID, Double> healthBefore = new HashMap<>();
    int totalSeconds;
    int remainingSeconds;
    boolean extended;
    DualLink link;
    Location lastKnown;

    Session(UUID primaryId, Modifier modifier, int totalSeconds) {
        this.primaryId = primaryId;
        this.modifier = modifier;
        this.totalSeconds = totalSeconds;
        this.remainingSeconds = totalSeconds;
    }

    boolean isParty() {
        return modifier == Modifier.PARTY;
    }
}
