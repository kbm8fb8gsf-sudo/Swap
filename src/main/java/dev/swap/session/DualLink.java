package dev.swap.session;

import dev.swap.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Dual Gemstone link: the partner mirrors the chosen player's inventory and health every tick.
 * The partner's own inventory is saved to disk first and handed back when the link ends, so nothing is
 * duplicated or lost (and a crash can be recovered from on the next join).
 */
final class DualLink {
    private final JavaPlugin plugin;
    private final UUID primaryId;
    private final UUID partnerId;
    private final ItemStack[] savedInventory;
    private final double savedHealth;
    private ItemStack[] last;
    private double lastHealth;
    private BukkitTask task;

    DualLink(JavaPlugin plugin, Player primary, Player partner) {
        this.plugin = plugin;
        this.primaryId = primary.getUniqueId();
        this.partnerId = partner.getUniqueId();
        this.savedInventory = snapshot(partner.getInventory().getContents());
        this.savedHealth = partner.getHealth();
        writePending();
    }

    UUID primaryId() {
        return primaryId;
    }

    UUID partnerId() {
        return partnerId;
    }

    /** Pulls the partner in and starts live synchronisation. */
    void start(Player primary, Player partner) {
        partner.closeInventory();
        partner.teleport(primary.getLocation());
        partner.getInventory().setContents(snapshot(primary.getInventory().getContents()));
        applyHealth(partner, primary.getHealth());
        last = snapshot(primary.getInventory().getContents());
        lastHealth = primary.getHealth();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        Player primary = Bukkit.getPlayer(primaryId);
        Player partner = Bukkit.getPlayer(partnerId);
        if (primary == null || partner == null || primary.isDead() || partner.isDead()) {
            return;
        }

        // Inventory: whoever changed since the last sync wins; the chosen player wins a tie.
        ItemStack[] primaryContents = primary.getInventory().getContents();
        if (!same(primaryContents, last)) {
            partner.getInventory().setContents(snapshot(primaryContents));
            last = snapshot(primaryContents);
        } else {
            ItemStack[] partnerContents = partner.getInventory().getContents();
            if (!same(partnerContents, last)) {
                primary.getInventory().setContents(snapshot(partnerContents));
                last = snapshot(partnerContents);
            }
        }

        // Health, the same way.
        double primaryHealth = primary.getHealth();
        double partnerHealth = partner.getHealth();
        if (Math.abs(primaryHealth - lastHealth) > 1.0E-4) {
            applyHealth(partner, primaryHealth);
            lastHealth = primaryHealth;
        } else if (Math.abs(partnerHealth - lastHealth) > 1.0E-4) {
            applyHealth(primary, partnerHealth);
            lastHealth = partnerHealth;
        }
    }

    private static void applyHealth(Player target, double health) {
        double clamped = Math.min(Math.max(health, 0.5), Chat.maxHealth(target));
        target.setHealth(clamped);
    }

    /** Gives the partner their original inventory and health back and forgets the on-disk copy. */
    void restorePartner(Player partner) {
        partner.getInventory().setContents(snapshot(savedInventory));
        if (!partner.isDead() && savedHealth > 0) {
            partner.setHealth(Math.min(savedHealth, Chat.maxHealth(partner)));
        }
        deletePending();
    }

    // ---- inventory helpers ----

    private static ItemStack[] snapshot(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = isEmpty(contents[i]) ? null : contents[i].clone();
        }
        return copy;
    }

    private static boolean same(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            boolean emptyA = isEmpty(a[i]);
            boolean emptyB = isEmpty(b[i]);
            if (emptyA != emptyB) {
                return false;
            }
            if (!emptyA && !a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    // ---- crash safety: the partner's own inventory lives on disk while linked ----

    private static File pendingFile(JavaPlugin plugin, UUID id) {
        return new File(new File(plugin.getDataFolder(), "pending"), id + ".yml");
    }

    private void writePending() {
        File file = pendingFile(plugin, partnerId);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("size", savedInventory.length);
        yaml.set("health", savedHealth);
        for (int i = 0; i < savedInventory.length; i++) {
            if (savedInventory[i] != null) {
                yaml.set("inv." + i, savedInventory[i]);
            }
        }
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save pending inventory for " + partnerId, e);
        }
    }

    private void deletePending() {
        pendingFile(plugin, partnerId).delete();
    }

    /** Called on join: restores an inventory left on disk by a crash or an offline partner. */
    static void recover(JavaPlugin plugin, Player player) {
        File file = pendingFile(plugin, player.getUniqueId());
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int size = yaml.getInt("size", player.getInventory().getContents().length);
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            contents[i] = yaml.getItemStack("inv." + i);
        }
        player.getInventory().setContents(contents);
        double health = yaml.getDouble("health", 0);
        if (health > 0) {
            player.setHealth(Math.min(health, Chat.maxHealth(player)));
        }
        file.delete();
        plugin.getLogger().info("Restored the pre-link inventory of " + player.getName());
    }
}
