package dev.swap;

import dev.swap.util.Chat;
import net.kyori.adventure.text.Component;

import java.util.Locale;

/** Random per-session modifiers. At most one applies to a session. */
public enum Modifier {
    PARTY("<bold><rainbow>", 10.0, "Everyone online joins the main world for this session!"),
    FORTUNATE("<bold><gradient:#1d4ed8:#38bdf8>", 1.0, "A Fortune V pickaxe is yours for this session."),
    LOOTABLE("<bold><gradient:#2563eb:#67e8f9>", 1.0, "A Looting V sword is yours for this session."),
    DEFLATION("<bold><gradient:#15803d:#86efac>", 2.0, "Every villager trade costs just 1 emerald."),
    INFLATION("<bold><gradient:#b91c1c:#fb923c>", 2.0, "Every villager trade costs 10% more."),
    THE_GREAT_RESET("<bold><gradient:#7f1d1d:#ef4444>", 0.1, "The main world was wiped and regenerated with a new seed!"),
    INVINCIBLE("<bold><gradient:#ffffff:#9ca3af>", 10.0, "Resistance 225: nothing can hurt you."),
    DAMAGED("<bold><gradient:#6d28d9:#e879f9>", 5.0, "The session starts on half health.");

    private final String openTags;
    private final double defaultChance;
    private final String description;

    Modifier(String openTags, double defaultChance, String description) {
        this.openTags = openTags;
        this.defaultChance = defaultChance;
        this.description = description;
    }

    public String displayName() {
        return name().replace('_', ' ');
    }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    public double defaultChance() {
        return defaultChance;
    }

    public String description() {
        return description;
    }

    /** The modifier's name in its gradient / rainbow chat colour. */
    public Component styledName() {
        return Chat.mm(openTags + displayName());
    }

    public static Modifier parse(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (Modifier m : values()) {
            if (m.name().equals(normalized)) {
                return m;
            }
        }
        return null;
    }
}
