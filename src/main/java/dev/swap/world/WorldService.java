package dev.swap.world;

import dev.swap.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Stream;

/** Owns the two worlds, the saved main-world exit position and the "Great Reset". */
public final class WorldService {
    private final JavaPlugin plugin;
    private final Settings settings;
    private final File stateFile;
    private Location lastMainLocation;

    public WorldService(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.stateFile = new File(plugin.getDataFolder(), "state.yml");
    }

    /** Creates or loads both worlds, then restores the saved exit position. */
    public void ensureWorlds() {
        if (Bukkit.getWorld(settings.superflatWorld()) == null) {
            new WorldCreator(settings.superflatWorld())
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .createWorld();
        }
        if (Bukkit.getWorld(settings.mainWorld()) == null) {
            new WorldCreator(settings.mainWorld())
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL)
                    .createWorld();
        }
        loadState();
    }

    public World superflat() {
        return Bukkit.getWorld(settings.superflatWorld());
    }

    public World main() {
        return Bukkit.getWorld(settings.mainWorld());
    }

    public boolean isMain(World world) {
        return world != null && world.getName().equals(settings.mainWorld());
    }

    public Location superflatSpawn() {
        World world = superflat();
        int y = world.getHighestBlockYAt(0, 0) + 1;
        return new Location(world, 0.5, y, 0.5);
    }

    /** Where the next session begins: exactly where the previous player left, else the world spawn. */
    public Location mainEntry() {
        World main = main();
        if (main == null) {
            return null;
        }
        if (lastMainLocation != null && lastMainLocation.getWorld() != null && isMain(lastMainLocation.getWorld())) {
            return lastMainLocation.clone();
        }
        return main.getSpawnLocation();
    }

    public void setLastMainLocation(Location location) {
        this.lastMainLocation = location == null ? null : location.clone();
        saveState();
    }

    /**
     * Wipes the main world and regenerates it with a new random seed.
     *
     * @return true if the world was regenerated
     */
    public boolean resetMain() {
        World main = main();
        if (main != null) {
            Location out = superflatSpawn();
            for (Player player : main.getPlayers()) {
                player.teleport(out);
            }
            File folder = main.getWorldFolder();
            if (!Bukkit.unloadWorld(main, false)) {
                plugin.getLogger().severe("The Great Reset failed: could not unload world '" + main.getName() + "'.");
                return false;
            }
            try {
                deleteRecursively(folder.toPath());
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "The Great Reset could not fully delete " + folder, e);
            }
        }
        World created = new WorldCreator(settings.mainWorld())
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .seed(ThreadLocalRandom.current().nextLong())
                .createWorld();
        setLastMainLocation(null);
        if (created == null) {
            plugin.getLogger().severe("The Great Reset failed: could not recreate world '" + settings.mainWorld() + "'.");
            return false;
        }
        plugin.getLogger().info("The Great Reset: '" + created.getName() + "' regenerated with seed " + created.getSeed());
        return true;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    private void loadState() {
        if (!stateFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        if (!yaml.contains("last.world")) {
            return;
        }
        World world = Bukkit.getWorld(yaml.getString("last.world", ""));
        if (world == null || !isMain(world)) {
            return;
        }
        lastMainLocation = new Location(world,
                yaml.getDouble("last.x"), yaml.getDouble("last.y"), yaml.getDouble("last.z"),
                (float) yaml.getDouble("last.yaw"), (float) yaml.getDouble("last.pitch"));
    }

    private void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (lastMainLocation != null && lastMainLocation.getWorld() != null) {
            yaml.set("last.world", lastMainLocation.getWorld().getName());
            yaml.set("last.x", lastMainLocation.getX());
            yaml.set("last.y", lastMainLocation.getY());
            yaml.set("last.z", lastMainLocation.getZ());
            yaml.set("last.yaw", (double) lastMainLocation.getYaw());
            yaml.set("last.pitch", (double) lastMainLocation.getPitch());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + stateFile, e);
        }
    }
}
