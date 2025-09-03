package DKsMinigames.dKsMinigames.games.BlockParty;

import DKsMinigames.dKsMinigames.utils.ConfigHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BPMap {
    private static final List<Material> PALETTE = List.of(
            Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.LIME_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.YELLOW_CONCRETE,
            Material.PINK_CONCRETE, Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE
    );

    private static final int PATTERN_COUNT = 10;

    private enum Facing { SOUTH, NORTH, EAST, WEST }

    private final Plugin plugin;
    private final ConfigHelper cfg;

    public BPMap(Plugin plugin) {
        this.plugin = plugin;
        this.cfg = new ConfigHelper(plugin);
    }

    public int getPatternCount() { return PATTERN_COUNT; }

    public boolean show(int pattern) { return show(pattern, false); }

    public boolean show(int pattern, boolean random) {
        if (pattern < 0 || pattern >= PATTERN_COUNT) return false;

        List<String> grid = loadLines("BPPatterns/Pattern" + pattern + ".txt");
        if (grid.isEmpty()) return false;

        Region arena = getArenaRegion();
        if (arena == null) return false;

        Facing facing = readFacing();
        if (!validateDimensions(grid, arena, facing)) return false;

        List<Material> palette = new ArrayList<>(PALETTE);
        if (random) Collections.shuffle(palette, ThreadLocalRandom.current());

        renderGrid(arena, grid, palette, facing);
        return true;
    }

    public boolean showTitle(boolean random) {
        List<String> title = loadLines("BPPatterns/Title.txt");
        if (title.isEmpty()) return false;

        Region arena = getArenaRegion();
        if (arena == null) return false;

        Facing facing = readFacing();
        if (!validateDimensions(title, arena, facing)) return false;

        List<Material> palette = new ArrayList<>(PALETTE);
        if (random) Collections.shuffle(palette, ThreadLocalRandom.current());

        renderGrid(arena, title, palette, facing);
        return true;
    }

    public List<String> generatePattern(Location p, Location q) {
        if (!Objects.equals(p.getWorld(), q.getWorld())) return Collections.emptyList();
        if (p.getBlockY() != q.getBlockY()) return Collections.emptyList();

        int minX = Math.min(p.getBlockX(), q.getBlockX());
        int maxX = Math.max(p.getBlockX(), q.getBlockX());
        int minZ = Math.min(p.getBlockZ(), q.getBlockZ());
        int maxZ = Math.max(p.getBlockZ(), q.getBlockZ());
        int y = p.getBlockY();
        World w = p.getWorld();

        List<String> rows = new ArrayList<>(maxZ - minZ + 1);
        for (int z = minZ; z <= maxZ; z++) {
            StringBuilder row = new StringBuilder(maxX - minX + 1);
            for (int x = minX; x <= maxX; x++) {
                Material m = w.getBlockAt(x, y, z).getType();
                int idx = PALETTE.indexOf(m);
                row.append(idx < 0 ? '.' : encodeIndex(idx));
            }
            rows.add(row.toString());
        }
        return rows;
    }

    public List<Material> getMaterials() { return PALETTE; }

    public void collapseTo(Material keep) {
        List<Location> arena = cfg.getGameArena("BlockParty");
        if (arena.size() < 2) return;

        Location p = arena.get(0);
        Location q = arena.get(1);
        if (!Objects.equals(p.getWorld(), q.getWorld())) return;

        int minX = Math.min(p.getBlockX(), q.getBlockX());
        int maxX = Math.max(p.getBlockX(), q.getBlockX());
        int minZ = Math.min(p.getBlockZ(), q.getBlockZ());
        int maxZ = Math.max(p.getBlockZ(), q.getBlockZ());
        int y = p.getBlockY();
        World w = p.getWorld();

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (w.getBlockAt(x, y, z).getType() != keep) {
                    w.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    // ==== internals ====

    private static char encodeIndex(int idx) {
        return (idx < 10) ? (char) ('0' + idx) : (char) ('A' + (idx - 10));
    }

    private static int decodeIndex(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'A' && ch <= 'Z') return 10 + (ch - 'A');
        if (ch >= 'a' && ch <= 'z') return 10 + (ch - 'a');
        return -1; // '.' or anything else
    }

    private List<String> loadLines(String path) {
        try (InputStream in = plugin.getResource(path);
             BufferedReader r = (in == null) ? null : new BufferedReader(new InputStreamReader(in))) {
            if (r == null) {
                plugin.getLogger().severe("Resource not found: " + path);
                return List.of();
            }
            List<String> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) out.add(line.trim());
            return out;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load " + path + ": " + e.getMessage());
            return List.of();
        }
    }

    private Facing readFacing() {
        // If you don’t have this helper, replace with parsing from your game config:
        // String raw = (String)((Map<String,Object>)cfg.getGameConfig("BlockParty").get("spawn")).getOrDefault("facing","south");
        String raw = cfg.getSpawnFacingDirection("BlockParty"); // expected: "north|south|east|west"
        if (raw == null) return Facing.SOUTH;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "north" -> Facing.NORTH;
            case "east"  -> Facing.EAST;
            case "west"  -> Facing.WEST;
            default      -> Facing.SOUTH;
        };
    }

    private void renderGrid(Region a, List<String> grid, List<Material> palette, Facing facing) {
        World w = a.world;
        int y = a.y;

        final int W = grid.get(0).length(); // columns in file
        final int H = grid.size();          // rows in file

        for (int v = 0; v < H; v++) {        // v = grid row index
            String row = grid.get(v);
            for (int u = 0; u < W; u++) {    // u = grid column index
                char ch = row.charAt(u);
                if (ch == '.') continue;
                int idx = decodeIndex(ch);
                if (idx < 0 || idx >= palette.size()) continue;

                int xoff, zoff;

                switch (facing) {
                    case SOUTH -> { // default (no rotation): (u, v)
                        xoff = u;
                        zoff = v;
                    }
                    case NORTH -> { // 180°: (W-1-u, H-1-v)
                        xoff = (a.width() - 1)  - u;
                        zoff = (a.height() - 1) - v;
                    }
                    case EAST -> {  // 90° clockwise: (H-1-v, u)
                        // For EAST/WEST we validated that a.width == H and a.height == W
                        xoff = (H - 1) - v;
                        zoff = u;
                    }
                    case WEST -> {  // 90° counter-clockwise: (v, W-1-u)
                        xoff = v;
                        zoff = (W - 1) - u;
                    }
                    default -> { xoff = u; zoff = v; }
                }

                int x = a.minX + xoff;
                int z = a.minZ + zoff;
                w.getBlockAt(x, y, z).setType(palette.get(idx), false);
            }
        }
    }

    private boolean validateDimensions(List<String> grid, Region arena, Facing facing) {
        // grid: W x H
        int H = grid.size();
        if (H == 0) return false;
        int W = grid.get(0).length();
        for (int r = 1; r < H; r++) {
            if (grid.get(r).length() != W) {
                plugin.getLogger().severe("Inconsistent row length at " + r);
                return false;
            }
        }

        // For EAST/WEST the grid is effectively transposed on the arena
        boolean ok = switch (facing) {
            case SOUTH, NORTH -> (W == arena.width() && H == arena.height());
            case EAST, WEST   -> (W == arena.height() && H == arena.width());
        };

        if (!ok) {
            plugin.getLogger().severe(
                    "Pattern size " + W + "x" + H + " does not match arena "
                            + arena.width() + "x" + arena.height()
                            + " for facing " + facing.name().toLowerCase(Locale.ROOT));
        }
        return ok;
    }

    private record Region(World world, int y, int minX, int minZ, int maxX, int maxZ) {
        int width()  { return (maxX - minX) + 1; }
        int height() { return (maxZ - minZ) + 1; }
    }

    private Region getArenaRegion() {
        List<Location> arena = cfg.getGameArena("BlockParty");
        if (arena == null || arena.size() < 2) return null;

        Location p = arena.get(0);
        Location q = arena.get(1);
        if (!Objects.equals(p.getWorld(), q.getWorld())) return null;

        int minX = Math.min(p.getBlockX(), q.getBlockX());
        int maxX = Math.max(p.getBlockX(), q.getBlockX());
        int minZ = Math.min(p.getBlockZ(), q.getBlockZ());
        int maxZ = Math.max(p.getBlockZ(), q.getBlockZ());

        return new Region(p.getWorld(), p.getBlockY(), minX, minZ, maxX, maxZ);
    }
}
