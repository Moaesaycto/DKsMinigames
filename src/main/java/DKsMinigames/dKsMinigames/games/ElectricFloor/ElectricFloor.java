package DKsMinigames.dKsMinigames.games.ElectricFloor;

import DKsMinigames.dKsMinigames.games.Minigame;
import DKsMinigames.dKsMinigames.utils.ConfigHelper;
import DKsMinigames.dKsMinigames.utils.FX;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class ElectricFloor extends Minigame {
    private static final int GRACE_TIME = 3;
    private final EFMap map;
    private int tick = 0;
    private boolean toEnd = false;
    private final ConfigHelper cfg;
    private List<Location> arenaCoords;
    private boolean canDecay;


    public ElectricFloor(Plugin plugin) {
        super(plugin, "ElectricFloor");

        this.map = new EFMap(plugin);

        this.setCanChangeInventory(false);
        this.setPlayerCanBeHungry(false);
        this.setPlayerCanBeHurt(false);
        this.cfg = new ConfigHelper(plugin);
        this.arenaCoords = getConfigHelper().getGameArena(name());

        this.canDecay = false;
    }

    @Override
    protected void onInit() {
        this.map.reset();
    }

    @Override
    public boolean command(String[] args, Player p) {
        if (args.length == 0) {
            p.sendMessage("Usage: /electricflor <args>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> this.start();
            case "end" -> {
                if (this.state() != Minigame.State.RUNNING) {
                    announcePlayer("Game cannot be ended in current state", p, true);
                } else {
                    this.end();
                }
            }
            case "reset-map" -> map.reset();
            case "players" -> this.printPlayerInfo(p);
        }

        return true;
    }

    @Override
    protected void onStart() {
        this.canDecay = false;
        this.toEnd = false;
        this.map.reset();

        this.arenaCoords = cfg.getGameArena(this.name());

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

        Bukkit.getScheduler().runTaskLater(this.getPlugin(), () -> {
            this.canDecay = true;
        }, 20L * GRACE_TIME);

    }

    @Override
    protected void onTick() {
        tick++;

        java.util.List<Player> toEliminate = new java.util.ArrayList<>();

        if (!this.canDecay) return;

        // Snapshot to avoid concurrent modification during eliminate()
        for (UUID id : new java.util.ArrayList<>(players())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || !map.isArenaWorld(p.getWorld())) continue;

            // Let EFMap handle: under-feet if solid @ arena.y, else nearest existing tile
            if (map.step(id, p.getLocation(), tick)) {
                addScoreboard(p, this.playerCount());
            }

            // Kill only after they've fallen sufficiently below the arena
            if (map.shouldEliminateForFall(p.getLocation())) {
                toEliminate.add(p);
            }
        }

        toEliminate.forEach(this::eliminate);

        if (this.toEnd) end(this.bestByPoints());
    }


    @Override
    protected void onEliminate(Player p) {
        FX.explodeWithBlood(p);
        FX.silentLightning(p);

        if (this.players().isEmpty()) {
            this.toEnd = true;
        }
    }

    @Override
    protected void onEnd() {
        Bukkit.getScheduler().runTaskLater(this.getPlugin(), this.map::reset, 20L * cfg.getEndWait());
    }
}
