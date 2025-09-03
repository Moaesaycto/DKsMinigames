package DKsMinigames.dKsMinigames.commands;

// Import your Plugin
import DKsMinigames.dKsMinigames.DKsMinigames;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PingCommand implements CommandExecutor {
    private final DKsMinigames plugin;

    public PingCommand(DKsMinigames plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        int ping = player.getPing(); // Paper/modern Spigot
        String msg = plugin.getConfig().getString("ping-message", "Pong! Your latency is %ping% ms.")
                .replace("%ping%", String.valueOf(ping));
        player.sendMessage(msg);
        return true;
    }
}