package dev.bastion.region;

import dev.bastion.dungeon.Messages;
import dev.bastion.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The selection wand and what it selects. Cuboid mode: left click is the first corner, right click the second.
 * Polygon mode: right click each corner in order, left click while sneaking closes the shape. The outline is drawn
 * for the admin only, and never more than a few hundred particles at a time.
 */
public final class Wand {

    public static final NamespacedKey KEY = new NamespacedKey("bastion", "wand");
    private static final int MAX_PARTICLES = 600;

    public static final class Selection {
        public boolean polygon;
        public Location a, b;
        public final List<Location> vertices = new ArrayList<>();
        public boolean closed;
    }

    private final Messages messages;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public Wand(Messages messages) {
        this.messages = messages;
    }

    public ItemStack item() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item("<gold>Dungeon Wand"));
        meta.lore(List.of(Text.item("<gray>Cuboid: left click = corner 1, right click = corner 2"),
                Text.item("<gray>Polygon: right click each corner, sneak + left click to close")));
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    public Selection of(UUID id) {
        return selections.computeIfAbsent(id, k -> new Selection());
    }

    public Selection peek(UUID id) {
        return selections.get(id);
    }

    public void polygonMode(UUID id, boolean on) {
        Selection s = of(id);
        s.polygon = on;
        s.vertices.clear();
        s.closed = false;
    }

    /** A click with the wand. {@code left} is the button, {@code block} the block clicked. */
    public void click(Player player, boolean left, Location block) {
        Selection s = of(player.getUniqueId());
        if (!s.polygon) {
            if (left) {
                s.a = block;
                messages.send(player, "wand-corner", "number", "1", "position", pos(block));
            } else {
                s.b = block;
                messages.send(player, "wand-corner", "number", "2", "position", pos(block));
            }
        } else if (left) {
            if (player.isSneaking()) {
                if (s.vertices.size() < 3) {
                    messages.send(player, "wand-few");
                    return;
                }
                s.closed = true;
                messages.send(player, "wand-closed", "count", String.valueOf(s.vertices.size()));
            } else {
                messages.send(player, "wand-polygon-help");
            }
        } else {
            if (s.closed) {
                s.vertices.clear();
                s.closed = false;
            }
            s.vertices.add(block);
            messages.send(player, "wand-corner", "number", String.valueOf(s.vertices.size()), "position", pos(block));
        }
        draw(player, s);
    }

    private static String pos(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    public void draw(Player player, Selection s) {
        if (!s.polygon) {
            if (s.a != null && s.b != null && s.a.getWorld() == s.b.getWorld()) {
                outlineBox(player, Math.min(s.a.getBlockX(), s.b.getBlockX()), Math.min(s.a.getBlockY(), s.b.getBlockY()),
                        Math.min(s.a.getBlockZ(), s.b.getBlockZ()), Math.max(s.a.getBlockX(), s.b.getBlockX()) + 1,
                        Math.max(s.a.getBlockY(), s.b.getBlockY()) + 1, Math.max(s.a.getBlockZ(), s.b.getBlockZ()) + 1);
            } else if (s.a != null) {
                mark(player, s.a);
            } else if (s.b != null) {
                mark(player, s.b);
            }
            return;
        }
        for (Location v : s.vertices) mark(player, v);
        double[] xs = new double[s.vertices.size()];
        double[] zs = new double[s.vertices.size()];
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            xs[i] = s.vertices.get(i).getBlockX();
            zs[i] = s.vertices.get(i).getBlockZ();
            low = Math.min(low, s.vertices.get(i).getBlockY());
            high = Math.max(high, s.vertices.get(i).getBlockY() + 1);
        }
        if (xs.length >= 2) outlinePolygon(player, xs, zs, low, high, s.closed);
    }

    private void mark(Player player, Location l) {
        player.spawnParticle(Particle.END_ROD, l.getBlockX() + 0.5, l.getBlockY() + 1.2, l.getBlockZ() + 0.5, 6, 0.1, 0.2, 0.1, 0.01);
    }

    /** Draws a region's edges. Used by /dungeon region show. */
    public void outline(Player player, Region region) {
        if (region instanceof PolygonRegion polygon) {
            outlinePolygon(player, polygon.xs(), polygon.zs(), region.minY(), region.maxY() + 1, true);
        } else {
            outlineBox(player, region.minX(), region.minY(), region.minZ(), region.maxX() + 1, region.maxY() + 1, region.maxZ() + 1);
        }
    }

    private void outlineBox(Player player, int x1, int y1, int z1, int x2, int y2, int z2) {
        double perimeter = 4.0 * ((x2 - x1) + (y2 - y1) + (z2 - z1));
        double step = Math.max(1, perimeter / MAX_PARTICLES);
        Particle.DustOptions dust = new Particle.DustOptions(Color.AQUA, 1f);
        for (double x = x1; x <= x2; x += step) for (int y : new int[]{y1, y2}) for (int z : new int[]{z1, z2}) dot(player, x, y, z, dust);
        for (double y = y1; y <= y2; y += step) for (int x : new int[]{x1, x2}) for (int z : new int[]{z1, z2}) dot(player, x, y, z, dust);
        for (double z = z1; z <= z2; z += step) for (int x : new int[]{x1, x2}) for (int y : new int[]{y1, y2}) dot(player, x, y, z, dust);
    }

    private void outlinePolygon(Player player, double[] xs, double[] zs, double low, double high, boolean closed) {
        double length = 0;
        int n = xs.length;
        for (int i = 0; i < (closed ? n : n - 1); i++) length += Math.hypot(xs[(i + 1) % n] - xs[i], zs[(i + 1) % n] - zs[i]);
        double step = Math.max(1, length * 2 / MAX_PARTICLES);
        Particle.DustOptions dust = new Particle.DustOptions(Color.YELLOW, 1f);
        for (int i = 0; i < (closed ? n : n - 1); i++) {
            double x0 = xs[i], z0 = zs[i], x1 = xs[(i + 1) % n], z1 = zs[(i + 1) % n];
            double segment = Math.hypot(x1 - x0, z1 - z0);
            for (double d = 0; d <= segment; d += step) {
                double t = segment == 0 ? 0 : d / segment;
                dot(player, x0 + (x1 - x0) * t, low, z0 + (z1 - z0) * t, dust);
                dot(player, x0 + (x1 - x0) * t, high, z0 + (z1 - z0) * t, dust);
            }
            for (double y = low; y <= high; y += Math.max(1, step)) dot(player, x0, y, z0, dust);
        }
    }

    private void dot(Player player, double x, double y, double z, Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
    }

    public void forget(UUID id) {
        selections.remove(id);
    }
}
