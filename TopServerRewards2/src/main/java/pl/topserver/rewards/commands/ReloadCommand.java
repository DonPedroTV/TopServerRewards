package pl.topserver.rewards.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import pl.topserver.rewards.TopServerRewards;

public class ReloadCommand {

    private final TopServerRewards plugin;

    public ReloadCommand(TopServerRewards plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender) {
        if (!sender.hasPermission("topserver.admin")) {
            String msg = plugin.getConfig().getString("messages.reload-no-permission",
                    "&cNie masz uprawnień do przeładowania pluginu!");
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return;
        }

        plugin.reloadPluginConfig();

        String msg = plugin.getConfig().getString("messages.reload-success",
                "&aPomyślnie przeładowano konfigurację TopServerRewards!");
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }
}
