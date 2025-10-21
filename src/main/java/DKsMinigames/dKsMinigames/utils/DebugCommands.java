package DKsMinigames.dKsMinigames.utils;

import DKsMinigames.dKsMinigames.objects.PowerUp.PowerUp;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;

public class DebugCommands implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    public DebugCommands(Plugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!sender.hasPermission("dks.debug")) { sender.sendMessage("No permission."); return true; }
        if (args.length == 0) { sender.sendMessage("/debug powerup [x y z]"); return true; }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "powerup" -> {
                Location base = p.getLocation();
                double x = (args.length > 1) ? parseCoord(args[1], base.getX()) : base.getBlockX();
                double y = (args.length > 2) ? parseCoord(args[2], base.getY()) : base.getBlockY();
                double z = (args.length > 3) ? parseCoord(args[3], base.getZ()) : base.getBlockZ();

                new PowerUp(
                        plugin,
                        new Location(p.getWorld(), x + 0.5, y, z + 0.5),
                        pl -> pl.sendMessage("DEBUG: Item collected"),
                        pl -> true,
                        200 // ~10s
                );
                p.sendMessage("Spawned PowerUp at " + (int)x + " " + (int)y + " " + (int)z + ".");
                return true;
            }
            default -> {
                sender.sendMessage("Unknown debug action.");
                return true;
            }
        }
    }

    // Supports "~", "~n", or absolute; trims to int when whole.
    private double parseCoord(String token, double origin) {
        token = token.trim();
        if (token.equals("~")) return origin;
        if (token.startsWith("~")) {
            String offset = token.substring(1);
            double o = offset.isEmpty() ? 0 : Double.parseDouble(offset);
            return origin + o;
        }
        return Double.parseDouble(token);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) return List.of();
        if (a.length == 1) return List.of("powerup");
        if (a.length == 2) return List.of("~", String.valueOf(p.getLocation().getBlockX()));
        if (a.length == 3) return List.of("~", String.valueOf(p.getLocation().getBlockY()));
        if (a.length == 4) return List.of("~", String.valueOf(p.getLocation().getBlockZ()));
        return Arrays.asList();
    }
}
