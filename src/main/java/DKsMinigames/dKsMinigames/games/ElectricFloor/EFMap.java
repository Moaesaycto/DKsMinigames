package DKsMinigames.dKsMinigames.games.ElectricFloor;

import DKsMinigames.dKsMinigames.utils.ConfigHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class EFMap {
    private final Plugin plugin;
    private final ConfigHelper cfg;
    private final Region arena;
    private final Map<UUID, RedMark> redAwardLock = new HashMap<>();
    private final Set<BlockKey> dropQueued = new HashSet<>();

    private record RedMark(BlockKey key, int untilTick) {}

    // Progression stages (last is lethal/red = falls)
    private final List<Material> stages = List.of(
            Material.LIGHT_BLUE_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS,
            Material.PURPLE_STAINED_GLASS,
            Material.RED_STAINED_GLASS
    );

    private final Map<UUID, StepState> stepState = new HashMap<>();
    private static final int COOLDOWN_TICKS = 5; // 0.25s @ 20 TPS
    private static final int KILL_DEPTH = 10; // eliminate only after falling this far
    private static final int FALL_DELAY = 5; // Ticks

    public EFMap(Plugin plugin) {
        this.plugin = plugin;
        this.cfg = new ConfigHelper(plugin);
        this.arena = getArenaRegion();
    }

    // ===== Public helpers =====
    public void reset() {
        if (arena == null) return;
        World w = arena.world;
        int y = arena.y;
        Material base = stages.getFirst();
        for (int x = arena.minX; x <= arena.maxX; x++) {
            for (int z = arena.minZ; z <= arena.maxZ; z++) {
                w.getBlockAt(x, y, z).setType(base, false);
            }
        }
        stepState.clear();
    }

    public boolean isArenaWorld(World w) { return arena != null && arena.world.equals(w); }

    public boolean isOnArenaFloor(Location loc) {
        if (arena == null || !arena.world.equals(loc.getWorld())) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return y == arena.y && x >= arena.minX && x <= arena.maxX && z >= arena.minZ && z <= arena.maxZ;
    }

    public int getArenaY() { return arena != null ? arena.y : Integer.MIN_VALUE; }

    public boolean shouldEliminateForFall(Location loc) {
        return arena != null
                && arena.world.equals(loc.getWorld())
                && loc.getBlockY() <= (arena.y - KILL_DEPTH);
    }

    /**
     * Called once per tick per player.
     * Behaviour:
     *  - Use the block directly under the player *if* it is at arena.y and within arena bounds.
     *  - If that block is AIR, snap to the nearest existing staged tile at arena.y (recomputed every tick).
     *  - When a tile reaches RED, it falls (we never eliminate here; falling into the void handles that elsewhere).
     */
    public boolean step(UUID playerId, Location playerLoc, int tick) {
        if (arena == null || playerLoc == null || !arena.world.equals(playerLoc.getWorld())) return false;

        // Compute the under-feet block (original behaviour)
        Location under = playerLoc.toBlockLocation().add(0, -1, 0);

        // Only act while interacting with the arena surface: the under-feet Y must equal arena.y.
        if (under.getBlockY() != arena.y) return false;

        // If under-feet is on the arena:
        if (isOnArenaFloor(under)) {
            // If it's air, fall back to nearest existing staged tile; else use the exact block.
            Location target = !under.getBlock().getType().isAir()
                    ? under
                    : nearestExistingTileAtArenaY(playerLoc);

            if (target == null) return false; // nothing to do (e.g., a completely broken patch)
            return stepOnBlock(playerId, target, tick);
        }

        return false;
    }

    // ===== Internals =====

    // Advance one step on a specific block (red → falls after delay). Returns true exactly once per action.
    private boolean stepOnBlock(UUID playerId, Location blockLoc, int tick) {
        final BlockKey key = BlockKey.of(blockLoc);
        final StepState prev = stepState.get(playerId);
        final boolean sameBlock = prev != null && key.equals(prev.block());
        final Material cur = blockLoc.getBlock().getType();
        final Material lethal = stages.getLast();

        // --- Already red: award once per player per block, queue a single delayed drop ---
        if (cur == lethal) {
            RedMark mark = redAwardLock.get(playerId);
            boolean locked = (mark != null) && mark.key().equals(key) && tick < mark.untilTick();
            if (locked) return false;

            // lock this player on this red block until drop fires
            redAwardLock.put(playerId, new RedMark(key, tick + FALL_DELAY));

            // only queue one drop per block
            if (dropQueued.add(key)) {
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    makeBlockFall(blockLoc, lethal);
                    dropQueued.remove(key);
                    // clear stale locks for this block (optional but tidy)
                    redAwardLock.entrySet().removeIf(e -> e.getValue().key().equals(key));
                }, FALL_DELAY);
            }
            return true; // first contact → one award
        }

        // --- Cooldown on same block to avoid rapid multi-advances ---
        if (sameBlock && tick < prev.nextAllowedTick()) return false;

        // --- Advance staged tiles; if we turn lethal, lock & queue the drop now ---
        final int idx = stageIndex(cur);
        if (idx >= 0 && idx < stages.size() - 1) {
            final Material next = stages.get(idx + 1);
            blockLoc.getBlock().setType(next, false);

            // per-player cooldown refresh
            stepState.put(playerId, new StepState(key, tick + COOLDOWN_TICKS, 0));

            if (next == lethal) {
                // lock immediately so the very next tick can't award again before the drop
                redAwardLock.put(playerId, new RedMark(key, tick + FALL_DELAY));
                if (dropQueued.add(key)) {
                    Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                        makeBlockFall(blockLoc, next);
                        dropQueued.remove(key);
                        redAwardLock.entrySet().removeIf(e -> e.getValue().key().equals(key));
                    }, FALL_DELAY);
                }
            }
            return true; // advancing stage counts once
        }

        // Not a staged tile: update cooldown only, no award.
        stepState.put(playerId, new StepState(key, tick + COOLDOWN_TICKS, 0));
        return false;
    }


    private void makeBlockFall(Location blockLoc, Material mat) {
        if (blockLoc.getBlock().getType().isAir()) return;

        BlockData data = mat.createBlockData();
        blockLoc.getBlock().setType(Material.AIR, false);

        // Spawn a falling block centred so it visually drops into the void.
        Location spawn = blockLoc.clone().add(0.5, 0.0, 0.5);
        FallingBlock fb = blockLoc.getWorld().spawnFallingBlock(spawn, data);
        fb.setDropItem(false);
        fb.setHurtEntities(false);
        fb.setGravity(true);
    }

    private int stageIndex(Material m) { return stages.indexOf(m); }

    /**
     * Find the nearest *existing* staged tile (non-air) at arena.y to the player's X/Z.
     * Recomputed every tick so “nearest” updates after a tile falls.
     */
    public Location nearestExistingTileAtArenaY(Location playerLoc) {
        if (arena == null || playerLoc == null || !arena.world.equals(playerLoc.getWorld())) return null;

        // Preferred candidate by rounding to nearest centre.
        int cx = (int) Math.floor(playerLoc.getX() + 0.5);
        int cz = (int) Math.floor(playerLoc.getZ() + 0.5);

        // Clamp to bounds
        cx = Math.max(arena.minX, Math.min(arena.maxX, cx));
        cz = Math.max(arena.minZ, Math.min(arena.maxZ, cz));

        // If that tile exists (non-air staged), return it.
        Location candidate = new Location(arena.world, cx, arena.y, cz);
        if (isStagedSolid(candidate)) return candidate;

        // Otherwise, search outwards in a small expanding square until we find a staged, non-air tile.
        // Radius grows up to the arena half-size, but we short-circuit on first best (min distance).
        int maxR = Math.max(arena.maxX - arena.minX, arena.maxZ - arena.minZ);
        double bestDist2 = Double.POSITIVE_INFINITY;
        Location best = null;

        for (int r = 1; r <= maxR; r++) {
            int minX = Math.max(arena.minX, cx - r);
            int maxX = Math.min(arena.maxX, cx + r);
            int minZ = Math.max(arena.minZ, cz - r);
            int maxZ = Math.min(arena.maxZ, cz + r);

            // Check perimeter of the square ring at radius r
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x != minX && x != maxX && z != minZ && z != maxZ) continue; // perimeter only
                    Location l = new Location(arena.world, x, arena.y, z);
                    if (!isStagedSolid(l)) continue;

                    double dx = (x + 0.5) - playerLoc.getX();
                    double dz = (z + 0.5) - playerLoc.getZ();
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestDist2) {
                        bestDist2 = d2;
                        best = l;
                    }
                }
            }
            if (best != null) break; // found the closest at this radius
        }
        return best;
    }

    private boolean isStagedSolid(Location l) {
        if (l == null || !isOnArenaFloor(l)) return false;
        Material m = l.getBlock().getType();
        return m != Material.AIR && stages.contains(m);
    }

    // ===== Records / region =====
    private record StepState(BlockKey block, int nextAllowedTick, int lethalSafeUntilTick) {}
    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Location l) {
            return new BlockKey(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
    }
    private record Region(World world, int y, int minX, int minZ, int maxX, int maxZ) {}

    private Region getArenaRegion() {
        List<Location> list = cfg.getGameArena("ElectricFloor");
        if (list == null || list.size() < 2) return null;

        Location p = list.get(0), q = list.get(1);
        if (!Objects.equals(p.getWorld(), q.getWorld())) return null;

        int minX = Math.min(p.getBlockX(), q.getBlockX());
        int maxX = Math.max(p.getBlockX(), q.getBlockX());
        int minZ = Math.min(p.getBlockZ(), q.getBlockZ());
        int maxZ = Math.max(p.getBlockZ(), q.getBlockZ());
        return new Region(p.getWorld(), p.getBlockY(), minX, minZ, maxX, maxZ);
    }
}
