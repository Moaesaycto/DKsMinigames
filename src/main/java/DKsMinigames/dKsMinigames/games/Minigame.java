package DKsMinigames.dKsMinigames.games;

import DKsMinigames.dKsMinigames.stats.HighScoreStore;
import DKsMinigames.dKsMinigames.utils.ConfigHelper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class Minigame implements Listener {

    // ===== Constants =====
    public enum State { LOBBY, RUNNING, ENDED }
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    // ===== Fields =====
    protected final Plugin plugin;
    private final String name;
    private final Component prefix;           // Adventure prefix
    private final Set<UUID> players = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final int minPlayers;
    private volatile State state = State.LOBBY;
    private BukkitTask tickTask;
    private final NamespacedKey KEY_GAME;
    private final NamespacedKey KEY_ACTION;
    private final Set<Location> startSigns = new HashSet<>();
    private final Location spawn;
    private final ConfigHelper cfg;
    private BukkitTask lobbyBarTask;
    private BukkitTask countdownTask;

    // Scoreboard
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, org.bukkit.scoreboard.Scoreboard> memberBoards = new HashMap<>();
    private final String objectiveName;
    private final HighScoreStore highScoreStore;

    // Runtime protection flags (default: allow everything)
    private boolean canChangeInventory = true;
    private boolean playerCanBeHurt = true;
    private boolean playerCanBeHungry = true;
    private volatile boolean ending = false;

    // ===== Construction =====
    protected Minigame(Plugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
        this.minPlayers = getMinPlayers(plugin, name);
        this.prefix = buildPrefix(plugin, name);
        this.KEY_GAME = new NamespacedKey(plugin, "minigame");
        this.KEY_ACTION = new NamespacedKey(plugin, "action");
        this.cfg = new ConfigHelper(plugin);
        this.spawn = this.cfg.getGameSpawn(name);
        this.objectiveName = "mg" + Math.abs(name.hashCode());
        this.highScoreStore = new HighScoreStore(plugin, name);
    }

    // ===== Lifecycle =====
    public final void init() {
        registerEvents();
        onInit();
        log(this.name + " has been initialized");
    }

    public final void disable() {
        onDisable();
    }

    protected void onDisable() {}

    protected boolean canStart() {
        return playerCount() >= minPlayers && this.state == State.LOBBY;
    }


    public final void start() {
        if (state != State.LOBBY) { log(name + ": failed to start (bad state)", true); return; }
        if (!canStart()) { announce("Not enough players to start!", true); refreshStartSigns(); return; }
        if (countdownTask != null) { announce("Countdown already running.", true); return; }

        final int total = Math.max(1, cfg.getCountdown()); // seconds
        final int[] left = { total };

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != State.LOBBY || playerCount() < minPlayers) {
                cancelCountdown();
                announce("Countdown cancelled — need " + minPlayers + " players.", true);
                refreshStartSigns();
                return;
            }

            stopLobbyActionbar();

            if (left[0] > 0) {
                broadcastActionBar(Component.text("Starting in " + left[0] + "…", NamedTextColor.YELLOW));
                playPing(1.0f);
                left[0]--;
                return;
            }

            playEventSound();

            cancelCountdown();
            clearAllTitles();
            broadcastGoTitle();
            state = State.RUNNING;
            setInvulnerablePlayers(false);
            setInvulnerableSpectators(true);
            try {
                onStart();
            } catch (Exception e) {
                state = State.LOBBY;
                applyInvulnerabilityForState();
                log(name + ": failed to start: " + e.getMessage(), true);
                e.printStackTrace();
                return;
            }

            initScoreboard();
            refreshScoreboard();

            tickTask = Bukkit.getScheduler().runTaskTimer(getPlugin(), this::tickSafe, 1L, tickPeriodTicks());
            log(name + " game has started");
            announce("The game has begun!");
            refreshStartSigns();
        }, 0L, 20L);
    }

    public final void end() { end(null); }

    public final synchronized void end(@org.jetbrains.annotations.Nullable Player winner) {
        // If we're already ending/ended, just ignore (don’t spam an error)
        if (ending || state != State.RUNNING) return;

        ending = true; // lock re-entry
        cancelCountdown();
        state = State.ENDED;
        applyInvulnerabilityForState();

        forEveryone(p -> highScoreStore.recordScore(p, getPoints(p)));

        if (tickTask != null) tickTask.cancel();

        try {
            onEnd(winner != null ? winner.getUniqueId() : null);
        } finally {
            log(name + " game has ended");
            broadcastEndTitle(winner != null ? winner.getName() : null);
            clearXpBars();
            clearInventoriesAll();
            setInvulnerableAll(true);
            refreshStartSigns();
            // resetMemberScoreboards();
        }

        long delay = 20L * cfg.getEndWait(); // seconds -> ticks
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != State.ENDED) return;

            teleportPlayersToSpawn();
            players.addAll(spectators);
            spectators.clear();

            state = State.LOBBY;
            applyInvulnerabilityForState();
            clearAllTitles();
            startLobbyActionbar();
            refreshStartSigns();

            announce("The game has ended!");
            if (winner != null) announce("The winner was " + winner.getName());

            ending = false; // allow future games to end again
            log(name + " reset to lobby");
        }, delay);
    }


    protected abstract void onTick();
    protected void onInit() {}
    protected void onStart() {}
    protected void onEnd() {}
    protected void onEnd(@org.jetbrains.annotations.Nullable UUID winner) { onEnd(); }
    protected int tickPeriodTicks() { return 1; }

    // ===== Roster =====
    public boolean join(Player p) { return join(p, false); }

    public boolean join(Player p, boolean teleport) {
        if (p == null) return false;
        UUID id = p.getUniqueId();

        if (players.contains(id) || spectators.contains(id)) {
            announcePlayer("You are already in this game", p, true);
            return false;
        }

        boolean added = switch (state) {
            case LOBBY, ENDED -> players.add(id);
            case RUNNING -> spectators.add(id);
        };

        if (!added) {
            announcePlayer("Cannot join game. Try again later", p, true);
            return false;
        }

        onJoin(p);
        if (teleport) p.teleport(spawn);

        p.setInvulnerable(state != State.RUNNING);

        if (state != State.RUNNING) {
            startLobbyActionbar();
        }
        log(p.getName() + " has joined " + name);
        announce(p.getName() + " has joined the game!");
        refreshStartSigns();

        joinScoreboard(p);
        refreshScoreboard();
        return true;
    }

    public boolean spectate(Player p) {
        if (p == null) return false;
        UUID id = p.getUniqueId();

        if (spectators.contains(id)) {
            announcePlayer("You are already a spectator", p, true);
            return false;
        }
        if (!players.contains(id)) {
            announcePlayer("You must be in the game to spectate", p, true);
            return false;
        }

        players.remove(id);
        boolean added = spectators.add(id);
        if (added) {
            onSpectate(p);
            p.setInvulnerable(true); // ⟵ always invulnerable as spectator
            announcePlayer("You are now spectating", p, false);
            joinScoreboard(p);
            refreshScoreboard();

            log(p.getName() + " is now spectating " + name);
        }
        refreshStartSigns();
        return added;
    }

    public boolean leave(Player p) {
        if (p == null) return false;
        UUID id = p.getUniqueId();

        boolean removed = players.remove(id) | spectators.remove(id);
        if (!removed) {
            announcePlayer("You aren't in this game", p, true);
            return false;
        }

        onLeave(p);

        log(p.getName() + " has left " + name);
        announce(p.getName() + " has left the game");
        announcePlayer("You have left " + name, p, false);
        clearPlayerTitles(p);

        p.setInvulnerable(false); // ⟵ leaving = reset protection
        p.teleport(cfg.getHubSpawn());

        detachScoreboard(p);
        refreshScoreboard();

        if (players.isEmpty() && spectators.isEmpty() && state == State.RUNNING) end();
        refreshStartSigns();
        return true;
    }


    public boolean eliminate(Player p) {
        if (state != State.RUNNING || p == null) return false;
        UUID id = p.getUniqueId();
        if (!players.remove(id)) return false;

        boolean added = spectators.add(id);
        if (added) {
            onEliminate(p);
            p.teleport(spawn);
            p.setInvulnerable(true); // ⟵ eliminated = spectator, always invulnerable
            log(p.getName() + " has been eliminated in " + name);
            announce(p.getName() + " has been eliminated!");
        }

        refreshScoreboard();
        refreshStartSigns();
        return added;
    }

    @EventHandler public void onPlayerQuit(PlayerQuitEvent e) { handleDisconnect(e.getPlayer()); }
    @EventHandler public void onPlayerKick(PlayerKickEvent e) { handleDisconnect(e.getPlayer()); }

    private void handleDisconnect(Player p) {
        if (p == null) return;
        boolean wasIn = players.remove(p.getUniqueId()) | spectators.remove(p.getUniqueId());
        if (wasIn) announce(p.getName() + " left.");
        if (players.isEmpty() && spectators.isEmpty() && state == State.RUNNING) end();
        refreshStartSigns();
    }

    protected void onJoin(Player p) {}
    protected void onSpectate(Player p) {}
    protected void onLeave(Player p) {}
    protected void onEliminate(Player p) {}

    // ===== Announce (Adventure) =====
    protected void announcePlayer(String m, Player p) { announcePlayer(m, p, false); }

    protected void announcePlayer(String m, Player p, boolean error) {
        TextColor col = error ? NamedTextColor.RED : NamedTextColor.WHITE;
        p.sendMessage(prefix.append(Component.space()).append(Component.text(m, col)));
    }

    private void announceTo(String m, boolean error, Collection<Collection<UUID>> groups) {
        TextColor col = error ? NamedTextColor.RED : NamedTextColor.WHITE;
        Component msg = prefix.append(Component.space()).append(Component.text(m, col));
        for (Collection<UUID> g : groups) {
            for (UUID id : g) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(msg);
            }
        }
    }

    private void announceTo(String m, Collection<Collection<UUID>> groups) { announceTo(m, false, groups); }

    protected void playersAnnounce(String m) { announceTo(m, List.of(players)); }
    protected void playersAnnounce(String m, boolean error) { announceTo(m, error, List.of(players)); }
    protected void spectatorsAnnounce(String m) { announceTo(m, List.of(spectators)); }
    protected void spectatorsAnnounce(String m, boolean e)   { announceTo(m, e, List.of(spectators)); }
    protected void announce(String m) { announceTo(m, List.of(players, spectators)); }
    protected void announce(String m, boolean e) { announceTo(m, e, List.of(players, spectators)); }

    private void startLobbyActionbar() {
        if (lobbyBarTask != null) return;
        lobbyBarTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (state == State.LOBBY) {
                if (playerCount() >= minPlayers) {
                    broadcastActionBar(Component.text("Ready to start", NamedTextColor.YELLOW));
                } else {
                    broadcastActionBar(Component.text("Waiting for players " + playerCount() + "/" + minPlayers, NamedTextColor.YELLOW));
                }
            } else if (state == State.ENDED) {
                broadcastActionBar(Component.text("Game ended back to lobby", NamedTextColor.GRAY));
            }
        }, 0L, 40L);
    }

    private void broadcastGoTitle() {
        var title = Component.text(name + " has begun", NamedTextColor.GREEN);
        var sub = Component.text("Good luck!", NamedTextColor.WHITE);
        var times = Title.Times.times(
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofMillis(400)
        );
        var full = Title.title(title, sub, times);

        forEveryone(p -> p.showTitle(full));
    }

    private void broadcastEndTitle() { broadcastEndTitle(null); }

    private void broadcastEndTitle(String victor) {
        var title = Component.text("GAME OVER!", NamedTextColor.DARK_RED);
        var sub = (victor == null)
                ? Component.text("No winner this time.", NamedTextColor.WHITE)
                : Component.text(victor + " has won " + this.name, NamedTextColor.WHITE);
        var times = Title.Times.times(
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofMillis(400)
        );
        var full = Title.title(title, sub, times);

        for (UUID id : players)     { var p = Bukkit.getPlayer(id); if (p != null) p.showTitle(full); }
        for (UUID id : spectators)  { var p = Bukkit.getPlayer(id); if (p != null) p.showTitle(full); }
    }

    private void cancelCountdown() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
    }

    private void stopLobbyActionbar() {
        if (lobbyBarTask != null) { lobbyBarTask.cancel(); lobbyBarTask = null; }
    }

    private void broadcastActionBar(Component body) {
        Component msg = prefix.append(Component.space()).append(body);
        players.forEach(id -> { var p = Bukkit.getPlayer(id); if (p != null) p.sendActionBar(msg); });
        spectators.forEach(id -> { var p = Bukkit.getPlayer(id); if (p != null) p.sendActionBar(msg); });
    }

    private void clearAllTitles() {
        for (UUID id : players)    { var pl = Bukkit.getPlayer(id); if (pl != null) pl.clearTitle(); }
        for (UUID id : spectators) { var sp = Bukkit.getPlayer(id); if (sp  != null) sp.clearTitle(); }
    }

    private void clearPlayerTitles(Player p) { p.clearTitle(); }

    protected void log(String m) { log(m, false); }
    protected void log(String m, boolean error) {
        if (error) plugin.getLogger().severe(m);
        else plugin.getLogger().info(m);
    }

    private void playPing(float pitch) {
        playersPlaySound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    private void playEventSound() {
        playersPlaySound(Sound.ENTITY_PLAYER_LEVELUP);
    }

    // ===== Queries =====
    public String name() { return name; }
    public State state() { return state; }
    public int playerCount() { return players.size(); }
    public boolean isRunning() { return state == State.RUNNING; }
    public Set<UUID> players() { return Collections.unmodifiableSet(players); }
    public Set<UUID> spectators() { return Collections.unmodifiableSet(spectators); }

    // ===== Internals =====
    private void tickSafe() {
        try {
            preTick();
            onTick();
            postTick();
        } catch (Throwable t) {
            log("Error during " + name + ". Ending game...");
            end();
        }
    }

    protected void preTick() {}
    protected void postTick() {}

    protected Plugin getPlugin() { return this.plugin; }
    protected void registerEvents() { Bukkit.getPluginManager().registerEvents(this, getPlugin()); }
    protected void unregisterEvents() { org.bukkit.event.HandlerList.unregisterAll(this); }
    protected ThreadLocalRandom rnd() { return ThreadLocalRandom.current(); }

    // ===== Config helpers =====
    protected static Component buildPrefix(Plugin plugin, String name) {
        var m = plugin.getConfig().getMapList("games").stream()
                .filter(x -> name.equalsIgnoreCase((String) x.get("name")))
                .findFirst().orElseThrow(() -> new IllegalStateException("No " + name + " config!"));

        TextColor primary = parseColor((String) m.get("primary-color"));
        TextColor secondary = parseColor((String) m.get("secondary-color"));

        TextComponent.Builder b = Component.text();
        b.append(Component.text("[", primary).decorate(TextDecoration.BOLD));
        b.append(Component.text(name, secondary).decorate(TextDecoration.BOLD));
        b.append(Component.text("]", primary).decorate(TextDecoration.BOLD));
        b.append(Component.empty().decoration(TextDecoration.BOLD, false)); // reset
        return b.build();
    }

    protected static int getMinPlayers(Plugin plugin, String name) {
        FileConfiguration c = plugin.getConfig();
        Map<?, ?> m = c.getMapList("games").stream()
                .filter(x -> name.equalsIgnoreCase((String) x.get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + name + " config!"));
        return ((Number) m.get("min-players")).intValue();
    }

    private static TextColor parseColor(String raw) {
        if (raw == null) return NamedTextColor.WHITE;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        TextColor named = NamedTextColor.NAMES.value(s);
        if (named != null) return named;
        if (!s.startsWith("#")) s = "#" + s;
        TextColor hex = TextColor.fromHexString(s);
        return hex != null ? hex : NamedTextColor.WHITE;
    }

    private Component prefixComponent() { return prefix; }
    protected ConfigHelper getConfigHelper() { return this.cfg; }

    // ===== Sign handlers =====
    @EventHandler
    public void onSignCreate(SignChangeEvent e) {
        String l0 = PLAIN.serialize(Objects.requireNonNullElse(e.line(0), Component.empty()));
        if (!name.equalsIgnoreCase(l0)) return;

        String action = Optional.ofNullable(e.line(2)).map(PLAIN::serialize)
                .filter(s -> !s.isBlank()).orElse("Join");

        e.line(0, prefixComponent());
        e.line(1, Component.empty());
        e.line(2, Component.text(action));
        e.line(3, Component.empty());

        Sign sign = (Sign) e.getBlock().getState();
        sign.setWaxed(true);
        sign.getPersistentDataContainer().set(KEY_GAME,   PersistentDataType.STRING, name.toLowerCase(Locale.ROOT));
        sign.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action.toLowerCase(Locale.ROOT));

        if ("start".equalsIgnoreCase(action)) {
            startSigns.add(sign.getLocation());
            refreshStartSigns();
        }
        sign.update(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignBreak(BlockBreakEvent e) {
        if (!(e.getBlock().getState() instanceof Sign sign)) return;

        var pdc = sign.getPersistentDataContainer();
        String g = pdc.get(KEY_GAME, PersistentDataType.STRING);
        String a = pdc.get(KEY_ACTION, PersistentDataType.STRING);
        if (g == null || !g.equalsIgnoreCase(name)) return;

        if (!e.getPlayer().hasPermission("dksminigames.editsigns." + name.toLowerCase(Locale.ROOT))) {
            e.setCancelled(true);
            sign.getSide(Side.FRONT).line(0, prefixComponent());
            sign.getSide(Side.FRONT).line(1, Component.empty());
            sign.getSide(Side.FRONT).line(2, Component.text(a != null ? a : ""));
            sign.getSide(Side.FRONT).line(3, Component.empty());
            sign.setWaxed(true);
            sign.update(true);
            e.getPlayer().sendMessage(prefix.append(Component.space()).append(Component.text("This sign is protected.", NamedTextColor.RED)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock(); if (b == null) return;
        if (!(b.getState() instanceof Sign sign)) return;

        var pdc = sign.getPersistentDataContainer();
        String g = pdc.get(KEY_GAME, PersistentDataType.STRING);
        String a = pdc.get(KEY_ACTION, PersistentDataType.STRING);
        if (g == null || a == null || !g.equalsIgnoreCase(name)) return;

        e.setCancelled(true);

        switch (a.toLowerCase(Locale.ROOT)) {
            case "join"  -> { join(e.getPlayer(), true); return; }
            case "leave" -> { leave(e.getPlayer()); return; }
            case "start" -> {
                if (e.getPlayer().hasPermission("dksminigames.start." + name.toLowerCase(Locale.ROOT))) start();
                return;
            }
            case "end" -> {
                if (e.getPlayer().hasPermission("dksminigames.end." + name.toLowerCase(Locale.ROOT))) end();
                return;
            }
            default -> e.getPlayer().sendMessage(prefix.append(Component.space()).append(Component.text("Unknown action.", NamedTextColor.RED)));
        }
    }

    private void refreshStartSigns() {
        boolean glow = canStart();
        for (Location loc : startSigns) {
            var state = loc.getBlock().getState();
            if (state instanceof Sign s) {
                s.getSide(Side.FRONT).setGlowingText(glow);
                s.update(true, false);
            }
        }
    }

    // ===== Protections (active only while RUNNING & in this game) =====
    public void setCanChangeInventory(boolean e) { this.canChangeInventory = e; }
    public void setPlayerCanBeHurt(boolean e) { this.playerCanBeHurt = e; }
    public void setPlayerCanBeHungry(boolean e) { this.playerCanBeHungry = e; }

    private boolean activeAndInGame(Player p) {
        return state == State.RUNNING && isInGame(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!activeAndInGame(p)) return;
        if (!canChangeInventory) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!activeAndInGame(p)) return;
        if (!canChangeInventory) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!activeAndInGame(e.getPlayer())) return;
        if (!canChangeInventory) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!activeAndInGame(e.getPlayer())) return;
        if (!canChangeInventory) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!activeAndInGame(p)) return;
        if (!playerCanBeHurt) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!activeAndInGame(p)) return;
        if (!playerCanBeHungry) {
            e.setCancelled(true);
            e.setFoodLevel(20);
            p.setSaturation(20f);
        }
    }


    // ===== Player helpers =====
    protected List<Player> getPlayers() {
        return players.stream()
            .map(Bukkit::getPlayer)   // UUID → Player (null if offline)
            .filter(p -> p != null && p.isOnline())
            .collect(Collectors.toList());
    }

    protected boolean isInGame(Player p) {
        UUID u = p.getUniqueId();
        return players.contains(u) || spectators.contains(u);
    }

    protected void forEachPlayer(Consumer<Player> action) {
        players.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) action.accept(p);
        });
    }

    protected void forEachSpectator(Consumer<Player> action) {
        spectators.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) action.accept(p);
        });
    }

    protected void forEveryone(Consumer<Player> action) {
        forEachPlayer(action);
        forEachSpectator(action);
    }

    protected boolean isPlaying(Player p) {
        return this.players.contains(p.getUniqueId());
    }

    protected void playersPlaySound(Sound sound) {
        forEveryone(p -> p.playSound(p, sound, SoundCategory.MASTER, 1.0f, 1.0f));
    }

    protected @org.jetbrains.annotations.Nullable Player bestByPoints() {
        UUID bestId = null;
        int best = Integer.MIN_VALUE;
        boolean tie = false;

        // check all players + spectators
        for (UUID id : players()) {
            int s = getPoints(id);
            if (s > best) {
                best = s;
                bestId = id;
                tie = false; // new leader
            } else if (s == best && bestId != null) {
                tie = true;  // found equal top
            }
        }
        for (UUID id : spectators()) {
            int s = getPoints(id);
            if (s > best) {
                best = s;
                bestId = id;
                tie = false;
            } else if (s == best && bestId != null) {
                tie = true;
            }
        }

        if (tie || bestId == null) return null; // null → “no winner”
        return Bukkit.getPlayer(bestId);
    }



    // ===== Helpers (Minigame) =====
    protected void setInvulnerablePlayers(boolean v) {
        players().forEach(id -> { var p = Bukkit.getPlayer(id); if (p != null) p.setInvulnerable(v); });
    }
    protected void setInvulnerableSpectators(boolean v) {
        spectators().forEach(id -> { var p = Bukkit.getPlayer(id); if (p != null) p.setInvulnerable(v); });
    }
    protected void setInvulnerableAll(boolean v) {
        setInvulnerablePlayers(v);
        setInvulnerableSpectators(v);
    }

    private void teleportPlayersToSpawn() {
        forEachPlayer(p -> p.teleport(spawn));
    }

    protected void clearXpBars() {
        forEveryone(p -> { p.setExp(0f); p.setLevel(0); });
    }

    protected void clearInventoriesAll() {
        forEveryone(p -> {
            p.getInventory().clear();
            p.setItemOnCursor(null);
        });
    }

    private void applyInvulnerabilityForState() {
        boolean inv = (state != State.RUNNING);
        setInvulnerablePlayers(inv);
        setInvulnerableSpectators(inv);
    }

    // ===== Points / Scoreboard =====
    protected void initScoreboard() {
        points.clear();
        // initialise everyone (players + spectators) to 0 and attach a fresh board
        forEveryone(pl -> {
            points.put(pl.getUniqueId(), 0);
            attachBoard(pl);
        });

        if (state == State.LOBBY) {
            forEveryone(this::renderDefaultSidebar);
        }
    }

    protected void joinScoreboard(Player p) {
        if (!isInGame(p)) return;
        points.putIfAbsent(p.getUniqueId(), 0);
        attachBoard(p);

        if (state == State.LOBBY || state == State.ENDED) {
            renderDefaultSidebar(p);
        }
    }

    protected void refreshScoreboard() {
        if (state != State.RUNNING) return;   // keep last shown board after GAME OVER
        forEveryone(this::renderSidebarFor);
    }

    protected void addScoreboard(Player p, int delta) {
        if (p == null || !isInGame(p)) return;
        points.merge(p.getUniqueId(), delta, Integer::sum);
        refreshScoreboard();
    }

    // ===== Internal scoreboard utils =====
    private void attachBoard(Player p) {
        org.bukkit.scoreboard.ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        org.bukkit.scoreboard.Scoreboard sb = sm.getNewScoreboard();
        memberBoards.put(p.getUniqueId(), sb);

        // create objective each time; we re-render content on refresh
        org.bukkit.scoreboard.Objective obj =
                sb.getObjective(objectiveName) != null ? sb.getObjective(objectiveName)
                        : sb.registerNewObjective(objectiveName, "dummy", PLAIN.serialize(prefix));
        obj.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);

        // ensure teams for colour-coding names
        ensureTeam(sb, "active", NamedTextColor.WHITE);
        ensureTeam(sb, "elim", NamedTextColor.GRAY);

        p.setScoreboard(sb);
    }

    private void detachScoreboard(Player p) {
        if (p == null) return;
        memberBoards.remove(p.getUniqueId());
        // return them to the main server board
        org.bukkit.scoreboard.ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm != null) p.setScoreboard(sm.getMainScoreboard());
        points.remove(p.getUniqueId());
    }

    private void resetMemberScoreboards() {
        org.bukkit.scoreboard.ScoreboardManager sm = Bukkit.getScoreboardManager();
        org.bukkit.scoreboard.Scoreboard main = sm != null ? sm.getMainScoreboard() : null;
        forEveryone(pl -> { if (main != null) pl.setScoreboard(main); });
        memberBoards.clear();
        points.clear();
    }

    private void renderSidebarFor(Player viewer) {
        org.bukkit.scoreboard.Scoreboard sb = memberBoards.get(viewer.getUniqueId());
        if (sb == null) { attachBoard(viewer); sb = memberBoards.get(viewer.getUniqueId()); }
        if (sb == null) return;

        // nuke existing lines by unregistering & re-registering the objective (keeps title)
        Objective old = sb.getObjective(objectiveName);
        if (old != null) old.unregister();

        Objective obj = sb.registerNewObjective(objectiveName, "dummy", ""); // dummy name
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.displayName(prefix);

        // build lines, max 15; we keep them unique by suffixing invisible counters where needed
        List<String> lines = new ArrayList<>(15);

        // 1) Player stats for the viewer
        boolean playing = isPlaying(viewer);
        boolean eliminated = spectators.contains(viewer.getUniqueId());
        String state = eliminated ? "ELIMINATED" : (playing ? "PLAYING" : "SPECTATING");
        String stateCol = eliminated ? "§c" : (playing ? "§a" : "§b");
        lines.add("§7Your Status: " + stateCol + state);
        int myScore = points.getOrDefault(viewer.getUniqueId(), 0);
        lines.add("§7Your Score: §f" + myScore);
        int myBest = highScoreStore.getHighScore(viewer.getUniqueId());
        lines.add("§7Your Best: §f" + myBest);

        lines.add(" "); // spacer

        // 2) Current game table (players and their points)
        lines.add("§6Players:");
        // sort by score desc, then name
        List<Player> everyone = new ArrayList<>();
        forEachPlayer(everyone::add);
        forEachSpectator(everyone::add);
        everyone.sort(Comparator
                .comparingInt((Player pl) -> points.getOrDefault(pl.getUniqueId(), 0)).reversed()
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int budget = 15 - lines.size(); // room left for entries
        int shown = 0;
        for (Player pl : everyone) {
            if (shown >= budget) break;
            boolean elim = spectators.contains(pl.getUniqueId());
            String nameCol = elim ? "§7" : "§f";
            int sc = points.getOrDefault(pl.getUniqueId(), 0);
            lines.add(nameCol + trimTo(pl.getName(), 12) + " §8: §f" + sc);
            shown++;
        }

        // push to objective with descending scores so it renders top→bottom
        int score = lines.size();
        for (String ln : lines) {
            // unique entry trick: append §r repeats to avoid duplicate line collisions
            String unique = uniquify(sb, ln);
            obj.getScore(unique).setScore(score--);
        }
    }

    private void renderDefaultSidebar(Player viewer) {
        Scoreboard sb = memberBoards.get(viewer.getUniqueId());
        if (sb == null) {
            attachBoard(viewer);
            sb = memberBoards.get(viewer.getUniqueId());
        }
        if (sb == null) return;

        // wipe and re-register objective
        Objective old = sb.getObjective(objectiveName);
        if (old != null) old.unregister();
        Objective obj = sb.registerNewObjective(objectiveName, "dummy", "");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.displayName(prefix); // coloured title

        List<String> lines = new ArrayList<>();
        lines.add("§7Waiting in lobby…");
        lines.add("§7Players: §f" + playerCount() + "/" + minPlayers);
        int best = highScoreStore.getHighScore(viewer.getUniqueId());
        lines.add("§7Your Best: §f" + best);

        int score = lines.size();
        for (String ln : lines) {
            obj.getScore(ln).setScore(score--);
        }
    }

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String uniquify(org.bukkit.scoreboard.Scoreboard sb, String base) {
        String out = base;
        int i = 0;
        while (hasEntry(sb, out) && i < 10) { out = base + "§r".repeat(++i); }
        return out;
    }

    private boolean hasEntry(org.bukkit.scoreboard.Scoreboard sb, String e) {
        for (String ex : sb.getEntries()) if (ex.equals(e)) return true;
        return false;
    }

    private void ensureTeam(org.bukkit.scoreboard.Scoreboard sb, String key, NamedTextColor color) {
        org.bukkit.scoreboard.Team t = sb.getTeam(key);
        if (t == null) t = sb.registerNewTeam(key);
        t.color(color);
        t.setCanSeeFriendlyInvisibles(false);
        t.setAllowFriendlyFire(true);
    }

    // Call when a player flips between active↔eliminated to keep name colour in tab/nametag if you like:
    private void applyNameTeam(Player pl) {
        org.bukkit.scoreboard.Scoreboard sb = memberBoards.get(pl.getUniqueId());
        if (sb == null) return;
        boolean elim = spectators.contains(pl.getUniqueId());
        org.bukkit.scoreboard.Team active = sb.getTeam("active");
        org.bukkit.scoreboard.Team elimT  = sb.getTeam("elim");
        if (active != null) active.removeEntry(pl.getName());
        if (elimT  != null) elimT.removeEntry(pl.getName());
        if (elim && elimT != null) elimT.addEntry(pl.getName());
        if (!elim && active != null) active.addEntry(pl.getName());
    }

    protected int getPoints(Player p) { return points.getOrDefault(p.getUniqueId(), 0); }
    protected int getPoints(UUID id)  { return points.getOrDefault(id, 0); }
    protected Map<UUID,Integer> getAllPoints() { return Collections.unmodifiableMap(points); }

    protected void setPoints(Player p, int newScore) {
        if (p == null || !isInGame(p)) return;
        points.put(p.getUniqueId(), newScore);
        refreshScoreboard();
    }

    protected void setPoints(UUID id, int newScore) {
        points.put(id, newScore);
        refreshScoreboard();
    }

    // ===== Debugging =====
    public void printPlayerInfo(Player p) {
        p.sendMessage(prefix.append(Component.space()).append(Component.text("Players:", NamedTextColor.GOLD)));
        if (players.isEmpty()) {
            p.sendMessage(Component.text(" (none)", NamedTextColor.GRAY));
        } else {
            for (UUID id : players) {
                Player pl = Bukkit.getPlayer(id);
                p.sendMessage(Component.text(" - " + (pl != null ? pl.getName() : id), NamedTextColor.GREEN));
            }
        }

        p.sendMessage(prefix.append(Component.space()).append(Component.text("Spectators:", NamedTextColor.GOLD)));
        if (spectators.isEmpty()) {
            p.sendMessage(Component.text(" (none)", NamedTextColor.GRAY));
        } else {
            for (UUID id : spectators) {
                Player pl = Bukkit.getPlayer(id);
                p.sendMessage(Component.text(" - " + (pl != null ? pl.getName() : id), NamedTextColor.AQUA));
            }
        }
    }

    public boolean command(String[] args, Player p) {
        if (args == null || args.length == 0) return onCommand(args, p);

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> { this.start(); return true; }
            case "end" -> {
                if (this.state() != Minigame.State.RUNNING) {
                    announcePlayer("Game cannot be ended in current state", p, true);
                } else this.end();
                return true;
            }
            case "join"   -> { this.join(p); return true; }
            case "leave"  -> { this.leave(p); return true; }
            case "players"-> { this.printPlayerInfo(p); return true; }
            case "highscore", "hs", "top" -> { return handleHighscore(Arrays.copyOfRange(args, 1, args.length), p); }
            default -> {
                // let child classes try; if they return false, show a short hint
                if (onCommand(args, p)) return true;
                announcePlayer("Unknown command. Try: start | end | join | leave | players | highscore", p, true);
                return true;
            }
        }
    }

    // Child hooks; override in subclasses for custom commands.
    protected boolean onCommand(String[] args, Player p) { return false; }

    // --- Highscore subcommand handling ---
    private boolean handleHighscore(String[] args, Player p) {
        // /<minigame> highscore
        if (args.length == 0) {
            int hs = highScoreStore.getHighScore(p.getUniqueId());
            int gp = highScoreStore.getGamesPlayed(p.getUniqueId());
            announcePlayer("Your high score: " + hs + " (games: " + gp + ")", p, false);
            return true;
        }

        String q = args[0];

        // rank: digits only => 1 is best
        if (q.chars().allMatch(Character::isDigit)) {
            int rank = Integer.parseInt(q);
            var res = highScoreStore.getByRank(rank);
            if (res.isPresent()) {
                var t = res.get();
                announcePlayer("#" + rank + ": " + t.playerName + " — " + t.score, p, false);
            } else {
                announcePlayer("No entry for rank #" + rank, p, true);
            }
            return true;
        }

        // player name lookup
        int hs = highScoreStore.getHighScore(q);
        int gp = highScoreStore.getGamesPlayed(q);
        if (hs == 0 && gp == 0) {
            announcePlayer("No record for " + q, p, true);
        } else {
            announcePlayer(q + "'s high score: " + hs + " (games: " + gp + ")", p, false);
        }
        return true;
    }
}
