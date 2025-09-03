package DKsMinigames.dKsMinigames.listeners;

import DKsMinigames.dKsMinigames.DKsMinigames; // <-- import your main class
import DKsMinigames.dKsMinigames.utils.ConfigHelper;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.Duration;

public class JoinListener implements Listener {
    private final ConfigHelper cfg;

    public JoinListener(Plugin plugin) {
        this.cfg = new ConfigHelper(plugin);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(DKsMinigames.NO_COLLIDE_TEAM);
        if (t == null) {
            t = sb.registerNewTeam(DKsMinigames.NO_COLLIDE_TEAM);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        t.addEntry(e.getPlayer().getName());

        e.getPlayer().teleport(cfg.getHubSpawn());
        e.getPlayer().playSound(e.getPlayer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);

        var mm = MiniMessage.miniMessage();
        Title title = Title.title(
                mm.deserialize("<gradient:#00e676:#ff4081:#00b0ff><bold>DK's Dude Dungeon!</bold></gradient>"),
                mm.deserialize("<yellow>Minigames</yellow> <gray>'n</gray> <gold>stuff</gold>"),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(400))
        );
        e.getPlayer().showTitle(title);
    }
}
