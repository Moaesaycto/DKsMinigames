package DKsMinigames.dKsMinigames.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigHelper {
    private final Plugin plugin;
    public ConfigHelper(Plugin plugin) { this.plugin = plugin; }

    public Location getHubSpawn() {
        FileConfiguration c = plugin.getConfig();
        double x = c.getDouble("hub.spawn.x");
        double y = c.getDouble("hub.spawn.y");
        double z = c.getDouble("hub.spawn.z");
        return new Location(plugin.getServer().getWorld("world"), x, y, z);
    }

    private Map<?, ?> getGameConfig(String name) {
        FileConfiguration c = plugin.getConfig();

        return c.getMapList("games").stream()
            .filter(m -> name.equalsIgnoreCase((String) m.get("name")))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No game with name " + name));
    }

    @SuppressWarnings("unchecked")
    public Location getGameSpawn(String name) {
        Map<String, Object> spawn = (Map<String, Object>) this.getGameConfig(name).get("spawn");

        int x = ((Number) spawn.get("x")).intValue();
        int y = ((Number) spawn.get("y")).intValue();
        int z = ((Number) spawn.get("z")).intValue();

        String facingRaw = (String) spawn.getOrDefault("facing", "south");
        float yaw;
        switch (facingRaw.toLowerCase(Locale.ROOT)) {
            case "north" -> yaw = 180f;
            case "west" -> yaw = 90f;
            case "east" -> yaw = -90f; // or 270f
            default -> yaw = 0f; // south
        }

        return new Location(Bukkit.getWorlds().getFirst(), x + 0.5, y, z + 0.5, yaw, 0f);
    }

    public List<Location> getGameArena(String name) {
        Map<?, ?> spawn = (Map<?, ?>) this.getGameConfig(name).get("arena");

        int x1 = ((Number) spawn.get("x1")).intValue();
        int y1 = ((Number) spawn.get("y1")).intValue();
        int z1 = ((Number) spawn.get("z1")).intValue();
        int x2 = ((Number) spawn.get("x2")).intValue();
        int y2 = ((Number) spawn.get("y2")).intValue();
        int z2 = ((Number) spawn.get("z2")).intValue();

        World world = Bukkit.getWorlds().getFirst();

        return List.of(
            new Location(world, x1, y1, z1),
            new Location(world, x2, y2, z2)
        );
    }

    @SuppressWarnings("unchecked")
    public String getSpawnFacingDirection(String name) {
        Map<String, Object> spawn = (Map<String, Object>) this.getGameConfig(name).get("spawn");
        return (String) spawn.getOrDefault("facing", "south");
    }

    public int getCountdown() {
        return plugin.getConfig().getInt("game-defaults.countdown", 5);
    }

    public int getEndWait() {
        return plugin.getConfig().getInt("game-defaults.end-wait", 3);
    }
}
