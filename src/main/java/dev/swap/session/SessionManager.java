package dev.swap.session;

import dev.swap.Modifier;
import dev.swap.Settings;
import dev.swap.item.Items;
import dev.swap.util.Chat;
import dev.swap.world.WorldService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Merchant;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The master scheduler: chooses a player every cycle, runs their session, and handles everything that can
 * end it early (death, logging out, admin command).
 */
public final class SessionManager {
    private enum Phase { COOLDOWN, ACTIVE }

    /** Resistance level 225 is amplifier 224. */
    private static final int INVINCIBLE_AMPLIFIER = 224;

    private final JavaPlugin plugin;
    private final Settings settings;
    private final WorldService worlds;
    private final Items items;
    private final TradeAdjuster trades = new TradeAdjuster();
    private final Random random = new Random();
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    /** Partners who died while linked; their own inventory is returned once they respawn. */
    private final Map<UUID, DualLink> deferredRestores = new HashMap<>();

    private Phase phase = Phase.COOLDOWN;
    private int cooldownRemaining;
    private int cooldownTotal;
    private Session session;
    private BukkitTask ticker;

    public SessionManager(JavaPlugin plugin, Settings settings, WorldService worlds, Items items) {
        this.plugin = plugin;
        this.settings = settings;
        this.worlds = worlds;
        this.items = items;
    }

    // ---- lifecycle ----

    public void start() {
        cooldownTotal = settings.initialDelaySeconds();
        cooldownRemaining = cooldownTotal;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (session != null) {
            endSession(session, "the server is shutting down");
        }
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        trades.restoreAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
    }

    // ---- the master timer (runs once per second) ----

    private void tick() {
        if (phase == Phase.COOLDOWN) {
            if (cooldownRemaining > 0) {
                cooldownRemaining--;
            }
            if (cooldownRemaining <= 0) {
                tryPick();
            }
        } else if (session != null) {
            Session s = session;
            s.remainingSeconds--;
            trackLocation(s);
            maintainModifier(s);
            warn(s);
            if (s.remainingSeconds <= 0) {
                endSession(s, "time is up");
            }
        }
        refreshBar();
    }

    private void tryPick() {
        List<Player> eligible = eligiblePlayers();
        if (eligible.isEmpty()) {
            return; // keep waiting; try again next second
        }
        startSession(eligible.get(random.nextInt(eligible.size())), null, false);
    }

