package dev.bastion.mob;

import dev.bastion.util.Fx;
import dev.bastion.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Dungeon mobs: vanilla entities dressed from mobs.yml. Everything that runs per mob is driven from one shared tick
 * and one shared emerge task, never a task per mob, so the cost grows with the mob cap, which is small.
 */
public final class Mobs {

    public static final NamespacedKey TAG = new NamespacedKey("bastion", "mob");
    public static final NamespacedKey HURL = new NamespacedKey("bastion", "hurl");

    public enum Trait { SHOVE, FLICKER, HURL, WAR_CRY }

    public record MobDef(String id, String name, EntityType type, double health, double damage, double speed,
                         Map<EquipmentSlot, Material> gear, List<PotionEffect> effects, String tier,
                         double traitChance, List<Trait> traits, boolean baby) {
    }

    /** What is known about a live dungeon mob. */
    public static final class State {
        public final MobDef def;
        public Trait trait;
        public long nextTraitAt;
        public boolean flickered;
        public final Map<UUID, Double> damage = new HashMap<>();
        /** Called when it dies, with the player who gets the credit (may be null). */
        public BiConsumer<LivingEntity, Player> onDeath;

        State(MobDef def) {
            this.def = def;
        }
    }

    private final class Emerging {
        final LivingEntity entity;
        final Location ground;
        final double fromScale, toScale;
        final int total;
        final Runnable ready;
        int step;

        Emerging(LivingEntity entity, Location ground, double fromScale, double toScale, int total, Runnable ready) {
            this.entity = entity;
            this.ground = ground;
            this.fromScale = fromScale;
            this.toScale = toScale;
            this.total = total;
            this.ready = ready;
        }
    }

    private static final double SINK = 0.6;
    private static final int EMERGE_STEPS = 12;
    private static final int EMERGE_PERIOD = 2;

    private final Plugin plugin;
    private final Fx fx;
    private final Supplier<Collection<Player>> alive;
    private final Map<String, MobDef> defs = new LinkedHashMap<>();
    private final Map<UUID, State> tracked = new HashMap<>();
    private final List<Emerging> emerging = new ArrayList<>();
    private BukkitTask emergeTask;

    public Mobs(Plugin plugin, Fx fx, Supplier<Collection<Player>> alive) {
        this.plugin = plugin;
        this.fx = fx;
        this.alive = alive;
    }

    // ---------------------------------------------------------------- config

