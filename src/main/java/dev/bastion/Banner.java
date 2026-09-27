package dev.bastion;

import dev.bastion.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** The console startup banner. Same emblem and gradient as every other Groovified/Blockie Studios plugin. */
final class Banner {

    private Banner() {
    }

    static void print(JavaPlugin plugin, String thankYou) {
        var console = Bukkit.getConsoleSender();
        console.sendMessage(Text.component("<#9D4EDD>   ▲"));
        console.sendMessage(Text.component("<#4E81DA>  ▐█▌"));
        console.sendMessage(Text.component("<#00B4D8>   ▼"));
        console.sendMessage(Text.component("<white><bold>" + plugin.getName() + "</bold> <gray>v" + plugin.getPluginMeta().getVersion()));
        console.sendMessage(Text.component("<gray>by Groovified — Blockie Studios"));
        console.sendMessage(Text.component("<gray>Running on <white>" + Bukkit.getName() + " " + Bukkit.getMinecraftVersion()));
        console.sendMessage(Text.component(""));
        console.sendMessage(Text.component("<gray>Support: <aqua>discord.gg/blockie"));
        console.sendMessage(Text.component("<italic><gray>" + thankYou));
    }
}
