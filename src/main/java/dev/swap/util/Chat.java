package dev.swap.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/** MiniMessage helpers, so announcements can use rainbow and gradient colours. */
public final class Chat {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gradient:#fbbf24:#f59e0b>Swap</gradient><dark_gray>] <gray>";

    private Chat() {
    }

    public static Component mm(String text, TagResolver... resolvers) {
        return MM.deserialize(text, resolvers);
    }

    /** A prefixed chat line. */
    public static Component msg(String text, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + text, resolvers);
    }

    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public static void broadcast(Component component) {
        Bukkit.broadcast(component);
    }

    /** 125 seconds -> "2:05". */
    public static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** 600 seconds -> "10 minutes". */
    public static String duration(int seconds) {
        if (seconds % 60 == 0) {
            int m = seconds / 60;
            return m + (m == 1 ? " minute" : " minutes");
        }
        return clock(seconds);
    }

    public static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }
}
