package DKsMinigames.dKsMinigames.objects.PowerUp;

import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

import java.util.function.Consumer;
import java.util.function.Predicate;

public class PowerUp {
    private final Plugin plugin;
    private final Consumer<Player> onCollect;
    private final Predicate<Player> canCollect;
    private final ArmorStand stand;
    private final Location base;
    private final long lifetime; // ms
    private final long spawnTime = System.currentTimeMillis();

    private BukkitTask task;
    private int t = 0;
    private float yaw = 0f;

    public PowerUp(Plugin plugin, Location spawnLoc,
                   Consumer<Player> action,
                   Predicate<Player> canCollect,
                   long lifetimeTicks) {
        this.plugin = plugin;
        this.onCollect = action;
        this.canCollect = canCollect;
        this.base = spawnLoc.clone();
        this.lifetime = lifetimeTicks * 50L; // ticks → ms

        spawnLoc.getWorld().spawnParticle(Particle.CLOUD, spawnLoc, 40, 0.6, 0.6, 0.6, 0.02);
        spawnLoc.getWorld().playSound(spawnLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);

        this.stand = spawnLoc.getWorld().spawn(spawnLoc, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setSmall(true);
            as.setCollidable(false);
            as.getEquipment().setHelmet(new ItemStack(Material.CHEST));
        });

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (!stand.isValid()) { cancel(); return; }
        t++;
        long age = System.currentTimeMillis() - spawnTime;

        // Simple bob + rotate
        double bob = 0.25 * Math.sin(t / 10.0);
        yaw = (yaw + 2f) % 360f;
        Location target = base.clone().add(0, bob, 0);
        target.setYaw(yaw);
        stand.teleport(target);
        stand.setHeadPose(new EulerAngle(0, Math.toRadians(yaw), 0));

        // Flash if close to despawn
        long remaining = lifetime - age;
        if (remaining <= 2000) { // last 2s
            stand.setVisible((t % 10) < 5); // blink fast
        }

        // Despawn if expired
        if (age >= lifetime) {
            despawnEffect();
            destroy();
            return;
        }

        // Collection
        for (Entity e : stand.getNearbyEntities(1.2, 1.2, 1.2)) {
            if (e instanceof Player p && canCollect.test(p)) {
                collect(p);
                return;
            }
        }
    }

    private void collect(Player p) {
        World w = stand.getWorld();
        Location l = stand.getLocation().add(0, 0.5, 0);
        w.spawnParticle(Particle.CRIT, l, 60, 0.5, 0.5, 0.5, 0.02);
        w.spawnParticle(Particle.WITCH, l, 30, 0.4, 0.6, 0.4, 0.01);
        w.playSound(l, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.1f);
        w.playSound(l, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.6f);

        try { onCollect.accept(p); } catch (Throwable ignored) {}
        destroy();
    }

    private void despawnEffect() {
        Location l = stand.getLocation().add(0, 0.5, 0);
        World w = stand.getWorld();
        w.spawnParticle(Particle.LARGE_SMOKE, l, 30, 0.5, 0.5, 0.5, 0.02);
        w.playSound(l, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.8f, 0.9f);
    }

    public void destroy() {
        if (stand != null && stand.isValid()) stand.remove();
        cancel();
    }

    private void cancel() {
        if (task != null) { task.cancel(); task = null; }
    }
}
