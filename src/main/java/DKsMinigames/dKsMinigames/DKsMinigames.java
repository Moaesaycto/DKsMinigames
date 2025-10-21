package DKsMinigames.dKsMinigames;

import DKsMinigames.dKsMinigames.commands.PingCommand;
import DKsMinigames.dKsMinigames.games.BlockParty.BlockParty;
import DKsMinigames.dKsMinigames.games.ElectricFloor.ElectricFloor;
import DKsMinigames.dKsMinigames.games.Minigame;
import DKsMinigames.dKsMinigames.utils.DebugCommands;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class DKsMinigames extends JavaPlugin {
    public static final String NO_COLLIDE_TEAM = "global_nocollide";
    private BlockParty blockParty;
    private ElectricFloor electricFloor;
    private List<Minigame> minigames;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        this.getCommand("ping").setExecutor(new PingCommand(this));

        var debug = new DebugCommands(this);
        var debugCmd = this.getCommand("debug");
        if (debugCmd != null) { // avoid NPE if plugin.yml is wrong
            debugCmd.setExecutor(debug);
            debugCmd.setTabCompleter(debug);
        }

        this.minigames = new ArrayList<Minigame>();

        this.minigames.add(new BlockParty(this));
        this.minigames.add(new ElectricFloor(this));

        // getConfig().options().copyDefaults(true);
        // saveConfig();

        minigames.forEach(Minigame::init);

        // FIXME: UNCOMMENT THESE!
        // Registering listeners
        // getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        //getServer().getPluginManager().registerEvents(new GlobalPvpListener(), this);
        Team t = ensureNoCollideTeam();
        Bukkit.getOnlinePlayers().forEach(p -> t.addEntry(p.getName()));

        Bukkit.getLogger().info("DK'S Minigames Loaded!");
    }

    @Override
    public void onDisable() {
        // saveConfig();

        minigames.forEach(Minigame::disable);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command cmd,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }

        switch (cmd.getName().toLowerCase()) {
            case "blockparty":
                return blockParty.command(args, p);      // ← delegate & return result
            case "electricfloor":
                return electricFloor.command(args, p);   // ← delegate & return result
            default:
                return false; // let Bukkit show usage if configured
        }
    }


    // ===== Global Server Properties =====
    private Team ensureNoCollideTeam() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(NO_COLLIDE_TEAM);
        if (t == null) t = sb.registerNewTeam(NO_COLLIDE_TEAM);
        t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        return t;
    }
}
