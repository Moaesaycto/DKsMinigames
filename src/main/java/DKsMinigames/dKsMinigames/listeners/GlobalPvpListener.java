package DKsMinigames.dKsMinigames.listeners;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalPvpListener implements Listener {
    // Players inside a PvP-enabled minigame
    private static final Set<UUID> ALLOW = ConcurrentHashMap.newKeySet();

    public static void allowPvpFor(UUID id)     { ALLOW.add(id); }
    public static void disallowPvpFor(UUID id)  { ALLOW.remove(id); }
    public static void allowPvpFor(Iterable<UUID> ids) { ids.forEach(ALLOW::add); }
    public static void disallowPvpFor(Iterable<UUID> ids) { ids.forEach(ALLOW::remove); }

    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        Player victim = (e.getEntity() instanceof Player p) ? p : null;
        if (victim == null) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null) return;

        // Only allow if BOTH are explicitly allowed (i.e., in same PvP-enabled game)
        boolean ok = ALLOW.contains(attacker.getUniqueId()) && ALLOW.contains(victim.getUniqueId());
        if (!ok) e.setCancelled(true);
    }
}
