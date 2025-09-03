package DKsMinigames.dKsMinigames.games.BlockParty;

import DKsMinigames.dKsMinigames.games.Minigame;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class BlockParty extends Minigame {
    // ===== Tunables =====
    private static final int GRACE_TIME = 3; // seconds before first round
    private static final int COLLAPSE_WAIT_TIME = 60; // ticks after collapse to settle eliminations
    private static final int MAX_SCOUT_TIME = 100; // ticks
    private static final int MIN_SCOUT_TIME = 20; // ticks
    private static final int DECR_TIME = 5; // ticks per round

    // ===== State =====
    private org.bukkit.scheduler.BukkitTask roundBarTask;
    private List<Location> arenaCoords;
    private int scout_time = MAX_SCOUT_TIME;
    private Material curr_material;
    private final BPMap map;

    // The most recent lone-survivor snapshot at a round boundary (used only if everyone later dies).
    private UUID lastLoneSurvivor = null;

    public BlockParty(Plugin plugin) {
        super(plugin, "BlockParty");
        this.map = new BPMap(plugin);
        this.setCanChangeInventory(false);
        this.setPlayerCanBeHungry(false);
        this.setPlayerCanBeHurt(false);
    }

    // ===== Commands =====
    @Override
    protected boolean onCommand(String[] args, Player p) {
        if (args == null || args.length == 0) {
            p.sendMessage("Usage: /blockparty <scan-arena|render|title|collapse|round>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "scan-arena" -> {
                this.printArenaPattern();
                return true;
            }
            case "render" -> {
                if (args.length < 2) { p.sendMessage("Usage: /blockparty render <patternNumber> [random]"); return true; }
                try {
                    int patternIndex = Integer.parseInt(args[1]);
                    boolean random = args.length >= 3 && args[2].equalsIgnoreCase("random");
                    boolean ok = new BPMap(this.getPlugin()).show(patternIndex, random);
                    if (!ok) p.sendMessage("Failed to render pattern " + patternIndex);
                } catch (NumberFormatException e) {
                    p.sendMessage("Pattern number must be an integer.");
                }
                return true;
            }
            case "title" -> {
                boolean random = args.length >= 2 && args[1].equalsIgnoreCase("random");
                boolean ok = new BPMap(this.getPlugin()).showTitle(random);
                if (!ok) p.sendMessage("Failed to render title (check BPPatterns/Title.txt and arena size).");
                return true;
            }
            case "collapse" -> {
                if (args.length < 2) { p.sendMessage("Usage: /blockparty collapse <MATERIAL>"); return true; }
                try {
                    Material keep = Material.valueOf(args[1].toUpperCase(Locale.ROOT));
                    new BPMap(this.getPlugin()).collapseTo(keep);
                    p.sendMessage("Collapsed arena to keep only " + keep);
                } catch (IllegalArgumentException e) {
                    p.sendMessage("Unknown material: " + args[1]);
                }
                return true;
            }
            case "round" -> {
                if (args.length != 2) { p.sendMessage("Usage: /blockparty round <ticks>"); return true; }
                try {
                    int ticks = Integer.parseInt(args[1]);
                    this.startRound(ticks, () -> {});
                } catch (Exception e) {
                    p.sendMessage("Invalid tick count: " + args[1]);
                }
                return true;
            }
            default -> {
                // Not a BlockParty-specific command; let the base class show its unknown hint.
                return false;
            }
        }
    }

    // ===== Lifecycle =====
    @Override
    protected void onInit() {
        map.showTitle(false);
    }

    @Override
    protected void onStart() {
        arenaCoords = getConfigHelper().getGameArena(name());
        scout_time = MAX_SCOUT_TIME;
        lastLoneSurvivor = null;

        // Scatter players onto the floor (1 block above)
        Location c1 = arenaCoords.getFirst();
        Location c2 = arenaCoords.getLast();
        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());
        int y = c1.getBlockY() + 1;

        forEachPlayer(p -> {
            int x = rnd().nextInt(minX + 2, maxX - 1);
            int z = rnd().nextInt(minZ + 2, maxZ - 1);
            p.teleport(new Location(c1.getWorld(), x + 0.5, y, z + 0.5));
        });

        // Grace, then round loop
        Bukkit.getScheduler().runTaskLater(getPlugin(), this::nextRoundLoop, 20L * GRACE_TIME);
    }

    @Override
    protected void onTick() {
        // Safety net: eliminate players who fall off (without mutating while iterating)
        int yFloor = arenaCoords.getFirst().getBlockY() - 20;

        java.util.List<Player> toEliminate = new java.util.ArrayList<>();
        players().forEach(id -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && p.getLocation().getBlockY() <= yFloor) {
                toEliminate.add(p);
            }
        });
        // Now mutate outside the iteration over players()
        toEliminate.forEach(this::eliminate);

        // Spectators rescue (safe: no set mutation)
        spectators().forEach(id -> {
            Player s = Bukkit.getPlayer(id);
            if (s != null && s.isOnline() && s.getLocation().getBlockY() <= yFloor) {
                s.teleport(getConfigHelper().getHubSpawn());
            }
        });
    }


    @Override
    protected void onEnd() {
        stopCountdownBar();
        map.showTitle(false);
        playersPlaySound(Sound.BLOCK_END_PORTAL_SPAWN);
        clearXpBars();
    }

    @Override
    protected void onEliminate(Player eliminated) {
        eliminated.getInventory().clear();
        playersPlaySound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
        // Do not end or decide here; resolution only at post-round checkpoint.
    }

    // ===== Round engine =====
    public void startRound(int durationTicks, Runnable onAfterWait) {
        if (state() != State.RUNNING) return;

        forEachPlayer(p -> addScoreboard(p, 1));
        playersPlaySound(Sound.ENTITY_WITHER_SPAWN);

        List<Material> materials = map.getMaterials();
        curr_material = materials.get(ThreadLocalRandom.current().nextInt(materials.size()));
        int pattern = ThreadLocalRandom.current().nextInt(map.getPatternCount());

        map.show(pattern, true);

        forEachPlayer(p -> {
            p.getInventory().clear();
            p.getInventory().setItem(4, new ItemStack(curr_material, 1));
        });

        startCountdownBar(durationTicks);

        // Collapse when the scout timer elapses
        Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
            if (state() != State.RUNNING) return;
            playersPlaySound(Sound.ENTITY_WITHER_BREAK_BLOCK);
            map.collapseTo(curr_material);
            stopCountdownBar(); // stop bar at collapse start
        }, durationTicks);

        // Post-collapse settle; then checkpoint outcome
        Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
            if (state() != State.RUNNING) return;
            if (postRoundCheckpointAndMaybeEnd()) return;
            onAfterWait.run();
        }, durationTicks + COLLAPSE_WAIT_TIME);
    }

    private void nextRoundLoop() {
        startRound(scout_time, () -> {
            scout_time = Math.max(MIN_SCOUT_TIME, scout_time - DECR_TIME);
            nextRoundLoop();
        });
    }

    // ===== Outcome logic (only at round boundaries) =====
    private UUID soleSurvivorId() {
        return players().size() == 1 ? players().iterator().next() : null;
    }

    /**
     * End only when EVERYONE is eliminated.
     * If exactly one alive at boundary → remember them (provisional winner) and continue.
     * If zero alive → crown last remembered lone-survivor if still online; else “no winners”.
     * Returns true if the game ended here.
     */
    private boolean postRoundCheckpointAndMaybeEnd() {
        int alive = players().size();

        if (alive == 1) {
            // Never end on 1; just remember who it was.
            lastLoneSurvivor = soleSurvivorId();
            return false;
        }

        if (alive == 0) {
            stopCountdownBar();
            map.showTitle(false);

            if (lastLoneSurvivor != null) {
                Player winner = Bukkit.getPlayer(lastLoneSurvivor);
                if (winner != null) {
                    end(winner);                 // Crown the last lone-survivor seen.
                } else {
                    announce("No winners this game."); // Survivor went offline → treat as no winner.
                    end();
                }
            } else {
                announce("No winners this game.");     // Never had a lone-survivor in any round.
                end();
            }
            return true;
        }

        return false; // >1 alive → keep going
    }

    // ===== UI: XP countdown =====
    private void startCountdownBar(int durationTicks) {
        stopCountdownBar();
        final int[] left = { durationTicks };
        roundBarTask = Bukkit.getScheduler().runTaskTimer(getPlugin(), () -> {
            if (state() != State.RUNNING) { stopCountdownBar(); return; }
            float pct = Math.max(0f, Math.min(1f, left[0] / (float) durationTicks));
            int secondsLeft = Math.max(0, (left[0] + 19) / 20);
            forEachPlayer(p -> { p.setExp(pct); p.setLevel(secondsLeft); });
            if (--left[0] < 0) stopCountdownBar();
        }, 0L, 1L);
    }

    private void stopCountdownBar() {
        if (roundBarTask != null) { roundBarTask.cancel(); roundBarTask = null; }
        forEachPlayer(p -> { p.setExp(0f); p.setLevel(0); });
    }

    // ===== Debugging =====
    public void printArenaPattern() {
        if (arenaCoords == null || arenaCoords.size() < 2) {
            arenaCoords = getConfigHelper().getGameArena(name());
        }
        var corners = arenaCoords;
        BPMap reader = new BPMap(this.plugin);
        for (String row : reader.generatePattern(corners.get(0), corners.get(1))) {
            log(row);
        }
    }
}
