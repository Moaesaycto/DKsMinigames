package DKsMinigames.dKsMinigames.utils;

import org.bukkit.*;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public final class FX {
    private FX() {}

    /** Violent explosion at the player's feet with “blood” (red dust + red chunks + mist). */
    public static void explodeWithBlood(Player target) {
        if (target == null || !target.isOnline()) return;

        World w = target.getWorld();
        Location loc = target.getLocation().clone().add(0, 0.1, 0);

        // Big boom (no fire, no block damage) + sounds
        w.createExplosion(loc.getX(), loc.getY(), loc.getZ(), 4.0f, false, false);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 0.7f, 0.9f);
        // w.playSound(loc, Sound.ENTITY_PLAYER_HURT, SoundCategory.MASTER, 1.2f, 0.6f);

        // Blood spray — red dust (Particle.DUST w/ DustOptions)
        Particle.DustOptions blood = new Particle.DustOptions(Color.fromRGB(150, 0, 0), 2.0f);
        w.spawnParticle(Particle.DUST, loc, 220, 1.6, 1.0, 1.6, 0.0, blood);

        // Chunks — fling item shards (Particle.ITEM with RED_DYE)
        w.spawnParticle(Particle.ITEM, loc, 120, 1.2, 0.8, 1.2, 0.15, new ItemStack(Material.RED_DYE));

        // Splats — red block dust (Particle.BLOCK with red concrete powder)
        w.spawnParticle(Particle.BLOCK, loc, 80, 1.2, 0.6, 1.2, 0.0, Material.RED_CONCRETE_POWDER.createBlockData());

        w.playSound(loc, Sound.ENTITY_SLIME_SQUISH, SoundCategory.MASTER, 1.4f, 0.6f);   // wet slap
        w.playSound(loc, Sound.BLOCK_SLIME_BLOCK_STEP, SoundCategory.MASTER, 1.0f, 0.8f); // rubbery impact
        w.playSound(loc, Sound.BLOCK_HONEY_BLOCK_FALL, SoundCategory.MASTER, 0.7f, 0.75f); // stickier undertone (optional)

        // Cosmetic knockback
        for (Entity e : w.getNearbyEntities(loc, 3.0, 2.0, 3.0)) {
            if (e instanceof Player p && p.isOnline()) {
                Vector away = p.getLocation().toVector().subtract(loc.toVector()).normalize()
                        .multiply(0.8).setY(0.35);
                p.setVelocity(p.getVelocity().add(away));
            }
        }
    }

    public static void silentLightning(Player target) {
        if (target == null || !target.isOnline()) return;
        World world = target.getWorld();
        Location loc = target.getLocation();

        // Pure visual lightning bolt (no fire, no damage, no extra sounds)
        world.strikeLightningEffect(loc);
    }
}
