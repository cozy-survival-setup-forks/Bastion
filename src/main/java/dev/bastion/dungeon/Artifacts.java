package dev.bastion.dungeon;

import dev.bastion.util.Text;
import dev.bastion.world.Points;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The nine artifacts. Some are hidden in chests in the first room, the rest are dropped by the mini-boss. The unlock
 * of the last door is one check, "how many have been found in total", so the split can be changed freely.
 * The tenth, the special artifact, is not one of the nine: it is the shop currency the final boss drops.
 */
public final class Artifacts {

    public static final NamespacedKey ID = new NamespacedKey("bastion", "artifact");
    public static final NamespacedKey SHOP = new NamespacedKey("bastion", "shop_currency");

    private record Template(String id, Material material, String name, List<String> lore, int model, boolean consume) {
    }

    /** What happened: which artifact, who found it, and which number it was. */
    public record Found(String id, String name, Player finder, int order) {
    }

    private final JavaPlugin plugin;
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private Template special;

    private final List<String> room1 = new ArrayList<>();
    private final List<String> room2 = new ArrayList<>();
    private final Map<Long, String> hidden = new HashMap<>();
    private final Map<Long, Location> hiddenLocations = new HashMap<>();
    private final Set<String> found = new LinkedHashSet<>();
    private final Set<String> room1Found = new LinkedHashSet<>();
    private final Set<String> dropped = new LinkedHashSet<>();
    private Consumer<Found> onFound = f -> { };

    public Artifacts(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        templates.clear();
        var yaml = plugin.getConfig();
        for (Map<?, ?> m : yaml.getMapList("artifacts.items")) {
            Template t = template(m, false);
            if (t != null) templates.put(t.id, t);
        }
        ConfigurationSection s = yaml.getConfigurationSection("artifacts.special");
        special = s == null ? null : template(s.getValues(false), true);
    }

    private Template template(Map<?, ?> m, boolean isSpecial) {
        String id = String.valueOf(m.get("id"));
        Material material = Material.matchMaterial(String.valueOf(m.get("material")));
        if (material == null || id.equals("null")) {
            plugin.getLogger().warning("artifacts.yml: '" + id + "' has no valid id or material, skipping it");
            return null;
        }
        List<String> lore = new ArrayList<>();
        if (m.get("lore") instanceof List<?> l) l.forEach(o -> lore.add(String.valueOf(o)));
        return new Template(id, material, String.valueOf(m.get("name") == null ? id : m.get("name")), lore,
                m.get("custom-model-data") instanceof Number n ? n.intValue() : 0,
                m.get("consume") instanceof Boolean b ? b : false);
    }

    public void onFound(Consumer<Found> callback) {
        this.onFound = callback;
    }

    public int total() {
        return templates.size();
    }

    public int found() {
        return found.size();
    }

    public int room1Found() {
        return room1Found.size();
    }

    public int room1Quota() {
        return room1.size();
    }

    public boolean complete() {
        return !templates.isEmpty() && found.size() >= templates.size();
    }

    // ---------------------------------------------------------------- a run

    public void newRun(int room1Count) {
        clear();
        List<String> ids = new ArrayList<>(templates.keySet());
        Collections.shuffle(ids);
        int n = Math.min(room1Count, ids.size());
        room1.addAll(ids.subList(0, n));
        room2.addAll(ids.subList(n, ids.size()));
    }

    public void clear() {
        room1.clear();
        room2.clear();
        hidden.clear();
        hiddenLocations.clear();
        found.clear();
        room1Found.clear();
        dropped.clear();
    }

    /** Hides the first room's artifacts in a random choice of the marked chests. Returns how many were hidden. */
    public int hideRoom1(Points points) {
        List<Points.Spot> spots = new ArrayList<>(points.get("chests"));
        Collections.shuffle(spots);
        int wanted = room1.size();
        int placed = 0;
        for (Points.Spot spot : spots) {
            if (placed >= wanted) break;
            Location at = spot.at();
            if (at == null) continue;
            Block block = at.getBlock();
            // a spot that is not a container yet gets a chest, turned to open into the room
            // floor plants and carpets count as free space
            if (!(block.getState() instanceof Container) && (block.getType().isAir() || block.isReplaceable())) {
                block.setType(Material.CHEST, false);
                if (block.getBlockData() instanceof Directional d) {
                    d.setFacing(openSide(block));
                    block.setBlockData(d, false);
                }
            }
            if (!(block.getState() instanceof Container container)) continue;
            String id = room1.get(placed);
            container.getInventory().clear();
            container.getInventory().setItem(container.getInventory().getSize() / 2, make(templates.get(id), null, 0));
            hidden.put(key(block), id);
            hiddenLocations.put(key(block), block.getLocation());
            placed++;
        }
        if (placed < wanted) {
            plugin.getLogger().warning("Only " + placed + " of " + wanted + " chest spots in the 'chests' point group are containers. Mark more.");
            // artifacts with no chest to hide in are dropped by the mini-boss instead, so none can go missing
            while (room1.size() > placed) room2.add(room1.remove(room1.size() - 1));
        }
        return placed;
    }

