package dev.bastion.listener;

import dev.bastion.boss.Bosses;
import dev.bastion.dungeon.Dialogue.Trigger;
import dev.bastion.dungeon.Dungeon;
import dev.bastion.dungeon.Run;
import dev.bastion.menu.Menus;
import dev.bastion.mob.Mobs;
import dev.bastion.region.Wand;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Every event the dungeon reacts to. Each handler starts by asking one cheap question, "is this player or mob in the
 * run?", so when nobody is playing the whole class costs one map lookup per event.
 */
public final class DungeonListener implements Listener {

    private final Dungeon dungeon;
    private final Menus menus;
    private final Wand wand;

    public DungeonListener(Dungeon dungeon, Menus menus, Wand wand) {
        this.dungeon = dungeon;
        this.menus = menus;
        this.wand = wand;
    }

    // ---------------------------------------------------------------- players

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!dungeon.state().active()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        // only a step from one block to another can cross a boundary, so look and sub-block movement cost nothing
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player player = event.getPlayer();
        Run run = dungeon.run(player.getUniqueId());
        if (run == null || !run.inside()) return;
        if (dungeon.blocksMove(player, from, to)) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            if (now >= run.nextWarn) {
                run.nextWarn = now + 2500;
                player.sendActionBar(dungeon.messages.text("locked"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!dungeon.isInside(player.getUniqueId())) return;
        // keep everything, and no death message in the server chat
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        dungeon.died(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Run run = dungeon.run(player.getUniqueId());
        if (run == null || !run.eliminated) return;
        Location back = dungeon.respawnFor(player);
        if (back != null) event.setRespawnLocation(back);
        Bukkit.getScheduler().runTaskLater(dungeon.plugin, () -> dungeon.afterRespawn(player), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dungeon.partyChanged();
        wand.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        dungeon.partyChanged();
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // somebody who was in the dungeon when the run ended (or the server stopped) is sent home
        if (dungeon.store.returnOf(id) != null && !dungeon.isInside(id)) {
            Bukkit.getScheduler().runTaskLater(dungeon.plugin, () -> {
                if (player.isOnline() && !dungeon.isInside(id)) dungeon.sendBack(player);
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!dungeon.isInside(player.getUniqueId()) || player.hasPermission("dungeon.bypass.commands")) return;
        String text = event.getMessage();
        int space = text.indexOf(' ');
        String root = (space < 0 ? text.substring(1) : text.substring(1, space)).toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        if (!dungeon.settings.commandWhitelist.contains(root)) {
            event.setCancelled(true);
            dungeon.messages.send(player, "silenced");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        if (menus.takeAmount(sender, net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()))) {
            event.setCancelled(true);
            return;
        }
        if (!dungeon.state().active()) return;
        boolean in = dungeon.isInside(sender.getUniqueId());
        if (in) {
            // dungeon chat: only the party (and staff who listen) see it
            event.viewers().removeIf(a -> a instanceof Player p && !dungeon.isInside(p.getUniqueId()) && !p.hasPermission("dungeon.spy"));
            Component prefix = dungeon.messages.text("chat-prefix");
            event.renderer((source, name, message, viewer) -> prefix.append(name).append(Component.text(": ")).append(message));
        } else {
            // and nothing from outside leaks in
            event.viewers().removeIf(a -> a instanceof Player p && dungeon.isInside(p.getUniqueId()));
        }
    }

    // ---------------------------------------------------------------- nothing is built or broken while waiting

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        stopBuilding(event.getPlayer(), event);
        if (!event.isCancelled()) autosave(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        stopBuilding(event.getPlayer(), event);
        if (!event.isCancelled()) autosave(event.getPlayer(), event.getBlock());
    }

    /** An admin building in the dungeon while it is idle: the saved snapshot follows what they do. */
    private void autosave(Player player, org.bukkit.block.Block block) {
        if (dungeon.state() != dev.bastion.dungeon.DungeonState.FUNDING || !player.hasPermission("dungeon.admin")) return;
        if (!dungeon.regions.inType(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), dev.bastion.region.RegionType.DUNGEON)) return;
        dungeon.editor.mark(block, key -> {
            if (key != null) player.sendActionBar(dungeon.messages.text(key));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        stopBuilding(event.getPlayer(), event);
    }

    /** Until the waiting is over, everybody in the spawn hall keeps their hands to themselves. */
    private void stopBuilding(Player player, org.bukkit.event.Cancellable event) {
        if (!dungeon.waiting() || !dungeon.isInside(player.getUniqueId())) return;
        event.setCancelled(true);
        player.sendActionBar(dungeon.messages.text("no-build"));
    }

    // ---------------------------------------------------------------- chests and items

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !dungeon.isInside(player.getUniqueId())) return;
        Location at = event.getInventory().getLocation();
        if (at == null) return;
        Block block = at.getBlock();
        if (dungeon.artifacts.isHidden(block)) dungeon.artifacts.opened(block, player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && dungeon.isInside(player.getUniqueId())) {
            dungeon.artifacts.pickedUp(player, event.getItem().getItemStack());
        }
    }

    // ---------------------------------------------------------------- mobs and bosses

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof LivingEntity living)) return;

        if (dungeon.mobs.count() > 0) {
            Mobs.State state = dungeon.mobs.state(victim);
            if (state != null) {
                Bosses.BossFight fight = dungeon.bosses.fightOf(victim);
                if (event instanceof EntityDamageByEntityEvent byEntity) {
                    if (dungeon.mobs.isDungeonMob(byEntity.getDamager())
                            || (byEntity.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity e && dungeon.mobs.isDungeonMob(e))) {
                        event.setCancelled(true);   // mobs do not hurt each other
                        return;
                    }
                    if (fight != null) dungeon.bosses.hit(fight, byEntity.getDamager());
                    if (dungeon.settings.topDamageCredit) {
                        Entity source = byEntity.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity e ? e : byEntity.getDamager();
                        if (source instanceof Player p) state.damage.merge(p.getUniqueId(), event.getFinalDamage(), Double::sum);
                    }
                }
                if (fight != null && dungeon.bosses.intercept(fight, event.getFinalDamage())) {
                    event.setCancelled(true);
                    return;
                }
                Bukkit.getScheduler().runTask(dungeon.plugin, () -> {
                    if (living.isValid()) dungeon.mobs.flicker(state, living);
                });
            }
        }

        // a player hurt by a dungeon mob
        if (victim instanceof Player player && event instanceof EntityDamageByEntityEvent byEntity && dungeon.isInside(player.getUniqueId())) {
            Entity source = byEntity.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity e ? e : byEntity.getDamager();
            Mobs.State attacker = dungeon.mobs.state(source);
            if (attacker != null) {
                if (source instanceof LivingEntity le) dungeon.mobs.shove(attacker, le, player, System.currentTimeMillis());
                dungeon.mobs.touch(attacker, player);
                dungeon.dialogue.fireTo(Trigger.ON_PLAYER_HURT_BY_MOB, player, Map.of("player", player.getName()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (dungeon.mobs.count() == 0) return;
        Mobs.State state = dungeon.mobs.died(entity);
        if (state == null) return;
        // nothing they wear or carry drops, and no experience
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (state.trait == Mobs.Trait.BLAST) dungeon.mobs.blast(entity.getLocation());
        Player killer = entity.getKiller();
        if (dungeon.settings.topDamageCredit && !state.damage.isEmpty()) {
            UUID top = null;
            double best = 0;
            for (Map.Entry<UUID, Double> e : state.damage.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    top = e.getKey();
                }
            }
            Player p = top == null ? null : Bukkit.getPlayer(top);
            if (p != null) killer = p;
        }
        if (state.onDeath != null) state.onDeath.accept(entity, killer);
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectile(ProjectileHitEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(Mobs.HURL, PersistentDataType.BYTE)) return;
        if (event.getHitEntity() instanceof Player player && dungeon.isInside(player.getUniqueId())) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, true, true));
            Bosses.hurt(player, 2, null, 4);
        }
        event.getEntity().remove();
    }

    // ---------------------------------------------------------------- the wand

    @EventHandler(ignoreCancelled = false)
    public void onWand(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !Wand.isWand(event.getItem())) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("dungeon.wand")) return;
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_BLOCK;
        if (!left && action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        if (event.getClickedBlock() != null) wand.click(player, left, event.getClickedBlock().getLocation());
    }
}
