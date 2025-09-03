package DKsMinigames.dKsMinigames.stats;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HighScoreStore {

    public static final class TopScore {
        public final String playerName;
        public final int score;
        private TopScore(String playerName, int score) { this.playerName = playerName; this.score = score; }
    }

    private static final class Entry {
        String lastName;
        int highScore;
        int gamesPlayed;
    }

    private final Plugin plugin;
    private final String gameName;
    private final File file;
    private final Map<UUID, Entry> data = new ConcurrentHashMap<>();

    public HighScoreStore(Plugin plugin, String gameName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gameName = Objects.requireNonNull(gameName, "gameName");
        File dir = new File(plugin.getDataFolder(), "highscores");
        if (!dir.exists()) dir.mkdirs();
        this.file = new File(dir, gameName.toLowerCase(Locale.ROOT) + ".yml");
        load();
    }

    /** Record a completed game for p; increments games played and updates high score only if beaten. */
    public synchronized void recordScore(Player p, int score) { recordScore(p.getUniqueId(), p.getName(), score); }

    /** Same as above for OfflinePlayer. */
    public synchronized void recordScore(OfflinePlayer p, int score) { recordScore(p.getUniqueId(), p.getName(), score); }

    private void recordScore(UUID id, String name, int score) {
        Entry e = data.computeIfAbsent(id, k -> new Entry());
        e.gamesPlayed += 1;
        e.lastName = name != null ? name : e.lastName;
        if (score > e.highScore) e.highScore = score;
        save();
    }

    /** 1) Highest score for a player by current/last known name; returns 0 if not found. */
    public synchronized int getHighScore(String playerName) {
        Entry e = findByName(playerName);
        return e != null ? e.highScore : 0;
    }

    /** 2) Highest score overall (name + score); returns Optional.empty() if no data. */
    public synchronized Optional<TopScore> getTopScore() {
        UUID bestId = null;
        int best = Integer.MIN_VALUE;
        for (var it : data.entrySet()) {
            if (it.getValue().highScore > best) {
                best = it.getValue().highScore;
                bestId = it.getKey();
            }
        }
        if (bestId == null) return Optional.empty();
        Entry e = data.get(bestId);
        return Optional.of(new TopScore(e.lastName != null ? e.lastName : bestId.toString(), e.highScore));
    }

    /** 3) Number of games a player (by name) has played; returns 0 if not found. */
    public synchronized int getGamesPlayed(String playerName) {
        Entry e = findByName(playerName);
        return e != null ? e.gamesPlayed : 0;
    }

    /** Optional: direct lookups by UUID if you need them. */
    public synchronized int getHighScore(UUID id) { return data.getOrDefault(id, new Entry()).highScore; }
    public synchronized int getGamesPlayed(UUID id) { return data.getOrDefault(id, new Entry()).gamesPlayed; }

    /** Optional: reset a single player or all. */
    public synchronized void resetPlayer(String playerName) {
        UUID target = findUuidByName(playerName);
        if (target != null) { data.remove(target); save(); }
    }
    public synchronized void resetAll() { data.clear(); save(); }

    // ===== persistence =====

    private void load() {
        data.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.contains("players")) return;

        for (String key : Objects.requireNonNull(cfg.getConfigurationSection("players")).getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Entry e = new Entry();
                e.lastName = cfg.getString("players." + key + ".name", null);
                e.highScore = cfg.getInt("players." + key + ".high", 0);
                e.gamesPlayed = cfg.getInt("players." + key + ".games", 0);
                data.put(id, e);
            } catch (IllegalArgumentException ignored) {
                // skip invalid UUID keys
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var it : data.entrySet()) {
            String base = "players." + it.getKey();
            Entry e = it.getValue();
            if (e.lastName != null) cfg.set(base + ".name", e.lastName);
            cfg.set(base + ".high", e.highScore);
            cfg.set(base + ".games", e.gamesPlayed);
        }
        cfg.set("meta.game", gameName);
        try {
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("[HighScoreStore] Failed to save " + file.getName() + ": " + ex.getMessage());
        }
    }

    // ===== helpers =====

    private Entry findByName(String name) {
        if (name == null) return null;
        String needle = name.trim();
        for (Entry e : data.values()) {
            if (e.lastName != null && e.lastName.equalsIgnoreCase(needle)) return e;
        }
        return null;
    }

    private UUID findUuidByName(String name) {
        if (name == null) return null;
        String needle = name.trim();
        for (var it : data.entrySet()) {
            Entry e = it.getValue();
            if (e.lastName != null && e.lastName.equalsIgnoreCase(needle)) return it.getKey();
        }
        return null;
    }


    public synchronized Optional<TopScore> getByRank(int rank) {
        if (rank <= 0) return Optional.empty();
        List<TopScore> list = getSortedTop();
        return rank <= list.size() ? Optional.of(list.get(rank - 1)) : Optional.empty();
    }

    public synchronized List<TopScore> getTop(int limit) {
        List<TopScore> list = getSortedTop();
        return list.subList(0, Math.min(limit, list.size()));
    }

    private List<TopScore> getSortedTop() {
        return data.entrySet().stream()
                .map(e -> new TopScore(
                        e.getValue().lastName != null ? e.getValue().lastName : e.getKey().toString(),
                        e.getValue().highScore))
                .sorted(Comparator
                        .comparingInt((TopScore t) -> t.score).reversed()
                        .thenComparing(t -> t.playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