    private static BlockFace openSide(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            if (block.getRelative(face).getType().isAir() && block.getRelative(face.getOppositeFace()).getType().isSolid()) return face;
        }
        return BlockFace.NORTH;
    }

    /** Where artifacts are still hidden, for the glint that helps players find them. */
    public java.util.Collection<Location> hiddenAt() {
        return hiddenLocations.values();
    }

    /** True if this block hides an artifact nobody has found yet. */
    public boolean isHidden(Block block) {
        return hidden.containsKey(key(block));
    }

    /** A player opened a chest that hides an artifact: it is found, and the item is signed with their name. */
    public void opened(Block block, Player finder) {
        String id = hidden.remove(key(block));
        if (id == null) return;
        hiddenLocations.remove(key(block));
        found.add(id);
        room1Found.add(id);
        if (block.getState() instanceof Container container) {
            container.getInventory().clear();
            container.getInventory().setItem(container.getInventory().getSize() / 2, make(templates.get(id), finder, found.size()));
        }
        onFound.accept(new Found(id, plain(id), finder, found.size()));
    }

    /**
     * The mini-boss is dead: the rest of the artifacts appear. With auto distribution they go straight to the nearest
     * players, one each in turn, and any left over are dropped; otherwise they all drop on the ground.
     */
    public void dropRoom2(Location at, List<Player> nearest, boolean distribute) {
        List<Player> order = new ArrayList<>(nearest);
        order.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(at), b.getLocation().distanceSquared(at)));
        int turn = 0;
        for (String id : room2) {
            if (found.contains(id)) continue;
            if (distribute && !order.isEmpty()) {
                Player to = order.get(turn++ % order.size());
                found.add(id);
                ItemStack item = make(templates.get(id), to, found.size());
                var leftover = to.getInventory().addItem(item);
                leftover.values().forEach(rest -> to.getWorld().dropItemNaturally(to.getLocation(), rest));
                onFound.accept(new Found(id, plain(id), to, found.size()));
            } else {
                dropped.add(id);
                var item = at.getWorld().dropItem(at, make(templates.get(id), null, 0));
                item.setGlowing(true);
                item.setUnlimitedLifetime(true);
            }
        }
    }

    /** Someone picked up an item that may be a dropped artifact. */
    public void pickedUp(Player player, ItemStack item) {
        String id = idOf(item);
        if (id == null || !dropped.remove(id)) return;
        found.add(id);
        ItemMeta meta = item.getItemMeta();
        Template t = templates.get(id);
        if (meta != null && t != null) {
            ItemStack signed = make(t, player, found.size());
            item.setItemMeta(signed.getItemMeta());
        }
        onFound.accept(new Found(id, plain(id), player, found.size()));
    }

    // ---------------------------------------------------------------- items

    private ItemStack make(Template t, Player finder, int order) {
        ItemStack item = new ItemStack(t.material);
        ItemMeta meta = item.getItemMeta();
        Map<String, String> values = Map.of("%player_found_by%", finder == null ? "nobody yet" : finder.getName(),
                "%find_order%", finder == null ? "?" : ordinal(order), "%total%", String.valueOf(templates.size()));
        meta.displayName(Text.item(Text.fill(t.name, values)));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : t.lore) lore.add(Text.item(Text.fill(line, values)));
        meta.lore(lore);
        if (t.model > 0) meta.setCustomModelData(t.model);
        meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, t.id);
        item.setItemMeta(meta);
        return item;
    }

    /** The special artifact, the shop currency. Not counted with the nine. */
    public ItemStack makeSpecial() {
        if (special == null) return null;
        ItemStack item = make(special, null, 0);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(SHOP, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(ID, PersistentDataType.STRING);
    }

    /** Takes the artifacts that are set to be consumed out of a player's inventory. Returns how many. */
    public int consume(Player player, boolean all) {
        int removed = 0;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            String id = idOf(item);
            if (id == null) continue;
            Template t = templates.get(id);
            if (all || (t != null && t.consume)) {
                player.getInventory().setItem(i, null);
                removed++;
            }
        }
        return removed;
    }

    private String plain(String id) {
        Template t = templates.get(id);
        return t == null ? id : Text.plain(t.name);
    }

    private static String ordinal(int n) {
        int mod = n % 100;
        String suffix = (mod >= 11 && mod <= 13) ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    private static long key(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFF);
    }
}
