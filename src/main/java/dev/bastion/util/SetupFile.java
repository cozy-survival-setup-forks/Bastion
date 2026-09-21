package dev.bastion.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * setup.yml holds the whole build: regions, doors and points. Each part saves only its own section and leaves the
 * others as they are.
 */
public final class SetupFile {

    private SetupFile() {
    }

    /** The file as it is now, with one section emptied, ready for that section to be written again. */
    public static YamlConfiguration open(File file, String section) {
        YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        yaml.set(section, null);
        return yaml;
    }
}