    public void load() {
        defs.clear();
        File file = new File(plugin.getDataFolder(), "mobs.yml");
        if (!file.exists()) plugin.saveResource("mobs.yml", false);
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("mobs");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            try {
                defs.put(id, parse(id, s));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("mobs.yml: " + id + " is broken (" + e.getMessage() + "), skipping it");
            }
        }
    }

    public static MobDef parse(String id, ConfigurationSection s) {
        EntityType type = EntityType.valueOf(s.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
        if (type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            throw new IllegalArgumentException(type + " is not a living entity");
        }
        Map<EquipmentSlot, Material> gear = new EnumMap<>(EquipmentSlot.class);
        slot(s, "head", EquipmentSlot.HEAD, gear);
        slot(s, "chest", EquipmentSlot.CHEST, gear);
        slot(s, "legs", EquipmentSlot.LEGS, gear);
        slot(s, "feet", EquipmentSlot.FEET, gear);
        slot(s, "hand", EquipmentSlot.HAND, gear);
        slot(s, "offhand", EquipmentSlot.OFF_HAND, gear);

        List<PotionEffect> effects = new ArrayList<>();
        for (String text : s.getStringList("effects")) {
            String[] p = text.split(":");
            PotionEffectType effect = org.bukkit.Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(p[0].toLowerCase(Locale.ROOT)));
            if (effect == null) throw new IllegalArgumentException("unknown effect " + p[0]);
            effects.add(new PotionEffect(effect, PotionEffect.INFINITE_DURATION, p.length > 1 ? Integer.parseInt(p[1]) : 0, true, false));
        }
        List<Trait> traits = new ArrayList<>();
        for (String t : s.getStringList("traits")) traits.add(Trait.valueOf(t.toUpperCase(Locale.ROOT)));

        return new MobDef(id, s.getString("name", id), type, s.getDouble("health", 0), s.getDouble("damage", 0),
                s.getDouble("speed", 1), gear, effects, s.getString("tier", "default"),
                s.getDouble("trait-chance", 0), traits, s.getBoolean("baby", false));
    }

    private static void slot(ConfigurationSection s, String key, EquipmentSlot slot, Map<EquipmentSlot, Material> gear) {
        String name = s.getString(key);
        if (name == null || name.isBlank()) return;
        Material material = Material.matchMaterial(name);
        if (material == null) throw new IllegalArgumentException("unknown item " + name);
        gear.put(slot, material);
    }

    public MobDef def(String id) {
        return defs.get(id);
    }

    public Collection<String> ids() {
        return List.copyOf(defs.keySet());
    }

    // ---------------------------------------------------------------- spawning

    /**
     * Brings a mob up out of the ground at the spot. Returns null if the definition does not exist.
     * The mob cannot act or be hurt until it has fully risen.
     */
    public LivingEntity spawn(String id, Location ground, BiConsumer<LivingEntity, Player> onDeath) {
        MobDef def = defs.get(id);
        if (def == null) {
            plugin.getLogger().warning("No mob called " + id + " in mobs.yml");
            return null;
        }
        LivingEntity entity = create(def, ground);
        State state = new State(def);
        state.onDeath = onDeath;
        tracked.put(entity.getUniqueId(), state);
        emerge(entity, ground, 1, 1, () -> ready(entity, state));
        return entity;
    }

    /** Spawns the mob dressed and hidden in the ground at the spot, not yet tracked or rising. */
    public LivingEntity create(MobDef def, Location ground) {
        Location below = ground.clone().subtract(0, SINK, 0);
        return (LivingEntity) ground.getWorld().spawnEntity(below, def.type, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            LivingEntity le = (LivingEntity) e;
            hide(le);
            dress(le, def);
        });
    }

    /** Freezes and hides an entity while it is still in the ground. */
    public static void hide(LivingEntity le) {
        le.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        le.setPersistent(true);
        le.setRemoveWhenFarAway(false);
        le.setAI(false);
        le.setInvisible(true);
        le.setInvulnerable(true);
        le.setSilent(true);
        le.setCollidable(false);
        le.setCanPickupItems(false);
    }

    private void dress(LivingEntity le, MobDef def) {
        le.customName(Text.component(def.name));
        le.setCustomNameVisible(true);
        if (le instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
            zombie.setBaby(def.baby);
        } else if (le instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        } else if (le instanceof org.bukkit.entity.PiglinAbstract piglin) {
            piglin.setImmuneToZombification(true);   // or brutes turn into zombified piglins in the overworld
        }
        set(le, Attribute.MAX_HEALTH, def.health);
        if (def.health > 0) le.setHealth(def.health);
        set(le, Attribute.ATTACK_DAMAGE, def.damage);
        AttributeInstance speed = le.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && def.speed != 1) speed.setBaseValue(speed.getBaseValue() * def.speed);

        EntityEquipment eq = le.getEquipment();
        if (eq != null) {
            def.gear.forEach((slot, material) -> eq.setItem(slot, new ItemStack(material)));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot != EquipmentSlot.BODY) eq.setDropChance(slot, 0f);
            }
        }
    }

    private static void set(LivingEntity le, Attribute attribute, double value) {
        if (value <= 0) return;
        AttributeInstance instance = le.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private void ready(LivingEntity entity, State state) {
        MobDef def = state.def;
        for (PotionEffect effect : def.effects) entity.addPotionEffect(effect);
        if (!def.traits.isEmpty() && ThreadLocalRandom.current().nextDouble() < def.traitChance) {
            state.trait = def.traits.get(ThreadLocalRandom.current().nextInt(def.traits.size()));
            state.nextTraitAt = System.currentTimeMillis() + 3000;
            if (state.trait == Trait.WAR_CRY) warCry(entity);
        }
        if (entity instanceof Warden warden) {
            for (Player p : alive.get()) warden.setAnger(p, 80);
        }
    }

    private void warCry(LivingEntity entity) {
        Location at = entity.getLocation();
        fx.sound(Sound.ENTITY_RAVAGER_ROAR, at, 0.8f, 1.3f);
        fx.particle(Particle.SOUL, at.clone().add(0, 1, 0), 20, 0.6, 0.6, 0.6, 0.05);
        for (Player p : alive.get()) {
            if (p.getWorld() == at.getWorld() && p.getLocation().distanceSquared(at) <= 64) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, true, true));
            }
        }
    }

    // ---------------------------------------------------------------- emerging

    /**
     * The shared ground-emerge animation: sound, cracking particles and a slow rise, then it is released. Bosses
     * grow from one scale to another while they come up.
     */
    public void emerge(LivingEntity entity, Location ground, double fromScale, double toScale, Runnable ready) {
        Location start = ground.clone().subtract(0, SINK, 0);
        entity.teleport(start);
        setScale(entity, fromScale);
        fx.sound(Sound.ENTITY_WARDEN_DIG, ground, 1f, 0.9f);
        emerging.add(new Emerging(entity, ground, fromScale, toScale, EMERGE_STEPS, ready));
        if (emergeTask == null) {
            emergeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::emergeStep, EMERGE_PERIOD, EMERGE_PERIOD);
        }
    }

    private void emergeStep() {
        Iterator<Emerging> it = emerging.iterator();
        while (it.hasNext()) {
            Emerging e = it.next();
            if (!e.entity.isValid()) {
                it.remove();
                continue;
            }
            e.step++;
            double progress = Math.min(1, e.step / (double) e.total);
            Location at = e.ground.clone().subtract(0, SINK * (1 - progress), 0);
            at.setYaw(e.entity.getLocation().getYaw());
            e.entity.teleport(at);
            setScale(e.entity, e.fromScale + (e.toScale - e.fromScale) * progress);
            if (e.step % 2 == 0) {
                World world = e.ground.getWorld();
                BlockData floor = world.getBlockAt(e.ground).getRelative(org.bukkit.block.BlockFace.DOWN).getBlockData();
                if (floor.getMaterial().isAir()) floor = Bukkit.createBlockData(Material.STONE);
                fx.particle(Particle.BLOCK, e.ground.clone().add(0, 0.1, 0), 10, 0.4, 0.1, 0.4, 0.1, floor);
            }
            if (e.step >= e.total) {
                it.remove();
                release(e.entity);
                fx.sound(Sound.ENTITY_WARDEN_EMERGE, e.ground, 0.7f, 1.1f);
                e.ready.run();
            }
        }
        if (emerging.isEmpty() && emergeTask != null) {
            emergeTask.cancel();
            emergeTask = null;
        }
    }

    public static void release(LivingEntity le) {
        le.setAI(true);
        le.setInvisible(false);
        le.setInvulnerable(false);
        le.setSilent(false);
        le.setCollidable(true);
    }

    public static void setScale(LivingEntity le, double scale) {
        AttributeInstance a = le.getAttribute(Attribute.SCALE);
        if (a != null) a.setBaseValue(scale);
    }

    // ---------------------------------------------------------------- tracking

    /** Starts tracking an entity somebody else spawned (a boss), so death and cleanup reach it. */
    public State track(LivingEntity entity, MobDef def, BiConsumer<LivingEntity, Player> onDeath) {
        State state = new State(def);
        state.onDeath = onDeath;
        tracked.put(entity.getUniqueId(), state);
        return state;
    }

    public State state(Entity entity) {
        return tracked.get(entity.getUniqueId());
    }

    public boolean isDungeonMob(Entity entity) {
        return tracked.containsKey(entity.getUniqueId());
    }

    public void forget(UUID id) {
        tracked.remove(id);
    }

    public int count() {
        return tracked.size();
    }

    /** Called by the death listener. Returns the state, or null if it is not ours. */
    public State died(LivingEntity entity) {
        return tracked.remove(entity.getUniqueId());
    }

    public void killAll() {
        for (UUID id : List.copyOf(tracked.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        tracked.clear();
        emerging.clear();
        if (emergeTask != null) {
            emergeTask.cancel();
            emergeTask = null;
        }
    }

    // ---------------------------------------------------------------- traits and upkeep

    /** Once a second: drops mobs that vanished, and lets ranged mobs with the hurl trait throw. */
    public void tick(long now) {
        List<Runnable> after = new ArrayList<>();
        Iterator<Map.Entry<UUID, State>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            Entity entity = Bukkit.getEntity(e.getKey());
            if (entity == null || !entity.isValid()) {
                State state = e.getValue();
                it.remove();
                if (state.onDeath != null) after.add(() -> state.onDeath.accept(null, null));
                continue;
            }
            State state = e.getValue();
            if (state.trait == Trait.HURL && now >= state.nextTraitAt && entity instanceof Mob mob
                    && mob.getTarget() instanceof Player target && target.getWorld() == mob.getWorld()
                    && mob.getLocation().distanceSquared(target.getLocation()) <= 196 && mob.hasLineOfSight(target)) {
                state.nextTraitAt = now + 8000;
                Vector aim = target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()).normalize();
                Snowball ball = mob.launchProjectile(Snowball.class, aim.multiply(1.2));
                ball.getPersistentDataContainer().set(HURL, PersistentDataType.BYTE, (byte) 1);
            }
        }
        after.forEach(Runnable::run);
    }

    /** A hit by a mob with the shove trait. */
    public void shove(State state, LivingEntity attacker, LivingEntity victim, long now) {
        if (state.trait != Trait.SHOVE || now < state.nextTraitAt) return;
        state.nextTraitAt = now + 8000;
        Vector push = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (push.lengthSquared() < 1e-6) return;
        victim.setVelocity(push.normalize().multiply(0.9).setY(0.35));
    }

    /** Below half health a mob with the flicker trait disappears for a moment, enough to break a target lock. */
    public void flicker(State state, LivingEntity entity) {
        if (state.trait != Trait.FLICKER || state.flickered) return;
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        if (max == null || entity.getHealth() > max.getValue() / 2) return;
        state.flickered = true;
        entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 30 + ThreadLocalRandom.current().nextInt(20), 0, true, false));
        fx.particle(Particle.LARGE_SMOKE, entity.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
    }
}
