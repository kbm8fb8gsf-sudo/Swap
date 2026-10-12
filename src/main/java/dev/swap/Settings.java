package dev.swap;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/** Values read from config.yml. A single instance is shared and refreshed on reload. */
public final class Settings {
    private String superflatWorld = "superflat";
    private String mainWorld = "main";
    private int initialDelaySeconds;
    private int sessionSeconds;
    private int extendedSessionSeconds;
    private int cooldownSeconds;
    private int extendedCooldownSeconds;
    private final Map<Modifier, Double> chances = new EnumMap<>(Modifier.class);

    public void load(FileConfiguration config) {
        superflatWorld = config.getString("worlds.superflat", "superflat");
        mainWorld = config.getString("worlds.main", "main");
        initialDelaySeconds = seconds(config, "timing.initial-delay-minutes", 5);
        sessionSeconds = seconds(config, "timing.session-minutes", 10);
        extendedSessionSeconds = seconds(config, "timing.extended-session-minutes", 20);
        cooldownSeconds = seconds(config, "timing.cooldown-minutes", 5);
        extendedCooldownSeconds = seconds(config, "timing.extended-cooldown-minutes", 10);
        for (Modifier modifier : Modifier.values()) {
            double chance = config.getDouble("modifiers." + modifier.configKey(), modifier.defaultChance());
            chances.put(modifier, Math.max(0.0, chance));
        }
    }

    private static int seconds(FileConfiguration config, String path, double defaultMinutes) {
        double minutes = Math.max(0.0, config.getDouble(path, defaultMinutes));
        return Math.max(1, (int) Math.round(minutes * 60));
    }

    /** Rolls a single modifier using the configured percentages; null means none. */
    public Modifier rollModifier(Random random) {
        double roll = random.nextDouble() * 100.0;
        double cumulative = 0.0;
        for (Modifier modifier : Modifier.values()) {
            cumulative += chances.getOrDefault(modifier, 0.0);
            if (roll < cumulative) {
                return modifier;
            }
        }
        return null;
    }

    public String superflatWorld() {
        return superflatWorld;
    }

    public String mainWorld() {
        return mainWorld;
    }

    public int initialDelaySeconds() {
        return initialDelaySeconds;
    }

    public int sessionSeconds() {
        return sessionSeconds;
    }

    public int extendedSessionSeconds() {
        return Math.max(extendedSessionSeconds, sessionSeconds);
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public int extendedCooldownSeconds() {
        return Math.max(extendedCooldownSeconds, cooldownSeconds);
    }
}
