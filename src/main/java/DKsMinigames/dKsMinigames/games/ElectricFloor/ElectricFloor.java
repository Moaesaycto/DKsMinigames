package DKsMinigames.dKsMinigames.games.ElectricFloor;

import DKsMinigames.dKsMinigames.games.Minigame;
import DKsMinigames.dKsMinigames.objects.PowerUp.PowerUp;
import DKsMinigames.dKsMinigames.objects.PowerUp.PowerUpAbility;
import DKsMinigames.dKsMinigames.utils.ConfigHelper;
import DKsMinigames.dKsMinigames.utils.FX;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class ElectricFloor extends Minigame {
    private static final int GRACE_TIME = 3;
    private final EFMap map;
    private int tick = 0;
    private boolean toEnd = false;
    private final ConfigHelper cfg;
    private List<Location> arenaCoords;
    private boolean canDecay;

    private BukkitTask powerUpSpawning;
    private int POWERUP_SPAWN_RATE = 10;
    private List<PowerUp> powerUpList;


    public ElectricFloor(Plugin plugin) {
        super(plugin, "ElectricFloor");

        this.map = new EFMap(plugin);

        this.setCanChangeInventory(false);
        this.setPlayerCanBeHungry(false);
        this.setPlayerCanBeHurt(false);
        this.cfg = new ConfigHelper(plugin);
        this.arenaCoords = getConfigHelper().getGameArena(name());

        this.canDecay = false;
        this.powerUpList = new ArrayList<PowerUp>();
    }

    @Override
    protected void onInit() {
        this.map.reset();
    }

    @Override
    protected void onDisable() {
        this.clearPowerUps();
    }

    @Override
    public boolean onCommand(String[] args, Player p) {
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
            var title = Component.text( "Don't stop moving!", NamedTextColor.RED, TextDecoration.BOLD);
            var sub = Component.text("Electrify as much of the floor as possible", NamedTextColor.WHITE);

            var times = Title.Times.times(
                    java.time.Duration.ofMillis(200),
                    java.time.Duration.ofSeconds(2),
                    java.time.Duration.ofMillis(400)
            );
            var full = Title.title(title, sub, times);

            forEveryone(p -> p.showTitle(full));
            playersPlaySound(Sound.ENTITY_ENDER_DRAGON_DEATH);
            this.canDecay = true;
        }, 20L * GRACE_TIME);

        this.powerUpSpawning = Bukkit.getScheduler().runTaskTimer(getPlugin(), () -> {
           if (state() != State.RUNNING) { stopPowerupSpawning(); }
            spawnRandomPowerUp();
        }, 20L * GRACE_TIME + 20L * POWERUP_SPAWN_RATE, 20L * POWERUP_SPAWN_RATE);
    }

    @Override
    protected void onTick() {
        tick++;

        java.util.List<Player> toEliminate = new java.util.ArrayList<>();

        if (!this.canDecay) return;

        this.forEachPlayer(p -> {
            Location loc = p.getLocation().clone().add(0, 0.1, 0);
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, loc, 1, 0.2, 0.05, 0.2, 0.1);

            if (map.step(p.getUniqueId(), loc, tick))  addScoreboard(p, this.playerCount());

            if (map.shouldEliminateForFall(loc)) toEliminate.add(p);
        });

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
        stopPowerupSpawning();
        clearPowerUps();
    }

    private void spawnRandomPowerUp() {
        EFPowerUps.PowerUp effect = EFPowerUps.randomPowerUp();

        this.powerUpList.add(new PowerUp(
                this.plugin,
                map.getRandomArenaLocation(true),
                effect.apply(),
                p -> this.players().contains(p.getUniqueId()),
                200
        ));
    }

    private void stopPowerupSpawning() {
        if (powerUpSpawning != null) {
            powerUpSpawning.cancel();
            powerUpSpawning = null;
        }
    }

    private void clearPowerUps() {
        powerUpList.forEach(PowerUp::destroy);
        powerUpList.clear();
    }
}
