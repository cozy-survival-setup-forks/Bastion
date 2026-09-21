package dev.bastion.command;

import dev.bastion.BastionPlugin;
import dev.bastion.dungeon.Dungeon;
import dev.bastion.region.CuboidRegion;
import dev.bastion.region.PolygonRegion;
import dev.bastion.region.Region;
import dev.bastion.region.RegionType;
import dev.bastion.region.Wand;
import dev.bastion.util.Text;
import dev.bastion.world.SchemFile;
import dev.bastion.world.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /dungeon and everything under it. */
public final class DungeonCommand implements TabExecutor {

    private static final List<String> PLAYER = List.of("menu", "status", "contribute", "enter", "leave");
    private static final List<String> ADMIN = List.of("reload", "forcestart", "forceend", "forcereset", "forcefund", "debug",
            "wand", "region", "door", "point", "chest", "paste");

    private final BastionPlugin plugin;
    private final Dungeon dungeon;

    public DungeonCommand(BastionPlugin plugin, Dungeon dungeon) {
        this.plugin = plugin;
        this.dungeon = dungeon;
    }

    private void say(CommandSender to, String key, String... pairs) {
        dungeon.messages.send(to, key, pairs);
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("dungeon.admin")) return true;
        say(s, "no-permission");
        return false;
    }

    private boolean builder(CommandSender s) {
        if (s.hasPermission("dungeon.wand") || s.hasPermission("dungeon.admin")) return true;
        say(s, "no-permission");
        return false;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        say(s, "players-only");
        return null;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (!sender.hasPermission("dungeon.use") && !sender.hasPermission("dungeon.admin")) {
            say(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "menu" -> menu(sender, rest);
            case "status" -> status(sender);
            case "contribute" -> contribute(sender, rest);
            case "enter" -> {
                Player p = player(sender);
                if (p != null) {
                    String error = dungeon.enter(p);
                    if (error != null) say(sender, error);
                }
            }
            case "leave" -> {
                Player p = player(sender);
                if (p != null) {
                    if (dungeon.run(p.getUniqueId()) == null && dungeon.store.returnOf(p.getUniqueId()) == null) say(sender, "not-in");
                    else dungeon.leave(p);
                }
            }
            case "reload" -> {
                if (admin(sender)) {
                    plugin.reloadAll();
                    say(sender, "reloaded");
                }
            }
            case "forcestart" -> {
                if (admin(sender)) {
                    boolean now = rest.length > 0 && rest[0].equalsIgnoreCase("now");
                    say(sender, dungeon.forceStart(now) ? "force-started" : "force-not-now", "state", dungeon.state().pretty());
                }
            }
            case "forceend" -> {
                if (admin(sender)) {
                    dungeon.forceEnd();
                    say(sender, "force-ended");
                }
            }
            case "forcereset" -> {
                if (admin(sender)) {
                    dungeon.beginReset();
                    say(sender, "force-reset");
                }
            }
            case "forcefund" -> {
                if (admin(sender)) {
                    double amount = rest.length > 0 ? number(rest[0], dungeon.settings.goal) : dungeon.settings.goal;
                    dungeon.store.setCurrent(Math.min(dungeon.settings.goal, dungeon.store.current() + amount));
                    if (dungeon.store.current() >= dungeon.settings.goal) dungeon.forceStart(false);
                    say(sender, "funded", "amount", Text.number(dungeon.store.current()));
                }
            }
            case "debug" -> {
                if (admin(sender)) debug(sender);
            }
            case "wand" -> {
                if (builder(sender)) wand(sender, rest);
            }
            case "region" -> {
                if (builder(sender)) region(sender, rest);
            }
            case "door" -> {
                if (builder(sender)) door(sender, rest);
            }
            case "point" -> {
                if (builder(sender)) point(sender, rest, false);
            }
            case "chest" -> {
                if (builder(sender)) point(sender, rest, true);
            }
            case "paste" -> {
                if (builder(sender)) paste(sender, rest);
            }
            default -> say(sender, "usage");
        }
        return true;
    }

    private static double number(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- players

    private void menu(CommandSender sender, String[] rest) {
        if (rest.length > 0 && rest[0].equalsIgnoreCase("reload")) {
            if (admin(sender)) {
                plugin.menus().load();
                say(sender, "reloaded");
            }
            return;
        }
        Player p = player(sender);
        if (p != null && !plugin.menus().open(p, "dungeon_main")) say(sender, "no-menu");
    }

    private void status(CommandSender sender) {
        say(sender, "status",
                "state", dungeon.state().pretty(),
                "goal", dungeon.placeholder(null, "goal_current") + "/" + dungeon.placeholder(null, "goal_total"),
                "players", dungeon.placeholder(null, "players_inside"),
                "artifacts", dungeon.placeholder(null, "artifacts_found") + "/" + dungeon.placeholder(null, "artifacts_total"),
                "time", dungeon.state().inRun() ? Dungeon.clock(dungeon.runLeft()) : Dungeon.clock(dungeon.joinLeft()));
    }

    private void contribute(CommandSender sender, String[] rest) {
        Player p = player(sender);
        if (p == null) return;
        if (rest.length == 0) {
            say(sender, "usage");
            return;
        }
        double amount = number(rest[0], -1);
        if (amount <= 0) {
            say(sender, "bad-amount");
            return;
        }
        String error = dungeon.contribute(p, amount);
        if (error != null) say(sender, error);
    }

    private void debug(CommandSender sender) {
        say(sender, "debug",
                "state", dungeon.state().name(),
                "inside", String.valueOf(dungeon.insideCount()),
                "mobs", String.valueOf(dungeon.mobs.count()),
                "waves", dungeon.waves.running() ? dungeon.waves.wave() + "/" + dungeon.waves.waveCount() + ", " + dungeon.waves.alive() + " alive" : "idle",
                "bosses", String.valueOf(dungeon.bosses.fights().size()),
                "tasks", String.valueOf(dungeon.tasks.size()),
                "regions", String.valueOf(dungeon.regions.all().size()),
                "artifacts", dungeon.artifacts.found() + "/" + dungeon.artifacts.total(),
                "reset", dungeon.reset.busy() ? "busy" : "idle");
    }

    // ---------------------------------------------------------------- building tools

    private void wand(CommandSender sender, String[] rest) {
        Player p = player(sender);
        if (p == null) return;
        if (rest.length > 0) {
            boolean polygon = rest[0].equalsIgnoreCase("polygon");
            plugin.wand().polygonMode(p.getUniqueId(), polygon);
            say(sender, polygon ? "wand-polygon" : "wand-cuboid");
        }
        p.getInventory().addItem(plugin.wand().item());
        say(sender, "wand-given");
    }

    private void region(CommandSender sender, String[] a) {
        if (a.length == 0) {
            say(sender, "usage-region");
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "save" -> regionSave(sender, a);
            case "cuboid" -> regionCuboid(sender, a);
            case "polygon" -> regionPolygon(sender, a);
            case "list" -> {
                if (dungeon.regions.all().isEmpty()) say(sender, "regions-none");
                for (Region r : dungeon.regions.all()) {
                    say(sender, "region-line", "id", r.id(), "type", r.type().name(), "world", r.world(),
                            "shape", r instanceof PolygonRegion ? "polygon" : "cuboid");
                }
            }
            case "show" -> {
                Player p = player(sender);
                Region r = a.length > 1 ? dungeon.regions.get(a[1]) : null;
                if (p == null) return;
                if (r == null) {
                    say(sender, "region-unknown");
                    return;
                }
                int[] left = {10};
                Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                    if (!p.isOnline() || --left[0] < 0) {
                        task.cancel();
                        return;
                    }
                    plugin.wand().outline(p, r);
                }, 0L, 20L);
            }
            case "delete" -> {
                if (a.length > 1 && dungeon.regions.remove(a[1])) {
                    plugin.regionStore().save();
                    say(sender, "region-deleted", "id", a[1]);
                } else {
                    say(sender, "region-unknown");
                }
            }
            case "setclean" -> setClean(sender);
            default -> say(sender, "usage-region");
        }
    }

    private void regionSave(CommandSender sender, String[] a) {
        Player p = player(sender);
        if (p == null) return;
        RegionType type = a.length > 2 ? RegionType.parse(a[2]) : null;
        if (a.length < 3 || type == null) {
            say(sender, "usage-region-save", "types", Arrays.toString(RegionType.values()));
            return;
        }
        Wand.Selection s = plugin.wand().peek(p.getUniqueId());
        String id = a[1].toLowerCase(Locale.ROOT);
        String world = p.getWorld().getName();
        if (s == null) {
            say(sender, "no-selection");
            return;
        }
        if (!s.polygon) {
            if (s.a == null || s.b == null) {
                say(sender, "no-selection");
                return;
            }
            dungeon.regions.put(CuboidRegion.of(id, type, world, s.a.getBlockX(), s.a.getBlockY(), s.a.getBlockZ(),
                    s.b.getBlockX(), s.b.getBlockY(), s.b.getBlockZ()));
        } else {
            if (!s.closed || s.vertices.size() < 3) {
                say(sender, "no-selection");
                return;
            }
            double[] xs = new double[s.vertices.size()];
            double[] zs = new double[xs.length];
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int i = 0; i < xs.length; i++) {
                xs[i] = s.vertices.get(i).getBlockX();
                zs[i] = s.vertices.get(i).getBlockZ();
                low = Math.min(low, s.vertices.get(i).getBlockY());
                high = Math.max(high, s.vertices.get(i).getBlockY());
            }
            if (a.length > 4) {
                low = (int) number(a[3], low);
                high = (int) number(a[4], high);
            }
            dungeon.regions.put(new PolygonRegion(id, type, world, xs, zs, low, high));
        }
        plugin.regionStore().save();
        say(sender, "region-saved", "id", id, "type", type.name());
    }

    private void regionCuboid(CommandSender sender, String[] a) {
        // region cuboid <id> <type> <world> x1 y1 z1 x2 y2 z2
        RegionType type = a.length > 3 ? RegionType.parse(a[2]) : null;
        if (a.length < 10 || type == null) {
            say(sender, "usage-region-cuboid");
            return;
        }
        dungeon.regions.put(CuboidRegion.of(a[1].toLowerCase(Locale.ROOT), type, a[3], (int) number(a[4], 0), (int) number(a[5], 0),
                (int) number(a[6], 0), (int) number(a[7], 0), (int) number(a[8], 0), (int) number(a[9], 0)));
        plugin.regionStore().save();
        say(sender, "region-saved", "id", a[1], "type", type.name());
    }

    private void regionPolygon(CommandSender sender, String[] a) {
        // region polygon <id> <type> <world> <minY> <maxY> x,z x,z x,z ...
        RegionType type = a.length > 3 ? RegionType.parse(a[2]) : null;
        if (a.length < 9 || type == null) {
            say(sender, "usage-region-polygon");
            return;
        }
        int n = a.length - 6;
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            String[] xz = a[6 + i].split(",");
            xs[i] = number(xz[0], 0);
            zs[i] = number(xz.length > 1 ? xz[1] : "0", 0);
        }
        dungeon.regions.put(new PolygonRegion(a[1].toLowerCase(Locale.ROOT), type, a[3], xs, zs, (int) number(a[4], 0), (int) number(a[5], 0)));
        plugin.regionStore().save();
        say(sender, "region-saved", "id", a[1], "type", type.name());
    }

    private void setClean(CommandSender sender) {
        Region box = dungeon.regions.firstOfType(RegionType.DUNGEON);
        World world = box == null ? null : Bukkit.getWorld(box.world());
        if (box == null || world == null) {
            say(sender, "no-dungeon-region");
            return;
        }
        if (dungeon.reset.busy()) {
            say(sender, "reset-busy");
            return;
        }
        say(sender, "snapshot-started");
        dungeon.reset.capture(world, box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), snapshot -> {
            if (snapshot == null) {
                say(sender, "snapshot-failed");
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    SchemFile.write(new File(plugin.getDataFolder(), "dungeon_clean.schem").toPath(), snapshot);
                    say(sender, "snapshot-saved", "blocks", String.valueOf(snapshot.volume()));
                } catch (Exception e) {
                    say(sender, "snapshot-failed");
                    plugin.getLogger().warning("Could not write the snapshot: " + e);
                }
            });
        }, line -> {
            if (sender instanceof Player) sender.sendActionBar(Text.component("<gray>" + line));
        });
    }

    private void door(CommandSender sender, String[] a) {
        if (a.length == 0) {
            say(sender, "usage-door");
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "define" -> {
                Player p = player(sender);
                if (p == null || a.length < 2) return;
                Wand.Selection s = plugin.wand().peek(p.getUniqueId());
                if (s == null || s.polygon || s.a == null || s.b == null) {
                    say(sender, "no-selection");
                    return;
                }
                int blocks = dungeon.doors.define(a[1], p.getWorld(), s.a.getBlockX(), s.a.getBlockY(), s.a.getBlockZ(),
                        s.b.getBlockX(), s.b.getBlockY(), s.b.getBlockZ());
                say(sender, "door-defined", "id", a[1], "blocks", String.valueOf(blocks));
            }
            case "cuboid" -> {
                // door cuboid <id> <world> x1 y1 z1 x2 y2 z2
                World w = a.length > 8 ? Bukkit.getWorld(a[2]) : null;
                if (w == null) {
                    say(sender, "usage-door");
                    return;
                }
                int blocks = dungeon.doors.define(a[1], w, (int) number(a[3], 0), (int) number(a[4], 0), (int) number(a[5], 0),
                        (int) number(a[6], 0), (int) number(a[7], 0), (int) number(a[8], 0));
                say(sender, "door-defined", "id", a[1], "blocks", String.valueOf(blocks));
            }
            case "list" -> say(sender, "door-list", "doors", String.join(", ", dungeon.doors.ids()));
            case "delete" -> say(sender, a.length > 1 && dungeon.doors.delete(a[1]) ? "door-deleted" : "door-unknown");
            case "open" -> {
                if (a.length > 1 && dungeon.doors.exists(a[1])) dungeon.doors.open(a[1]);
                else say(sender, "door-unknown");
            }
            case "close" -> {
                if (a.length > 1 && dungeon.doors.exists(a[1])) dungeon.doors.close(a[1]);
                else say(sender, "door-unknown");
            }
            default -> say(sender, "usage-door");
        }
    }

    /** /dungeon point ... and /dungeon chest ..., which is the point group "chests" and the block you look at. */
    private void point(CommandSender sender, String[] a, boolean chest) {
        if (a.length == 0) {
            say(sender, "usage-point");
            return;
        }
        String action = a[0].toLowerCase(Locale.ROOT);
        Player p = action.equals("add") ? player(sender) : null;
        if (action.equals("add") && p == null) return;
        String group = chest ? "chests" : a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : null;
        if (group == null && !action.equals("list")) {
            say(sender, "usage-point");
            return;
        }
        int numberAt = chest ? 1 : 2;
        switch (action) {
            case "add" -> {
                Location at = p.getLocation();
                if (chest) {
                    Block target = p.getTargetBlockExact(6);
                    if (target == null) {
                        say(sender, "look-at-chest");
                        return;
                    }
                    at = target.getLocation().add(0.5, 0, 0.5);
                }
                dungeon.points.add(group, at);
                say(sender, "point-added", "group", group, "count", String.valueOf(dungeon.points.get(group).size()));
            }
            case "list" -> {
                if (group == null) {
                    dungeon.points.all().forEach((g, spots) -> say(sender, "point-group", "group", g, "count", String.valueOf(spots.size())));
                } else {
                    int i = 1;
                    for (var s : dungeon.points.get(group)) {
                        say(sender, "point-line", "number", String.valueOf(i++), "spot", s.x() + " " + s.y() + " " + s.z());
                    }
                }
            }
            case "remove" -> say(sender, a.length > numberAt && dungeon.points.remove(group, (int) number(a[numberAt], -1)) ? "point-removed" : "point-unknown");
            case "clear" -> say(sender, dungeon.points.clear(group) ? "point-cleared" : "point-unknown");
            default -> say(sender, "usage-point");
        }
    }

    private void paste(CommandSender sender, String[] a) {
        // paste <file> [world x y z]
        if (a.length == 0) {
            say(sender, "usage-paste");
            return;
        }
        if (dungeon.reset.busy()) {
            say(sender, "reset-busy");
            return;
        }
        File file = new File(a[0]);
        if (!file.isAbsolute()) file = new File(plugin.getDataFolder(), a[0]);
        World world;
        int x, y, z;
        if (a.length >= 5) {
            world = Bukkit.getWorld(a[1]);
            x = (int) number(a[2], 0);
            y = (int) number(a[3], 0);
            z = (int) number(a[4], 0);
        } else if (sender instanceof Player p) {
            world = p.getWorld();
            x = p.getLocation().getBlockX();
            y = p.getLocation().getBlockY();
            z = p.getLocation().getBlockZ();
        } else {
            say(sender, "usage-paste");
            return;
        }
        if (world == null || !file.exists()) {
            say(sender, "paste-missing");
            return;
        }
        File source = file;
        say(sender, "paste-started");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Snapshot snapshot = SchemFile.read(source.toPath());
                snapshot.originX = x;
                snapshot.originY = y;
                snapshot.originZ = z;
                Bukkit.getScheduler().runTask(plugin, () -> dungeon.reset.restore(snapshot, world, false, result ->
                        say(sender, "paste-done", "blocks", String.valueOf(result.changed()), "seconds", String.valueOf(result.millis() / 1000)),
                        line -> sender.sendMessage(Text.component("<gray>" + line))));
            } catch (Exception e) {
                say(sender, "paste-failed", "error", String.valueOf(e.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        boolean isAdmin = sender.hasPermission("dungeon.admin") || sender.hasPermission("dungeon.wand");
        if (args.length == 1) {
            options.addAll(PLAYER);
            if (isAdmin) options.addAll(ADMIN);
        } else if (args.length == 2 && isAdmin) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "region" -> options.addAll(List.of("save", "cuboid", "polygon", "list", "show", "delete", "setclean"));
                case "door" -> options.addAll(List.of("define", "cuboid", "list", "delete", "open", "close"));
                case "point" -> options.addAll(List.of("add", "list", "remove", "clear"));
                case "chest" -> options.addAll(List.of("add", "list", "remove", "clear"));
                case "wand" -> options.addAll(List.of("cuboid", "polygon"));
                case "forcestart" -> options.add("now");
                case "menu" -> options.add("reload");
                default -> { }
            }
        } else if (args.length == 3 && isAdmin) {
            String head = args[0].toLowerCase(Locale.ROOT) + " " + args[1].toLowerCase(Locale.ROOT);
            if (head.equals("region save") || head.equals("region cuboid") || head.equals("region polygon")) {
                for (RegionType t : RegionType.values()) options.add(t.name().toLowerCase(Locale.ROOT));
            } else if (head.equals("region show") || head.equals("region delete")) {
                dungeon.regions.all().forEach(r -> options.add(r.id()));
            } else if (head.startsWith("door ") && !head.equals("door list") && !head.equals("door define")) {
                options.addAll(dungeon.doors.ids());
            } else if (head.startsWith("point ")) {
                options.addAll(dungeon.points.all().keySet());
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
