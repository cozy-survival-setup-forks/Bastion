package dev.bastion.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The typed title: a bold coloured word typed out letter by letter with a click for each, then a subtitle typed the
 * same way between two stars, and a chime at the end. One repeating task drives the whole thing for everyone who is
 * watching, so it costs the same for one player or thirty.
 */
public final class Titles {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int TYPING_TICKS = 2;
    private static final int PAUSE_STEPS = 4;

    private final TaskBag tasks;

    public Titles(TaskBag tasks) {
        this.tasks = tasks;
    }

    /** {@code color} is a hex colour such as #FF5555. The title is shown in capitals. */
    public void type(Collection<Player> to, String title, String color, String subtitle) {
        type(to, title, color, subtitle, "★");
    }

    /** The symbol on both sides of the subtitle: a star for wins and finds, a dagger for the fights. */
    public void type(Collection<Player> to, String title, String color, String subtitle, String symbol) {
        List<Player> players = new ArrayList<>(to);
        if (players.isEmpty()) return;
        String head = Text.plain(title).toUpperCase(java.util.Locale.ROOT);
        String sub = subtitle == null ? "" : Text.plain(subtitle);
        if (head.isEmpty()) return;

        // it stays until everything is typed, and a couple of seconds after
        long typingMs = (long) (head.length() + PAUSE_STEPS + sub.length()) * TYPING_TICKS * 50L;
        Title.Times times = Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(typingMs + 2500), Duration.ofMillis(600));
        Title first = Title.title(colored(color, head.substring(0, 1)), Component.empty(), times);
        for (Player p : players) if (p.isOnline()) p.showTitle(first);
        click(players, 0, 0.3f, 1.6f);

        int[] step = {0};
        tasks.every(TYPING_TICKS, () -> {
            int n = ++step[0];
            if (n < head.length()) {
                Component part = colored(color, head.substring(0, n + 1));
                for (Player p : players) if (p.isOnline()) p.sendTitlePart(TitlePart.TITLE, part);
                click(players, n, 0.3f, 1.6f);
                return true;
            }
            int typed = n - head.length() - PAUSE_STEPS;
            if (sub.isEmpty()) {
                chime(players);
                return false;
            }
            if (typed < 0) return true;
            if (typed < sub.length()) {
                Component part = MINI.deserialize("<" + color + ">" + symbol + "</" + color + "> <white>" + escape(sub.substring(0, typed + 1)) + "</white> <" + color + ">" + symbol + "</" + color + ">");
                for (Player p : players) if (p.isOnline()) p.sendTitlePart(TitlePart.SUBTITLE, part);
                click(players, typed, 0.25f, 1.8f);
                return true;
            }
            chime(players);
            return false;
        });
    }

    private static Component colored(String color, String text) {
        return MINI.deserialize("<" + color + "><bold>" + escape(text) + "</bold></" + color + ">");
    }

    /** Text is shown as it is, so nothing in it can be read as a tag. */
    private static String escape(String text) {
        return MINI.escapeTags(text);
    }

    private static void click(List<Player> players, int index, float volume, float basePitch) {
        for (Player p : players) {
            if (p.isOnline()) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, volume, basePitch + (index % 3) * 0.1f);
        }
    }

    private static void chime(List<Player> players) {
        for (Player p : players) {
            if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 1.0f, 1.2f);
        }
    }
}
