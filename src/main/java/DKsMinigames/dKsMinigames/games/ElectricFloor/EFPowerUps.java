package DKsMinigames.dKsMinigames.games.ElectricFloor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class EFPowerUps {
    private EFPowerUps() {}

    public record PowerUp(String id, Consumer<Player> apply) {
        public void give(Player p) { apply.accept(p); }
    }

    public static PowerUp potion(String id, PotionEffectType type, int seconds, int amp) {
        return new PowerUp(id, p -> p.addPotionEffect(new PotionEffect(type, seconds * 20, amp, false, false, true)));
    }

    public static PowerUp item(String id, ItemStack stack) {
        return new PowerUp(id, p -> p.getInventory().addItem(stack));
    }

    public static PowerUp cmd(String id, String consoleCmdWithPlayerPlaceholder) {
        return new PowerUp(id, p -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                consoleCmdWithPlayerPlaceholder.replace("{player}", p.getName())));
    }

    public static PowerUp knockback(String id, double horiz, double up) {
        return new PowerUp(id, p -> p.setVelocity(p.getLocation().getDirection().multiply(-horiz).setY(up)));
    }

    public static final Map<String, PowerUp> REGISTRY = Collections.unmodifiableMap(new LinkedHashMap<>() {{
        put("slow3", potion("slow3", PotionEffectType.SLOWNESS, 5, 2));
        put("blind", potion("blind", PotionEffectType.BLINDNESS, 5, 0));
        put("dark", potion("dark", PotionEffectType.DARKNESS, 5, 0));
        put("jump2", potion("jump2", PotionEffectType.JUMP_BOOST, 5, 1));
        put("speed2", potion("speed2", PotionEffectType.SPEED, 5, 1));
        put("levitate", potion("levitate", PotionEffectType.LEVITATION, 3, 0));
        put("vanish", potion("vanish", PotionEffectType.INVISIBILITY, 5, 2)); // example via command
    }});

    public static final List<Consumer<Player>> POWER_UPS = REGISTRY.values().stream()
            .map(PowerUp::apply).collect(Collectors.toUnmodifiableList());

    public record Weighted<T>(T value, int weight) {}
    private static final List<Weighted<PowerUp>> WEIGHTS = List.of(
            new Weighted<>(REGISTRY.get("speed2"), 2),
            new Weighted<>(REGISTRY.get("slow3"), 2),
            new Weighted<>(REGISTRY.get("blind"), 1),
            new Weighted<>(REGISTRY.get("dark"), 1),
            new Weighted<>(REGISTRY.get("jump2"), 2),
            new Weighted<>(REGISTRY.get("levitate"), 1),
            new Weighted<>(REGISTRY.get("vanish"), 1)
    );

    public static PowerUp randomPowerUp() {
        int total = WEIGHTS.stream().mapToInt(Weighted::weight).sum();
        int r = ThreadLocalRandom.current().nextInt(total); // [0,total)
        for (Weighted<PowerUp> w : WEIGHTS) {
            if ((r -= w.weight) < 0) return w.value;
        }
        return WEIGHTS.getLast().value(); // unreachable, but keeps compiler happy
    }
}
