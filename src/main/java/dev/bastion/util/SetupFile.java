package dev.bastion.util;

import dev.bastion.safe.Health;
import dev.bastion.safe.SafeIo;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * setup.yml holds the whole build: regions, doors and points. Each part saves only its own section and leaves the
 * others as they are. The file is the owner's work, so it is read with a fallback to its .bak, and when it cannot be
 * read at all it is left exactly as it is and nothing is saved over it until it is fixed.
 */
public final class SetupFile {

    private static final Set<Path> BLOCKED = ConcurrentHashMap.newKeySet();

    private SetupFile() {
    }

    /** The file as it is now. Empty when it is missing, or when it cannot be read (then nothing will be saved over it). */
    public static YamlConfiguration read(File file, Logger log) {
        if (!file.exists()) return new YamlConfiguration();
        SafeIo.Loaded loaded = SafeIo.loadYaml(file.toPath(), SafeIo.Policy.PROTECTED, log);
        if (loaded.state == SafeIo.State.BLOCKED) {
            BLOCKED.add(file.toPath().toAbsolutePath());
            log.severe(file.getName() + " could not be read, so no region, door or point is loaded and nothing will be saved over it. "
                    + "Fix the file (or put back " + file.getName() + ".bak), then /dungeon reload.");
            return new YamlConfiguration();
        }
        BLOCKED.remove(file.toPath().toAbsolutePath());
        SafeIo.refreshBackup(file.toPath());
        return loaded.yaml;
    }

    public static boolean blocked(File file) {
        return BLOCKED.contains(file.toPath().toAbsolutePath());
    }

    /** The file as it is now, with one section emptied, ready for that section to be written again. Null when it is blocked. */
    public static YamlConfiguration open(File file, String section, Logger log) {
        if (blocked(file)) {
            log.severe(file.getName() + " could not be read earlier, so the change was not saved. Fix the file and /dungeon reload.");
            return null;
        }
        YamlConfiguration yaml = read(file, log);
        if (blocked(file)) return null;
        yaml.set(section, null);
        return yaml;
    }

    /** Writes through a temporary file, with the previous version kept as setup.yml.bak. */
    public static void save(File file, YamlConfiguration yaml, Logger log) throws IOException {
        try {
            SafeIo.writeYaml(file.toPath(), yaml.saveToString());
        } catch (IOException e) {
            Health.failure(file.getName() + " could not be saved: " + e.getMessage());
            throw e;
        }
    }
}
