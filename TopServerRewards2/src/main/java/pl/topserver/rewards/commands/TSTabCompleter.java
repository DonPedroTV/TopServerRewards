package pl.topserver.rewards.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import pl.topserver.rewards.TopServerRewards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TSTabCompleter implements TabCompleter {

    private final TopServerRewards plugin;

    public TSTabCompleter(TopServerRewards plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String claimSub = plugin.getConfig().getString("commands.claim-sub", "odbierz");

            completions.add(claimSub);

            if (sender.hasPermission("topserver.admin")) {
                completions.add("reload");
            }

            String input = args[0].toLowerCase();
            List<String> filtered = new ArrayList<>();
            for (String c : completions) {
                if (c.toLowerCase().startsWith(input)) {
                    filtered.add(c);
                }
            }
            return filtered;
        }

        return Collections.emptyList();
    }
}
