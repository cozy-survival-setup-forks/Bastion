package dev.bastion.dungeon;

import dev.bastion.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** messages.yml. Texts take &amp; codes or MiniMessage, and %name% placeholders passed as name, value pairs. */
public final class Messages {

    private final JavaPlugin plugin;
    private YamlConfiguration yaml = new YamlConfiguration();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
        var bundled = plugin.getResource("messages.yml");
        if (bundled != null) yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
    }

    public String raw(String key, String... pairs) {
        String text = yaml.getString(key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) text = text.replace("%" + pairs[i] + "%", pairs[i + 1]);
        return text;
    }

    /** A block of lines, for the big server announcements. */
    public List<String> lines(String key, String... pairs) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : yaml.getStringList(key)) {
            for (int i = 0; i + 1 < pairs.length; i += 2) line = line.replace("%" + pairs[i] + "%", pairs[i + 1]);
            out.add(line);
        }
        return out;
    }

    public Component text(String key, String... pairs) {
        return Text.component(raw(key, pairs));
    }

    public Component prefixed(String key, String... pairs) {
        return Text.component(yaml.getString("prefix", "") + raw(key, pairs));
    }

    public void send(CommandSender to, String key, String... pairs) {
        to.sendMessage(prefixed(key, pairs));
    }
}
