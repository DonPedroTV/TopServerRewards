package pl.topserver.rewards.updater;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import pl.topserver.rewards.TopServerRewards;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker implements Listener {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/DonPedroTV/TopServerRewards2/releases/latest";

    private final TopServerRewards plugin;
    private String latestVersion = null;
    private String downloadUrl = null;
    private boolean updateAvailable = false;
    private BukkitTask checkTask;

    public UpdateChecker(TopServerRewards plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("update-checker.enabled", true)) {
            plugin.getLogger().info("Sprawdzanie aktualizacji jest wylaczone w config.yml.");
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        int intervalHours = plugin.getConfig().getInt("update-checker.check-interval", 6);
        long intervalTicks = intervalHours * 60L * 60L * 20L;

        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkForUpdate, 60L, intervalTicks);
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    private void checkForUpdate() {
        try {
            URL url = new URL(GITHUB_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "TopServerRewards/" + plugin.getDescription().getVersion());
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

            if (conn.getResponseCode() != 200) {
                plugin.getLogger()
                        .warning("Nie udalo sie sprawdzic aktualizacji (HTTP " + conn.getResponseCode() + ")");
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();

            JSONObject json = (JSONObject) new JSONParser().parse(response.toString());
            String tagName = (String) json.get("tag_name");

            if (tagName == null)
                return;

            String remoteVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            String currentVersion = plugin.getDescription().getVersion();

            if (isNewerVersion(remoteVersion, currentVersion)) {
                latestVersion = remoteVersion;
                downloadUrl = "https://topserver.pl/tutorial.php";
                updateAvailable = true;

                plugin.getLogger().info("========================================");
                plugin.getLogger().info("Dostepna nowa wersja TopServerRewards!");
                plugin.getLogger().info("Obecna: " + currentVersion + " -> Nowa: " + latestVersion);
                plugin.getLogger().info("Pobierz: https://topserver.pl/tutorial.php");
                plugin.getLogger().info("========================================");
            } else {
                updateAvailable = false;
                plugin.getLogger().info("TopServerRewards jest aktualny (v" + currentVersion + ").");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Blad podczas sprawdzania aktualizacji: " + e.getMessage());
        }
    }

    private boolean isNewerVersion(String remote, String current) {
        try {
            String[] remoteParts = remote.split("\\.");
            String[] currentParts = current.split("\\.");

            int maxLength = Math.max(remoteParts.length, currentParts.length);
            for (int i = 0; i < maxLength; i++) {
                int remotePart = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;

                if (remotePart > currentPart)
                    return true;
                if (remotePart < currentPart)
                    return false;
            }
        } catch (NumberFormatException e) {
            return !remote.equals(current);
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable || latestVersion == null)
            return;

        Player player = event.getPlayer();
        if (!player.isOp())
            return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline())
                return;

            String currentVersion = plugin.getDescription().getVersion();

            String defaultMsg = "&e[TopServerRewards] &aDostepna nowa wersja: &f{new_version} &7(obecna: {current_version})";
            String msgUpdate = plugin.getConfig().getString("messages.update-available", "");
            if (msgUpdate == null || msgUpdate.isEmpty() || msgUpdate.equalsIgnoreCase("false")) {
                msgUpdate = defaultMsg;
            }

            msgUpdate = ChatColor.translateAlternateColorCodes('&',
                    msgUpdate.replace("{new_version}", latestVersion)
                            .replace("{current_version}", currentVersion));

            TextComponent message = new TextComponent(msgUpdate + " ");

            TextComponent clickText = new TextComponent(ChatColor.translateAlternateColorCodes('&',
                    "&a&l[LINK]"));
            clickText.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, downloadUrl));
            clickText.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(ChatColor.translateAlternateColorCodes('&',
                            "&7Kliknij, aby przejsc na strone pobierania"))));

            message.addExtra(clickText);

            player.sendMessage("");
            player.spigot().sendMessage(message);
            player.sendMessage("");
        }, 40L);
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }
}
