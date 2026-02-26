package pl.topserver.rewards;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

import pl.topserver.rewards.commands.ReloadCommand;
import pl.topserver.rewards.commands.TSTabCompleter;
import pl.topserver.rewards.updater.ConfigUpdater;
import pl.topserver.rewards.updater.UpdateChecker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class TopServerRewards extends JavaPlugin {

    private String apiUrl;
    private String serverIp;
    private Map<UUID, Long> cooldowns = new HashMap<>();
    private ReloadCommand reloadCommand;
    private UpdateChecker updateChecker;
    private static final long COOLDOWN_TIME = 5000;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        new ConfigUpdater(this).update();

        loadConfigValues();

        // bStats metrics
        int pluginId = 29789;
        Metrics metrics = new Metrics(this, pluginId);
        metrics.addCustomChart(new SimplePie("plugin_version",
                () -> getDescription().getVersion()));

        reloadCommand = new ReloadCommand(this);

        PluginCommand tsCmd = getCommand("ts");
        if (tsCmd != null) {
            tsCmd.setTabCompleter(new TSTabCompleter(this));
        }

        // Sprawdzanie aktualizacji
        updateChecker = new UpdateChecker(this);
        updateChecker.start();

        getLogger().info("TopServerRewards zostal wlaczony!");
        getLogger().info("API URL: " + apiUrl);
        getLogger().info("Server IP: " + (serverIp.isEmpty() ? "NIE USTAWIONY!" : serverIp));

        if (serverIp.isEmpty()) {
            getLogger().warning("========================================");
            getLogger().warning("UWAGA: Nie ustawiono 'server-ip' w config.yml!");
            getLogger().warning("Plugin nie bedzie dzialal dopoki nie ustawisz IP serwera!");
            getLogger().warning("========================================");
        }

        if (!getConfig().getBoolean("rewards.enabled", true)) {
            getLogger().warning("Nagrody sa WYLACZONE w config.yml!");
        }
    }

    private void loadConfigValues() {
        apiUrl = getConfig().getString("api-url", "https://topserver.pl/api_rewards.php");
        serverIp = getConfig().getString("server-ip", "");
    }

    /**
     * Przeładowuje konfigurację pluginu.
     * Wywoływane przez ReloadCommand.
     */
    public void reloadPluginConfig() {
        reloadConfig();
        loadConfigValues();
        getLogger().info("Konfiguracja zostala przeladowana.");
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stop();
        }
        getLogger().info("TopServerRewards zostal wylaczony!");
    }

    private String msg(String path) {
        String raw = getConfig().getString(path, "");
        if (raw == null || raw.isEmpty() || raw.equalsIgnoreCase("false"))
            return null;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private void send(Player player, String path) {
        String m = msg(path);
        if (m != null)
            player.sendMessage(m);
    }

    private void send(Player player, String path, String... replacements) {
        String m = msg(path);
        if (m == null)
            return;
        for (int i = 0; i + 1 < replacements.length; i += 2)
            m = m.replace(replacements[i], replacements[i + 1]);
        player.sendMessage(m);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ts"))
            return false;

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadCommand.execute(sender);
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Ta komenda moze byc uzyta tylko przez gracza!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String claimSub = getConfig().getString("commands.claim-sub", "odbierz");
        if (args[0].equalsIgnoreCase(claimSub)) {
            claimReward(player);
        } else {
            showHelp(player);
        }
        return true;
    }

    private void showHelp(Player player) {
        String mainCmd = getConfig().getString("commands.main", "ts");
        String claimSub = getConfig().getString("commands.claim-sub", "odbierz");

        send(player, "messages.help.line-top");
        send(player, "messages.help.title");
        send(player, "messages.help.line-separator");
        send(player, "messages.help.usage", "{command}", "/" + mainCmd + " " + claimSub);
        send(player, "messages.help.vote-url");
        send(player, "messages.help.line-bottom");
    }

    private void claimReward(Player player) {
        if (!player.hasPermission("topserver.claim")) {
            send(player, "messages.no-permission");
            return;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        if (cooldowns.containsKey(playerId)) {
            long lastUse = cooldowns.get(playerId);
            if (currentTime - lastUse < COOLDOWN_TIME) {
                long timeLeft = (COOLDOWN_TIME - (currentTime - lastUse)) / 1000;
                send(player, "messages.cooldown", "{seconds}", String.valueOf(timeLeft));
                return;
            }
        }

        cooldowns.put(playerId, currentTime);

        if (serverIp.isEmpty()) {
            send(player, "messages.config-error");
            send(player, "messages.config-error-hint");
            return;
        }

        send(player, "messages.checking");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String playerName = player.getName();

                JSONObject checkResponse = makeApiRequest("check", playerName, null);
                if (checkResponse == null) {
                    send(player, "messages.api-error");
                    send(player, "messages.api-error-hint");
                    return;
                }

                boolean success = (boolean) checkResponse.get("success");
                if (!success) {
                    String error = (String) checkResponse.getOrDefault("error", "Nieznany blad");
                    send(player, "messages.api-fail", "{error}", error);
                    return;
                }

                boolean hasReward = (boolean) checkResponse.getOrDefault("has_reward", false);
                if (!hasReward) {
                    String apiMessage = (String) checkResponse.getOrDefault("message", "");
                    String noRewardMsg = msg("messages.no-reward");
                    if (noRewardMsg != null)
                        player.sendMessage(noRewardMsg.replace("{api_message}", apiMessage));
                    send(player, "messages.no-reward-hint");
                    return;
                }

                long voteId = ((Number) checkResponse.get("vote_id")).longValue();
                String serverName = (String) checkResponse.get("server_name");

                JSONObject claimResponse = makeApiRequest("claim", playerName, voteId);
                if (claimResponse == null) {
                    send(player, "messages.claim-error");
                    return;
                }

                boolean claimSuccess = (boolean) claimResponse.get("success");

                if (claimSuccess) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        giveRewards(player);

                        send(player, "messages.claimed.line-top");
                        send(player, "messages.claimed.success");
                        send(player, "messages.claimed.server", "{server}", serverName);
                        send(player, "messages.claimed.thanks");
                        send(player, "messages.claimed.line-bottom");

                        if (getConfig().getBoolean("rewards.broadcast", true)) {
                            String broadcastMsg = msg("messages.broadcast");
                            if (broadcastMsg != null)
                                Bukkit.broadcastMessage(broadcastMsg.replace("{player}", player.getName()));
                        }
                    });
                } else {
                    String error = (String) claimResponse.getOrDefault("error", "Nieznany blad");
                    send(player, "messages.claim-fail", "{error}", error);
                }

            } catch (Exception e) {
                send(player, "messages.unexpected-error");
                getLogger().severe("Blad podczas odbierania nagrody dla " + player.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void giveRewards(Player player) {
        if (!getConfig().getBoolean("rewards.enabled", true)) {
            send(player, "messages.rewards-disabled");
            return;
        }

        if (getConfig().getBoolean("rewards.items.enabled", true)) {
            for (String itemString : getConfig().getStringList("rewards.items.list")) {
                try {
                    String[] parts = itemString.split(":");
                    Material material = Material.valueOf(parts[0]);
                    int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    player.getInventory().addItem(new ItemStack(material, amount));
                } catch (Exception e) {
                    getLogger().warning("Nieprawidlowy item: " + itemString);
                }
            }
        }

        if (getConfig().getBoolean("rewards.commands.enabled", false)) {
            for (String cmd : getConfig().getStringList("rewards.commands.list"))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", player.getName()));
        }

        if (getConfig().getBoolean("rewards.money.enabled", false)) {
            double amount = getConfig().getDouble("rewards.money.amount", 100.0);
            send(player, "messages.money-received", "{amount}", String.valueOf(amount));
        }
    }

    private JSONObject makeApiRequest(String action, String playerName, Long voteId) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(apiUrl);
        urlBuilder.append("?action=").append(action);
        urlBuilder.append("&nick=").append(URLEncoder.encode(playerName, StandardCharsets.UTF_8.toString()));
        urlBuilder.append("&server_ip=").append(URLEncoder.encode(serverIp, StandardCharsets.UTF_8.toString()));
        if (voteId != null)
            urlBuilder.append("&vote_id=").append(voteId);

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "TopServerRewards/2.0");

        if (conn.getResponseCode() == 200) {
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null)
                response.append(line);
            in.close();
            return (JSONObject) new JSONParser().parse(response.toString());
        }

        return null;
    }
}