package org.example.h4dro.levelStar;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LevelStar extends JavaPlugin implements Listener {

    private Connection connection;
    // Cache quản lý người chơi đang online - Tự động dọn dẹp tại onQuit
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final List<LevelRange> parsedRanges = new ArrayList<>();

    private ScheduledExecutorService worker;
    private int pointsPerKill, pointsPerLevel, maxLevel, minLevel, flushIntervalSeconds;
    private String levelFormat, levelUpMessage, levelUpMessageType;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        setupDatabase();
        loadLevelRanges();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("levelstar") != null) {
            getCommand("levelstar").setExecutor((sender, cmd, label, args) -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("levelstar.reload")) {
                        sender.sendMessage(ChatColor.RED + "No permission!");
                        return true;
                    }
                    reloadConfig();
                    loadConfigValues();
                    loadLevelRanges();
                    sender.sendMessage(ChatColor.GREEN + "[LevelStar] Config reloaded!");
                    return true;
                }
                return false;
            });
        }

        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LevelStar-FlushWorker");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::flushCacheToDbSafe, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LevelStarExpansion(this).register();
        }
    }

    @Override
    public void onDisable() {
        if (worker != null) {
            worker.shutdown();
            try { worker.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        flushCacheToDb();
        closeDatabase();
    }


    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            PlayerData data = loadPlayerData(uuid);
            cache.put(uuid, data);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        PlayerData data = cache.remove(uuid);
        if (data != null) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveSinglePlayerData(uuid, data));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onKill(PlayerDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;

        PlayerData data = cache.get(killer.getUniqueId());
        if (data == null) return;

        data.points += pointsPerKill;
        boolean leveledUp = false;

        while (data.points >= pointsPerLevel && data.level < maxLevel) {
            data.points -= pointsPerLevel;
            data.level++;
            leveledUp = true;
        }

        if (leveledUp) {
            sendLevelUpMessage(killer, data.level);
        }
    }


    private void setupDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/data.db");
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS levels (uuid TEXT PRIMARY KEY, level INTEGER, points INTEGER)");
            }
        } catch (SQLException e) {
            getLogger().severe("DB Connection Failed: " + e.getMessage());
        }
    }

    private PlayerData loadPlayerData(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT level, points FROM levels WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new PlayerData(rs.getInt("level"), rs.getInt("points"));
            }
        } catch (SQLException e) {
            getLogger().warning("Error loading " + uuid + ": " + e.getMessage());
        }
        return new PlayerData(minLevel, 0);
    }

    private void saveSinglePlayerData(UUID uuid, PlayerData data) {
        if (connection == null) return;
        synchronized (connection) {
            String sql = "INSERT INTO levels(uuid, level, points) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET level=excluded.level, points=excluded.points";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, data.level);
                ps.setInt(3, data.points);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("Save failed for " + uuid);
            }
        }
    }

    private void flushCacheToDbSafe() {
        try { flushCacheToDb(); } catch (Exception e) { getLogger().warning("Flush failed: " + e.getMessage()); }
    }

    private void flushCacheToDb() {
        if (connection == null || cache.isEmpty()) return;
        synchronized (connection) {
            String sql = "INSERT INTO levels(uuid, level, points) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET level=excluded.level, points=excluded.points";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                connection.setAutoCommit(false);
                for (Map.Entry<UUID, PlayerData> entry : cache.entrySet()) {
                    ps.setString(1, entry.getKey().toString());
                    ps.setInt(2, entry.getValue().level);
                    ps.setInt(3, entry.getValue().points);
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                getLogger().warning("Batch update failed.");
            }
        }
    }

    private void closeDatabase() {
        try { if (connection != null && !connection.isClosed()) connection.close(); } catch (SQLException ignored) {}
    }


    private void loadConfigValues() {
        pointsPerKill = getConfig().getInt("points.per_kill", 50);
        pointsPerLevel = getConfig().getInt("points.per_level", 5000);
        maxLevel = getConfig().getInt("max-level", 2000);
        minLevel = getConfig().getInt("min-level", 1);
        flushIntervalSeconds = Math.max(10, getConfig().getInt("flush-interval-seconds", 30));
        levelFormat = getConfig().getString("level-format", "LVL {level}");
        levelUpMessage = getConfig().getString("level-up-message", "&a[LevelStar] %player% reached {display}!");
        levelUpMessageType = getConfig().getString("level-up-message-type", "private");
    }

    private void loadLevelRanges() {
        parsedRanges.clear();
        if (getConfig().isConfigurationSection("levels")) {
            for (String key : getConfig().getConfigurationSection("levels").getKeys(false)) {
                String color = getConfig().getString("levels." + key);
                Pattern p = Pattern.compile("^(\\d+)(?:-(\\d+))?$");
                Matcher m = p.matcher(key);
                if (m.matches()) {
                    int min = Integer.parseInt(m.group(1));
                    int max = m.group(2) != null ? Integer.parseInt(m.group(2)) : min;
                    parsedRanges.add(new LevelRange(min, max, color));
                }
            }
        }
        parsedRanges.sort(Comparator.comparingInt(r -> r.min));
    }

    public String formatLevel(UUID uuid) {
        PlayerData data = cache.get(uuid);
        int lvl = (data != null) ? data.level : minLevel;
        String color = "#FFFFFF";
        for (LevelRange r : parsedRanges) {
            if (lvl >= r.min && lvl <= r.max) { color = r.color; break; }
        }
        String text = levelFormat.replace("{level}", String.valueOf(lvl));
        return colorize(color, text) + ChatColor.RESET;
    }

    private void sendLevelUpMessage(Player p, int newLevel) {
        String formatted = formatLevel(p.getUniqueId());
        String msg = ChatColor.translateAlternateColorCodes('&', levelUpMessage
                .replace("%player%", p.getName())
                .replace("{display}", formatted)
                .replace("%level%", String.valueOf(newLevel)));

        if ("broadcast".equalsIgnoreCase(levelUpMessageType)) Bukkit.broadcastMessage(msg);
        else p.sendMessage(msg);
    }

    private String colorize(String color, String text) {
        try {
            if (color.startsWith("#")) return net.md_5.bungee.api.ChatColor.of(color) + text;
            return ChatColor.translateAlternateColorCodes('&', color) + text;
        } catch (Exception e) { return text; }
    }


    static class PlayerData {
        int level, points;
        PlayerData(int level, int points) { this.level = level; this.points = points; }
    }

    static class LevelRange {
        final int min, max;
        final String color;
        LevelRange(int min, int max, String color) { this.min = min; this.max = max; this.color = color; }
    }

    public static class LevelStarExpansion extends PlaceholderExpansion {
        private final LevelStar plugin;
        public LevelStarExpansion(LevelStar plugin) { this.plugin = plugin; }
        @Override public boolean persist() { return true; }
        @Override public String getIdentifier() { return "levelstar"; }
        @Override public String getAuthor() { return "Hy4dro"; }
        @Override public String getVersion() { return "1.0"; }
        @Override public String onPlaceholderRequest(Player p, String params) {
            if (p == null) return "";
            if (params.equalsIgnoreCase("level")) return plugin.formatLevel(p.getUniqueId());
            if (params.equalsIgnoreCase("level_raw")) {
                PlayerData d = plugin.cache.get(p.getUniqueId());
                return String.valueOf(d != null ? d.level : 0);
            }
            return null;
        }
    }
}