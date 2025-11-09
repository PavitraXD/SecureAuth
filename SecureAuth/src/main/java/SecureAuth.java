import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import java.io.File;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SecureAuth extends JavaPlugin implements Listener {
    
    private Map<UUID, Boolean> loggedIn = new HashMap<>();
    private Map<UUID, Integer> attempts = new HashMap<>();
    private Map<UUID, Long> loginTime = new HashMap<>();
    private FileConfiguration data;
    private File dataFile;
    private int sessionTimeout = 300; // 5 minutes
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (Exception e) {}
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        sessionTimeout = getConfig().getInt("session-timeout", 300);
        
        // Start session cleanup task
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupSessions();
            }
        }.runTaskTimer(this, 1200L, 1200L); // Every minute
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        
        switch (cmd.getName()) {
            case "register" -> {
                if (args.length != 1) {
                    p.sendMessage("§eUsage: /register <password>");
                    return true;
                }
                if (data.contains(p.getUniqueId().toString())) {
                    p.sendMessage("§cAlready registered!");
                    return true;
                }
                data.set(p.getUniqueId() + ".password", hash(args[0]));
                data.set(p.getUniqueId() + ".premium", false);
                saveData();
                loggedIn.put(p.getUniqueId(), true);
                loginTime.put(p.getUniqueId(), System.currentTimeMillis());
                updateTag(p);
                p.sendMessage("§aAccount created and logged in!");
                showTitle(p, "§a§lREGISTERED", "§fWelcome to the server!");
                return true;
            }
            case "login" -> {
                if (args.length != 1) {
                    p.sendMessage("§eUsage: /login <password>");
                    return true;
                }
                String stored = data.getString(p.getUniqueId() + ".password");
                if (stored == null) {
                    p.sendMessage("§eUse /register <password>");
                    return true;
                }
                if (stored.equals(hash(args[0]))) {
                    loggedIn.put(p.getUniqueId(), true);
                    loginTime.put(p.getUniqueId(), System.currentTimeMillis());
                    attempts.remove(p.getUniqueId());
                    updateTag(p);
                    p.sendMessage("§aSuccessfully logged in!");
                    showTitle(p, "§a§lLOGGED IN", "§fWelcome back!");
                } else {
                    int att = attempts.getOrDefault(p.getUniqueId(), 0) + 1;
                    attempts.put(p.getUniqueId(), att);
                    if (att >= getConfig().getInt("max-attempts")) {
                        p.kick(Component.text("§cToo many failed attempts!"));
                    } else {
                        p.sendMessage("§cWrong password! " + (getConfig().getInt("max-attempts") - att) + " attempts left");
                    }
                }
                return true;
            }
            case "changepassword" -> {
                if (args.length != 2) {
                    p.sendMessage("§cUsage: /changepassword <old> <new>");
                    return true;
                }
                if (!loggedIn.getOrDefault(p.getUniqueId(), false)) {
                    p.sendMessage("§cLogin first!");
                    return true;
                }
                String stored = data.getString(p.getUniqueId() + ".password");
                if (stored != null && stored.equals(hash(args[0]))) {
                    data.set(p.getUniqueId() + ".password", hash(args[1]));
                    saveData();
                    p.sendMessage("§aPassword changed!");
                } else {
                    p.sendMessage("§cWrong old password!");
                }
                return true;
            }
        }
        return false;
    }
    
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (getServer().getOnlineMode()) {
            data.set(p.getUniqueId() + ".premium", true);
            loggedIn.put(p.getUniqueId(), true);
            loginTime.put(p.getUniqueId(), System.currentTimeMillis());
            saveData();
            updateTag(p);
            p.sendMessage("§aPremium account automatically authenticated!");
        } else {
            loggedIn.put(p.getUniqueId(), false);
            updateTag(p);
            if (data.contains(p.getUniqueId().toString())) {
                p.sendMessage("§e§lPlease login: §f/login <password>");
                showTitle(p, "§e§lLOGIN REQUIRED", "§f/login <password>");
                startLoginReminder(p);
            } else {
                p.sendMessage("§e§lPlease register: §f/register <password>");
                showTitle(p, "§e§lREGISTRATION REQUIRED", "§f/register <password>");
                startLoginReminder(p);
            }
        }
    }
    
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isAuthenticated(e.getPlayer())) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar(Component.text("§cPlease login to move!"));
            }
        }
    }
    
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (!isAuthenticated(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cPlease login before chatting!");
        }
    }
    
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isAuthenticated(e.getPlayer())) {
            String cmd = e.getMessage().toLowerCase();
            if (!cmd.startsWith("/login") && !cmd.startsWith("/register")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage("§cPlease login first!");
            }
        }
    }
    
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        loggedIn.remove(uuid);
        attempts.remove(uuid);
        loginTime.remove(uuid);
    }
    
    private boolean isAuthenticated(Player p) {
        UUID uuid = p.getUniqueId();
        if (!loggedIn.getOrDefault(uuid, false)) return false;
        
        // Check session timeout
        Long lastLogin = loginTime.get(uuid);
        if (lastLogin != null && sessionTimeout > 0) {
            long elapsed = (System.currentTimeMillis() - lastLogin) / 1000;
            if (elapsed > sessionTimeout) {
                loggedIn.remove(uuid);
                loginTime.remove(uuid);
                p.sendMessage("§cSession expired. Please login again.");
                return false;
            }
        }
        return true;
    }
    
    private void cleanupSessions() {
        if (sessionTimeout <= 0) return;
        
        long currentTime = System.currentTimeMillis();
        loginTime.entrySet().removeIf(entry -> {
            long elapsed = (currentTime - entry.getValue()) / 1000;
            if (elapsed > sessionTimeout) {
                UUID uuid = entry.getKey();
                loggedIn.remove(uuid);
                Player p = getServer().getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.sendMessage("§cYour session has expired. Please login again.");
                }
                return true;
            }
            return false;
        });
    }
    
    private void showTitle(Player p, String title, String subtitle) {
        Title titleComponent = Title.title(
            Component.text(title),
            Component.text(subtitle),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
        );
        p.showTitle(titleComponent);
    }
    
    private void startLoginReminder(Player p) {
        new BukkitRunnable() {
            int count = 0;
            @Override
            public void run() {
                if (!p.isOnline() || isAuthenticated(p) || count >= 10) {
                    cancel();
                    return;
                }
                
                if (data.contains(p.getUniqueId().toString())) {
                    p.sendActionBar(Component.text("§e§lLogin required: /login <password>"));
                } else {
                    p.sendActionBar(Component.text("§e§lRegister required: /register <password>"));
                }
                count++;
            }
        }.runTaskTimer(this, 60L, 60L); // Every 3 seconds
    }
    
    private void updateTag(Player p) {
        boolean premium = data.getBoolean(p.getUniqueId() + ".premium", false);
        String tag = premium ? getConfig().getString("premium-prefix") : getConfig().getString("cracked-prefix");
        p.displayName(Component.text(tag.replace("&", "§") + p.getName()));
        p.playerListName(Component.text(tag.replace("&", "§") + p.getName()));
    }
    
    private String hash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return password;
        }
    }
    
    private void saveData() {
        try { data.save(dataFile); } catch (Exception e) {}
    }
}