    private List<Player> eligiblePlayers() {
        List<Player> list = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isDead() && player.getGameMode() != GameMode.SPECTATOR) {
                list.add(player);
            }
        }
        return list;
    }

    // ---- starting a session ----

    /** @return an error message, or null on success */
    public String forceStart(Player chosen, Modifier modifier, boolean modifierSpecified) {
        if (session != null) {
            return "A session is already running.";
        }
        if (chosen == null || !chosen.isOnline()) {
            return "No player is available to choose.";
        }
        return startSession(chosen, modifier, modifierSpecified) ? null : "The session could not be started.";
    }

    private boolean startSession(Player chosen, Modifier forcedModifier, boolean forced) {
        Modifier modifier = forced ? forcedModifier : settings.rollModifier(random);

        // Modifiers that change the world must happen before anyone enters it.
        if (modifier == Modifier.THE_GREAT_RESET) {
            worlds.resetMain();
        }
        Location entry = worlds.mainEntry();
        if (entry == null || entry.getWorld() == null) {
            plugin.getLogger().severe("The main world is not available; retrying in 30 seconds.");
            cooldownRemaining = 30;
            cooldownTotal = 30;
            return false;
        }

        Session s = new Session(chosen.getUniqueId(), modifier, settings.sessionSeconds());
        s.participants.add(chosen.getUniqueId());
        if (modifier == Modifier.PARTY) {
            for (Player player : eligiblePlayers()) {
                s.participants.add(player.getUniqueId());
            }
        }
        s.lastKnown = entry.clone();
        session = s;
        phase = Phase.ACTIVE;

        // Per-session modifier effects are applied before the teleport.
        applyModifier(s, chosen);

        for (UUID id : s.participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.closeInventory();
                player.teleport(entry);
            }
        }

        Chat.broadcast(Chat.msg("<yellow><player></yellow> has been sent to the <green>main world</green> for <white><time></white>!",
                Chat.text("player", chosen.getName()), Chat.text("time", Chat.duration(s.totalSeconds))));
        if (modifier != null) {
            Chat.broadcast(Chat.msg("Modifier: <mod> <dark_gray>-</dark_gray> <gray><desc>",
                    Chat.comp("mod", modifier.styledName()), Chat.text("desc", modifier.description())));
            chosen.showTitle(Title.title(Chat.mm("<gold>You're up!"), modifier.styledName()));
        } else {
            chosen.showTitle(Title.title(Chat.mm("<gold>You're up!"), Chat.mm("<gray>Make it count.")));
        }
        refreshBar();
        return true;
    }

    private void applyModifier(Session s, Player primary) {
        if (s.modifier == null) {
            return;
        }
        switch (s.modifier) {
            case FORTUNATE -> items.giveTemporary(primary, items.fortunePickaxe());
            case LOOTABLE -> items.giveTemporary(primary, items.lootingSword());
            case INVINCIBLE -> applyInvincible(primary);
            case DAMAGED -> {
                s.healthBefore.put(primary.getUniqueId(), primary.getHealth());
                primary.setHealth(Math.max(1.0, Chat.maxHealth(primary) / 2.0));
            }
            default -> {
                // PARTY, DEFLATION, INFLATION and THE_GREAT_RESET need nothing per player.
            }
        }
    }

    private void applyInvincible(Player player) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.RESISTANCE);
        if (current == null || current.getAmplifier() < INVINCIBLE_AMPLIFIER) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION,
                    INVINCIBLE_AMPLIFIER, false, false, true));
        }
    }

    private void removeInvincible(Player player) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.RESISTANCE);
        if (current != null && current.getAmplifier() == INVINCIBLE_AMPLIFIER) {
            player.removePotionEffect(PotionEffectType.RESISTANCE);
        }
    }

    // ---- per-second upkeep ----

    private void trackLocation(Session s) {
        Player primary = Bukkit.getPlayer(s.primaryId);
        if (primary != null && worlds.isMain(primary.getWorld())) {
            s.lastKnown = primary.getLocation();
        }
    }

    private void maintainModifier(Session s) {
        if (s.modifier != Modifier.INVINCIBLE) {
            return;
        }
        for (UUID id : s.participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && !player.isDead()) {
                applyInvincible(player); // e.g. a milk bucket can't strip it
            }
        }
    }

    private void warn(Session s) {
        int left = s.remainingSeconds;
        if (left == 60 || left == 30 || left == 10) {
            for (UUID id : s.participants) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.sendActionBar(Chat.mm("<red>Returning to superflat in <time>",
                            Chat.text("time", Chat.duration(left))));
                }
            }
        }
    }

    private void refreshBar() {
        if (phase == Phase.ACTIVE && session != null) {
            Player primary = Bukkit.getPlayer(session.primaryId);
            String name = primary != null ? primary.getName() : "Someone";
            Component title = Chat.mm("<green><bold><name></bold></green> <gray>is in the main world <dark_gray>|</dark_gray> <white><time>",
                    Chat.text("name", name), Chat.text("time", Chat.clock(session.remainingSeconds)));
            if (session.modifier != null) {
                title = title.append(Chat.mm(" <dark_gray>|</dark_gray> ")).append(session.modifier.styledName());
            }
            bar.name(title);
            bar.color(BossBar.Color.GREEN);
            bar.progress(fraction(session.remainingSeconds, session.totalSeconds));
        } else {
            boolean waiting = cooldownRemaining <= 0;
            bar.name(waiting
                    ? Chat.mm("<yellow>Waiting for players...")
                    : Chat.mm("<yellow>Next swap in <white><time>", Chat.text("time", Chat.clock(cooldownRemaining))));
            bar.color(BossBar.Color.YELLOW);
            bar.progress(waiting ? 0f : fraction(cooldownRemaining, cooldownTotal));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
    }

    private static float fraction(int remaining, int total) {
        if (total <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) remaining / (float) total));
    }

    // ---- ending a session ----

    public void forceEnd() {
        if (session != null) {
            endSession(session, "an admin ended it");
        }
    }

    private void endSession(Session s, String reason) {
        if (session != s) {
            return;
        }
        session = null;
        phase = Phase.COOLDOWN;
        cooldownTotal = s.extended ? settings.extendedCooldownSeconds() : settings.cooldownSeconds();
        cooldownRemaining = cooldownTotal;

        if (s.link != null) {
            releasePartner(s, Bukkit.getPlayer(s.link.partnerId()));
        }
        // Everyone else leaves in order, with the chosen player last so their position is the one saved.
        List<UUID> ids = new ArrayList<>(s.participants);
        if (ids.remove(s.primaryId)) {
            ids.add(s.primaryId);
        }
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && !player.isDead()) {
                recordLeave(s, player);
                sendOut(s, player);
            }
        }
        s.participants.clear();

        Chat.broadcast(Chat.msg("The session is over (<reason>). The next player is chosen in <white><time></white>.",
                Chat.text("reason", reason), Chat.text("time", Chat.duration(cooldownTotal))));
        if (ticker != null) {
            refreshBar();
        }
    }

    /** Stores where a player left the main world, so the next player can start at the same spot. */
    private void recordLeave(Session s, Player player) {
        if (worlds.isMain(player.getWorld())) {
            worlds.setLastMainLocation(player.getLocation());
        } else if (player.getUniqueId().equals(s.primaryId) && s.lastKnown != null) {
            worlds.setLastMainLocation(s.lastKnown);
        }
    }

    /** Reverts every effect of the session on a living player and returns them to superflat. */
    private void sendOut(Session s, Player player) {
        trades.restore(player);
        player.closeInventory();
        items.removeTemporary(player);
        removeInvincible(player);
        Double before = s.healthBefore.get(player.getUniqueId());
        if (s.modifier == Modifier.DAMAGED && before != null && !player.isDead()) {
            player.setHealth(Math.min(Chat.maxHealth(player), Math.max(player.getHealth(), before)));
        }
        player.teleport(worlds.superflatSpawn());
    }

    private void releasePartner(Session s, Player partner) {
        DualLink link = s.link;
        if (link == null) {
            return;
        }
        link.stop();
        s.link = null;
        s.participants.remove(link.partnerId());
        if (partner != null) {
            trades.restore(partner);
            partner.closeInventory();
            link.restorePartner(partner);
            removeInvincible(partner);
            if (!partner.isDead()) {
                partner.teleport(worlds.superflatSpawn());
            }
        }
    }

    // ---- player events ----

    public void onJoin(Player player) {
        DualLink.recover(plugin, player);
        items.removeTemporary(player);
        removeInvincible(player);
        items.discoverRecipes(player);
        if (!isParticipant(player) && !player.hasPermission("swap.bypass")
                && !player.getWorld().getName().equals(settings.superflatWorld())) {
            player.teleport(worlds.superflatSpawn());
        }
        player.showBossBar(bar);
    }

    public void onQuit(Player player) {
        Session s = session;
        if (s == null || !s.participants.contains(player.getUniqueId())) {
            return;
        }
        UUID id = player.getUniqueId();
        if (s.link != null && s.link.partnerId().equals(id)) {
            releasePartner(s, player); // the chosen player simply continues alone
            return;
        }
        boolean primary = id.equals(s.primaryId);
        recordLeave(s, player);
        s.participants.remove(id);
        sendOut(s, player);
        if (s.participants.isEmpty() || (primary && !s.isParty())) {
            endSession(s, player.getName() + " left the server");
        }
    }

    public void onDeath(Player player, PlayerDeathEvent event) {
        Session s = session;
        if (s == null || !s.participants.contains(player.getUniqueId())) {
            return;
        }
        UUID id = player.getUniqueId();
        event.getDrops().removeIf(items::isTemporary);

        DualLink link = s.link;
        boolean partnerDied = link != null && link.partnerId().equals(id);
        boolean primary = id.equals(s.primaryId);

        if (!partnerDied) {
            recordLeave(s, player);
        }
        trades.restore(player);
        items.removeTemporary(player);
        s.participants.remove(id);

        if (link != null) {
            link.stop();
            s.link = null;
            if (partnerDied) {
                // The shared inventory drops from the partner's body; the chosen player's copy must vanish.
                deferredRestores.put(id, link);
                Player chosen = Bukkit.getPlayer(link.primaryId());
                if (chosen != null && !event.getKeepInventory()) {
                    chosen.getInventory().clear();
                }
            } else {
                s.participants.remove(link.partnerId());
                Player partner = Bukkit.getPlayer(link.partnerId());
                if (partner != null) {
                    trades.restore(partner);
                    partner.closeInventory();
                    link.restorePartner(partner);
                    removeInvincible(partner);
                    partner.teleport(worlds.superflatSpawn());
                }
            }
        }

        // "Instantly teleported back": skip the death screen; the respawn handler picks the location.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        });

        if (s.participants.isEmpty() || (primary && !s.isParty()) || partnerDied) {
            endSession(s, player.getName() + " died");
        }
    }

    public void afterRespawn(Player player) {
        DualLink link = deferredRestores.remove(player.getUniqueId());
        if (link != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    link.restorePartner(player);
                }
            });
        }
    }

    // ---- villager price modifiers ----

    public void onMerchantOpen(Player player, Merchant merchant) {
        Session s = session;
        if (s != null && s.participants.contains(player.getUniqueId())
                && (s.modifier == Modifier.DEFLATION || s.modifier == Modifier.INFLATION)) {
            trades.onOpen(player, merchant, s.modifier);
        }
    }

    public void onMerchantClose(Player player) {
        trades.restore(player);
    }

    // ---- queries used by listeners and commands ----

    public boolean isParticipant(Player player) {
        return session != null && session.participants.contains(player.getUniqueId());
    }

    public boolean isLinkedPartner(Player player) {
        return session != null && session.link != null && session.link.partnerId().equals(player.getUniqueId());
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (phase == Phase.ACTIVE && session != null) {
            Player primary = Bukkit.getPlayer(session.primaryId);
            lines.add("<yellow>" + (primary != null ? primary.getName() : "A player") + "</yellow> is in the main world, <white>"
                    + Chat.clock(session.remainingSeconds) + "</white> left"
                    + (session.extended ? " <gray>(extended)</gray>" : "") + ".");
            if (session.modifier != null) {
                lines.add("Modifier: " + session.modifier.displayName() + " <gray>- " + session.modifier.description());
            }
            if (session.link != null) {
                Player partner = Bukkit.getPlayer(session.link.partnerId());
                lines.add("Dual Gemstone partner: <yellow>" + (partner != null ? partner.getName() : "offline") + "</yellow>");
            }
        } else if (cooldownRemaining > 0) {
            lines.add("Next player is chosen in <white>" + Chat.clock(cooldownRemaining) + "</white>.");
        } else {
            lines.add("Waiting for a player to be online.");
        }
        return lines;
    }

    // ---- powerups ----

    /** @return an error message, or null if the Time Extender can be used right now */
    public String validateExtend(Player player) {
        Session s = session;
        if (s == null || !s.primaryId.equals(player.getUniqueId())) {
            return "A Time Extender can only be used by the chosen player during their session.";
        }
        if (s.extended) {
            return "This session has already been extended.";
        }
        return null;
    }

    public void applyExtend(Player player) {
        Session s = session;
        if (s == null) {
            return;
        }
        int extra = settings.extendedSessionSeconds() - settings.sessionSeconds();
        s.remainingSeconds += extra;
        s.totalSeconds += extra;
        s.extended = true;
        Chat.broadcast(Chat.msg("<yellow><player></yellow> used a <aqua>Time Extender</aqua>: the session is now <white><time></white> long.",
                Chat.text("player", player.getName()), Chat.text("time", Chat.duration(s.totalSeconds))));
        refreshBar();
    }

    /** @return an error message, or null if the Dual Gemstone can be used on this target right now */
    public String validateBring(Player player, Player target) {
        Session s = session;
        if (s == null || !s.primaryId.equals(player.getUniqueId())) {
            return "A Dual Gemstone can only be used by the chosen player during their session.";
        }
        if (s.isParty()) {
            return "Everyone is already in the main world.";
        }
        if (s.link != null) {
            return "You already have a Dual Gemstone partner.";
        }
        if (target == null || !target.isOnline()) {
            return "That player is not online.";
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            return "You can't bring yourself.";
        }
        if (target.isDead() || target.getGameMode() == GameMode.SPECTATOR) {
            return "That player can't be brought right now.";
        }
        return null;
    }

    public void applyBring(Player player, Player target) {
        Session s = session;
        if (s == null) {
            return;
        }
        DualLink link = new DualLink(plugin, player, target);
        s.link = link;
        s.participants.add(target.getUniqueId());
        link.start(player, target);
        if (s.modifier == Modifier.INVINCIBLE) {
            applyInvincible(target);
        }
        Chat.broadcast(Chat.msg("<yellow><player></yellow> used a <light_purple>Dual Gemstone</light_purple> and brought <yellow><partner></yellow> along. They now share an inventory and health!",
                Chat.text("player", player.getName()), Chat.text("partner", target.getName())));
    }
}
