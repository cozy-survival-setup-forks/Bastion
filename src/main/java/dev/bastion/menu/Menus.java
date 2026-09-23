package dev.bastion.menu;

import dev.bastion.dungeon.Dungeon;
import dev.bastion.dungeon.DungeonState;
import dev.bastion.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A small config-driven menu framework in the style of DeluxeMenus. menus.yml defines menus as a grid of items; an
 * item can appear several times for one slot with different priorities and states, and the best match is shown.
 * Click actions are lines like {@code [COMMAND] balance} or {@code [CONTRIBUTE] 500}. A new action type is one
 * entry in {@link #actions}.
 */
public final class Menus implements Listener {

    /** What an action does. Gets the player and the text after the tag. */
    public interface Action {
        void run(Player player, String argument);
    }

    private record Item(int slot, int priority, Set<DungeonState> states, Material material, String name,
                        List<String> lore, boolean glow, int amount, List<String> any, List<String> left, List<String> right, int price) {
    }

    private record Menu(String id, String title, int rows, List<Item> items) {
    }

    private static final class Holder implements InventoryHolder {
        final Menu menu;
        final Map<Integer, Item> shown = new HashMap<>();
        Inventory inventory;

        Holder(Menu menu) {
            this.menu = menu;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final Dungeon dungeon;
    private final Map<String, Menu> menus = new LinkedHashMap<>();
    private final Map<String, Action> actions = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final Set<UUID> typingAmount = new HashSet<>();
    private BukkitTask refresher;

    public Menus(JavaPlugin plugin, Dungeon dungeon) {
        this.plugin = plugin;
        this.dungeon = dungeon;
        registerDefaults();
    }

    /** Adds or replaces an action type. */
    public void register(String tag, Action action) {
        actions.put(tag.toUpperCase(Locale.ROOT), action);
    }

    private void registerDefaults() {
        register("COMMAND", (p, a) -> p.performCommand(fill(p, a)));
        register("CONSOLE", (p, a) -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fill(p, a)));
        register("MESSAGE", (p, a) -> p.sendMessage(Text.component(fill(p, a))));
        register("OPEN_MENU", (p, a) -> Bukkit.getScheduler().runTask(plugin, () -> open(p, a.trim())));
        register("CLOSE", (p, a) -> Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory()));
        register("SOUND", (p, a) -> {
            NamespacedKey key = NamespacedKey.fromString(a.trim().toLowerCase(Locale.ROOT));
            Sound sound = key == null ? null : Registry.SOUNDS.get(key);
            if (sound != null) p.playSound(p.getLocation(), sound, 1f, 1f);
        });
        register("CONTRIBUTE", (p, a) -> {
            try {
                String error = dungeon.contribute(p, Double.parseDouble(a.trim()));
                if (error != null) dungeon.messages.send(p, error);
            } catch (NumberFormatException e) {
                dungeon.messages.send(p, "bad-amount");
            }
        });
        register("CONTRIBUTE_PROMPT", (p, a) -> {
            if (dungeon.state() != DungeonState.FUNDING) {
                dungeon.messages.send(p, "in-progress");
                return;
            }
            typingAmount.add(p.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            dungeon.messages.send(p, "type-amount");
        });
        register("ENTER", (p, a) -> {
            String error = dungeon.enter(p);
            if (error != null) dungeon.messages.send(p, error);
            else Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        });
        register("LEAVE", (p, a) -> dungeon.leave(p));
    }

    // ---------------------------------------------------------------- config

    public void load() {
        menus.clear();
        File file = new File(plugin.getDataFolder(), "menus.yml");
        if (!file.exists()) plugin.saveResource("menus.yml", false);
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("menus");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            List<Item> items = new ArrayList<>();
            ConfigurationSection list = s.getConfigurationSection("items");
            if (list != null) {
                for (String key : list.getKeys(false)) {
                    ConfigurationSection i = list.getConfigurationSection(key);
                    if (i != null) items.addAll(item(key, i));
                }
            }
            menus.put(id.toLowerCase(Locale.ROOT), new Menu(id, s.getString("title", id), Math.max(1, Math.min(6, s.getInt("rows", 3))), items));
        }
    }

    private List<Item> item(String key, ConfigurationSection s) {
        Material material = Material.matchMaterial(s.getString("material", "STONE"));
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("menus.yml: " + key + " has an unknown material");
            return List.of();
        }
        Set<DungeonState> states = EnumSet.noneOf(DungeonState.class);
        for (String name : s.getStringList("states")) {
            try {
                states.add(DungeonState.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("menus.yml: " + key + " has an unknown state " + name);
            }
        }
        List<Integer> slots = new ArrayList<>();
        for (String part : s.getStringList("slots")) {
            String[] range = part.trim().split("-");
            try {
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int i = from; i <= to; i++) slots.add(i);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("menus.yml: " + key + " has a bad slot " + part);
            }
        }
        if (slots.isEmpty()) slots.add(s.getInt("slot", 0));
        List<Item> made = new ArrayList<>();
        for (int slot : slots) {
            made.add(new Item(slot, s.getInt("priority", 0), states, material, s.getString("name", " "),
                    s.getStringList("lore"), s.getBoolean("glow", false), Math.max(1, s.getInt("amount", 1)),
                    s.getStringList("actions"), s.getStringList("left-actions"), s.getStringList("right-actions"),
                    Math.max(0, s.getInt("price", 0))));
        }
        return made;
    }

    public Collection<String> ids() {
        return List.copyOf(menus.keySet());
    }

    // ---------------------------------------------------------------- showing

    public boolean open(Player player, String id) {
        Menu menu = menus.get(id.toLowerCase(Locale.ROOT));
        if (menu == null) return false;
        Holder holder = new Holder(menu);
        holder.inventory = Bukkit.createInventory(holder, menu.rows * 9, Text.component(fill(player, menu.title)));
        render(player, holder);
        player.openInventory(holder.inventory);
        viewers.add(player.getUniqueId());
        if (refresher == null) refresher = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 20L);
        return true;
    }

    private void render(Player player, Holder holder) {
        holder.shown.clear();
        DungeonState state = dungeon.state();
        Map<Integer, Item> best = new HashMap<>();
        for (Item item : holder.menu.items) {
            if (!item.states.isEmpty() && !item.states.contains(state)) continue;
            if (item.slot < 0 || item.slot >= holder.inventory.getSize()) continue;
            Item current = best.get(item.slot);
            if (current == null || item.priority > current.priority) best.put(item.slot, item);
        }
        holder.inventory.clear();
        best.forEach((slot, item) -> {
            holder.shown.put(slot, item);
            holder.inventory.setItem(slot, stack(player, item));
        });
    }

    private ItemStack stack(Player player, Item item) {
        ItemStack stack = new ItemStack(item.material, item.amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.item(fill(player, item.name)));
        List<Component> lore = new ArrayList<>();
        for (String line : item.lore) lore.add(Text.item(fill(player, line.replace("%price%", String.valueOf(item.price)))));
        meta.lore(lore);
        if (item.glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Once a second while anyone has a menu open: only their own items are rebuilt. */
    private void refresh() {
        boolean any = false;
        for (UUID id : List.copyOf(viewers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) {
                render(p, holder);
                any = true;
            } else {
                viewers.remove(id);
            }
        }
        if (!any && refresher != null) {
            refresher.cancel();
            refresher = null;
        }
    }

    /** %dungeon_...%, %player%, and PlaceholderAPI if there. */
    private String fill(Player player, String text) {
        String out = text.replace("%player%", player.getName());
        if (out.contains("%dungeon_")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("%dungeon_([a-z0-9_]+)%").matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String value = m.group(1).equals("goal_bar") ? bar() : dungeon.placeholder(player, m.group(1));
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value == null ? m.group() : value));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        if (out.contains("%") && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, out);
        }
        return out;
    }

    private String bar() {
        int filled = (int) Math.min(20, Math.round(dungeon.funding() * 20 / dungeon.settings.goal));
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(20 - filled);
    }

    // ---------------------------------------------------------------- input

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Item item = holder.shown.get(event.getSlot());
        if (item == null) return;
        if (item.price > 0) {
            buy(player, item);
            return;
        }
        run(player, item.any);
        if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) run(player, item.right);
        else run(player, item.left);
    }

    /**
     * A priced menu item: buying takes the shop currency and gives a plain copy of the displayed item (no custom
     * name/lore/enchants carried over). For anything fancier, skip `price` and drive the reward yourself with
     * [COMMAND]/[CONSOLE] actions instead.
     */
    private void buy(Player player, Item item) {
        if (!dungeon.artifacts.takeShopCurrency(player, item.price)) {
            dungeon.messages.send(player, "not-enough");
            return;
        }
        var leftover = player.getInventory().addItem(new ItemStack(item.material, item.amount));
        leftover.values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        dungeon.messages.send(player, "shop-bought", "price", String.valueOf(item.price));
    }

    private void run(Player player, List<String> lines) {
        for (String line : lines) {
            String text = line.trim();
            int close = text.indexOf(']');
            if (!text.startsWith("[") || close < 0) continue;
            Action action = actions.get(text.substring(1, close).toUpperCase(Locale.ROOT));
            if (action != null) action.run(player, text.substring(close + 1).trim());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        viewers.remove(event.getPlayer().getUniqueId());
    }

    /** True if the player was asked for an amount, and this was it. Called from the chat listener. */
    public boolean takeAmount(Player player, String message) {
        if (!typingAmount.remove(player.getUniqueId())) return false;
        String text = message.trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                dungeon.messages.send(player, "cancelled");
                return;
            }
            try {
                String error = dungeon.contribute(player, Double.parseDouble(text));
                if (error != null) dungeon.messages.send(player, error);
            } catch (NumberFormatException e) {
                dungeon.messages.send(player, "bad-amount");
            }
        });
        return true;
    }

    public void closeAll() {
        for (UUID id : List.copyOf(viewers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
        viewers.clear();
        if (refresher != null) refresher.cancel();
        refresher = null;
    }
}
