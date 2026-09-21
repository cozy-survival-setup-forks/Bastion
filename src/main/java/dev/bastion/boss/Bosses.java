package dev.bastion.boss;

import dev.bastion.mob.Mobs;
import dev.bastion.mob.Mobs.MobDef;
import dev.bastion.util.Fx;
import dev.bastion.util.TaskBag;
import dev.bastion.util.Text;
import dev.bastion.world.Points;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Warden;
import org.bukkit.GameMode;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The mini-boss and the final boss. Both are a vanilla entity grown with the scale attribute, with a bossbar and a
 * weighted-random set of timed abilities picked from bosses.yml. Abilities are telegraphed, damage is capped so a
 * scripted hit can hurt but never kill, and everything is driven from one shared tick.
 */
public final class Bosses {

    public record AbilityDef(String id, int weight, long cooldownMs, ConfigurationSection params) {
    }

    public record BossDef(String id, MobDef mob, double scale, BossBar.Color color, BossBar.Overlay overlay,
                          List<AbilityDef> abilities, long gapMinMs, long gapMaxMs, double lowHp, double lowHpScale,
                          boolean dramaticDeath, double damageCap) {
    }

    /** Callbacks the dungeon gives a fight. */
    public interface Events {
        void lowHealth(BossFight fight);

        /** The boss is dead (after the death animation, if it has one). */
        void died(BossFight fight, Player lastHit, Location where);
    }

    public final class BossFight {
        public final BossDef def;
        public final LivingEntity entity;
        public final BossBar bar;
        final Events events;
        final Map<String, Long> cooldowns = new HashMap<>();
        final Set<String> usedOnce = new HashSet<>();
        final Set<UUID> shown = new HashSet<>();
        long nextAbilityAt;
        long busyUntil;
        boolean dying, lowTriggered, over;
        String forced;
        Player lastHit;
        float lastProgress = -1;

        BossFight(BossDef def, LivingEntity entity, BossBar bar, Events events) {
            this.def = def;
            this.entity = entity;
            this.bar = bar;
            this.events = events;
        }

        public boolean active() {
            return !over && entity.isValid();
        }
    }

    private final Plugin plugin;
    private final Mobs mobs;
    private final Points points;
    private final Fx fx;
    private final TaskBag tasks;
    private final Supplier<Collection<Player>> alive;
    private final Map<String, BossDef> defs = new LinkedHashMap<>();
    private final List<BossFight> fights = new ArrayList<>();

    public Bosses(Plugin plugin, Mobs mobs, Points points, Fx fx, TaskBag tasks, Supplier<Collection<Player>> alive) {
        this.plugin = plugin;
        this.mobs = mobs;
        this.points = points;
        this.fx = fx;
        this.tasks = tasks;
        this.alive = alive;
    }

    // ---------------------------------------------------------------- config

    public void load() {
        defs.clear();
        File file = new File(plugin.getDataFolder(), "bosses.yml");
        if (!file.exists()) plugin.saveResource("bosses.yml", false);
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("bosses");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            try {
                defs.put(id, parse(id, s));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("bosses.yml: " + id + " is broken (" + e.getMessage() + "), skipping it");
            }
        }
    }

    private static BossDef parse(String id, ConfigurationSection s) {
        List<AbilityDef> abilities = new ArrayList<>();
        ConfigurationSection list = s.getConfigurationSection("abilities");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                ConfigurationSection a = list.getConfigurationSection(key);
                if (a != null) abilities.add(new AbilityDef(key.toLowerCase(Locale.ROOT), Math.max(1, a.getInt("weight", 1)),
                        (long) (a.getDouble("cooldown", 15) * 1000), a));
            }
        }
        ConfigurationSection bar = s.getConfigurationSection("bossbar");
        return new BossDef(id, Mobs.parse(id, s), s.getDouble("scale", 1),
                BossBar.Color.valueOf((bar == null ? "RED" : bar.getString("color", "RED")).toUpperCase(Locale.ROOT)),
                BossBar.Overlay.valueOf((bar == null ? "PROGRESS" : bar.getString("style", "PROGRESS")).toUpperCase(Locale.ROOT)),
                abilities, (long) (s.getDouble("ability-gap-min", 5) * 1000), (long) (s.getDouble("ability-gap-max", 9) * 1000),
                s.getDouble("low-health", 0.3), s.getDouble("low-health-scale", 1.1), s.getBoolean("dramatic-death", false),
                s.getDouble("damage-cap", 6));
    }

    public BossDef def(String id) {
        return defs.get(id);
    }

    public List<BossFight> fights() {
        return fights;
    }

    public boolean isBoss(Entity entity) {
        for (BossFight f : fights) if (f.entity.getUniqueId().equals(entity.getUniqueId())) return true;
        return false;
    }

    public BossFight fightOf(Entity entity) {
        for (BossFight f : fights) if (f.entity.getUniqueId().equals(entity.getUniqueId())) return f;
        return null;
    }

    // ---------------------------------------------------------------- starting

    /** Brings the boss up out of the ground at the spot, growing as it rises. Returns null if it is not defined. */
    public BossFight spawn(String id, Location ground, Events events) {
        BossDef def = defs.get(id);
        if (def == null) {
            plugin.getLogger().warning("No boss called " + id + " in bosses.yml");
            return null;
        }
        LivingEntity entity = mobs.create(def.mob, ground);
        BossBar bar = BossBar.bossBar(Text.component(def.mob.name()), 1f, def.color, def.overlay);
        BossFight fight = new BossFight(def, entity, bar, events);
        fights.add(fight);
        fight.busyUntil = Long.MAX_VALUE;   // no abilities until it has risen
        mobs.track(entity, def.mob, (dead, killer) -> {
            if (dead == null) return;
            finish(fight, killer, dead.getLocation());
        });
        mobs.emerge(entity, ground, def.scale * 0.5, def.scale, () -> {
            long now = System.currentTimeMillis();
            fight.busyUntil = 0;
            fight.nextAbilityAt = now + 4000;
            if (entity instanceof Warden warden) for (Player p : alive.get()) warden.setAnger(p, 150);
        });
        return fight;
    }

    // ---------------------------------------------------------------- the shared tick

    /** Called four or five times a second. */
    public void tick(long now) {
        for (BossFight fight : List.copyOf(fights)) {
            if (fight.over) continue;
            if (!fight.entity.isValid()) {
                fights.remove(fight);
                hideBar(fight);
                continue;
            }
            updateBar(fight);
            if (fight.dying || now < fight.busyUntil) continue;

            AttributeInstance max = fight.entity.getAttribute(Attribute.MAX_HEALTH);
            if (!fight.lowTriggered && max != null && fight.entity.getHealth() <= max.getValue() * fight.def.lowHp) {
                fight.lowTriggered = true;
                Mobs.setScale(fight.entity, fight.def.scale * fight.def.lowHpScale);
                fight.events.lowHealth(fight);
            }
            if (now >= fight.nextAbilityAt) runAbility(fight, now);
        }
    }

    private void updateBar(BossFight fight) {
        AttributeInstance max = fight.entity.getAttribute(Attribute.MAX_HEALTH);
        float progress = max == null ? 1f : (float) Math.max(0, Math.min(1, fight.entity.getHealth() / max.getValue()));
        // only send when it moved enough to be seen
        if (Math.abs(progress - fight.lastProgress) >= 0.005f) {
            fight.lastProgress = progress;
            fight.bar.progress(progress);
        }
        Set<UUID> now = new HashSet<>();
        for (Player p : alive.get()) {
            now.add(p.getUniqueId());
            if (fight.shown.add(p.getUniqueId())) p.showBossBar(fight.bar);
        }
        for (UUID id : List.copyOf(fight.shown)) {
            if (!now.contains(id)) {
                fight.shown.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(fight.bar);
            }
        }
    }

    private void hideBar(BossFight fight) {
        for (UUID id : fight.shown) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(fight.bar);
        }
        fight.shown.clear();
    }

    // ---------------------------------------------------------------- damage and death

    public void hit(BossFight fight, Entity damager) {
        Entity source = damager instanceof org.bukkit.entity.Projectile p && p.getShooter() instanceof Entity e ? e : damager;
        if (source instanceof Player player) fight.lastHit = player;
    }

    /**
     * True if the event was a killing blow the death animation took over, so the caller should cancel it.
     */
    public boolean intercept(BossFight fight, double finalDamage) {
        if (!fight.def.dramaticDeath || fight.dying || fight.entity.getHealth() - finalDamage > 0) return false;
        fight.dying = true;
        deathSequence(fight);
        return true;
    }

    private void deathSequence(BossFight fight) {
        LivingEntity e = fight.entity;
        e.setHealth(1);
        e.setInvulnerable(true);
        e.setAI(false);
        e.setGlowing(true);
        Location base = e.getLocation();
        fx.sound(Sound.ENTITY_WARDEN_ROAR, base, 2f, 0.6f);
        int[] step = {0};
        tasks.every(2, () -> {
            if (!e.isValid()) return false;
            int n = ++step[0];
            Location shake = base.clone().add((Math.random() - 0.5) * 0.3, 0, (Math.random() - 0.5) * 0.3);
            e.teleport(shake);
            if (n > 45) step[0] = 30;   // a safety net: never run longer than the sequence should
            Location center = base.clone().add(0, e.getHeight() / 2, 0);
            fx.particle(Particle.EXPLOSION, center, 3, 1.5, 1.5, 1.5, 0.05);
            fx.particle(Particle.DRAGON_BREATH, center, 30, 1.2, 1.5, 1.2, 0.04);
            if (n == 10 || n == 20 || n == 26) {
                Location strike = base.clone().add((Math.random() - 0.5) * 8, 0, (Math.random() - 0.5) * 8);
                base.getWorld().strikeLightningEffect(strike);
            }
            if (n >= 30) {
                fx.particle(Particle.EXPLOSION_EMITTER, center, 6, 1.5, 1.5, 1.5, 0);
                fx.sound(Sound.ENTITY_GENERIC_EXPLODE, base, 2f, 0.7f);
                fx.sound(Sound.ENTITY_ENDER_DRAGON_DEATH, base, 1.5f, 1.2f);
                Player killer = fight.lastHit;
                mobs.forget(e.getUniqueId());
                e.remove();
                fight.over = true;
                fights.remove(fight);
                hideBar(fight);
                fight.events.died(fight, killer, base);
                return false;
            }
            return true;
        });
    }

    private void finish(BossFight fight, Player killer, Location where) {
        fight.over = true;
        fights.remove(fight);
        hideBar(fight);
        fight.events.died(fight, killer != null ? killer : fight.lastHit, where);
    }

    /** The run ran out of time: the boss slips away in smoke, without a death animation. */
    public void escape(BossFight fight) {
        Location at = fight.entity.getLocation();
        fx.particle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 80, 1.5, 2, 1.5, 0.05);
        fx.particle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0, 1, 0), 30, 1, 2, 1, 0.02);
        fx.sound(Sound.ENTITY_ENDERMAN_TELEPORT, at, 1.5f, 0.5f);
        mobs.forget(fight.entity.getUniqueId());
        fight.entity.remove();
        fight.over = true;
        fights.remove(fight);
        hideBar(fight);
    }

    public void stopAll() {
        for (BossFight fight : List.copyOf(fights)) {
            hideBar(fight);
            fight.over = true;
        }
        fights.clear();
    }

    // ---------------------------------------------------------------- final boss entrance

    /**
     * The gathering storm: a few seconds of dread, then lightning converging on the throne from every direction
     * (visual only, plus a small capped hit to anyone standing at a strike), then {@code after} runs.
     */
    public void storm(Location throne, double cap, Runnable after) {
        World world = throne.getWorld();
        fx.sound(Sound.AMBIENT_CAVE, 1.5f, 0.5f);
        for (int i = 0; i < 6; i++) {
            long delay = i * 10L;
            tasks.later(delay + 1, () -> {
                fx.particle(Particle.CAMPFIRE_COSY_SMOKE, throne.clone().add(0, 1, 0), 40, 6, 2, 6, 0.02);
                fx.sound(Sound.ENTITY_WARDEN_HEARTBEAT, throne, 1.5f, 0.6f + 0.05f * (float) delay / 10);
            });
        }
        int strikes = 8;
        for (int i = 0; i < strikes; i++) {
            double angle = i * Math.PI * 2 / strikes;
            double radius = 12 - i * 1.2;   // a ring that shrinks as it goes
            long delay = 70 + i * 4L;
            tasks.later(delay, () -> {
                Location at = throne.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                world.strikeLightningEffect(at);
                for (Player p : alive.get()) {
                    if (p.getWorld() == world && p.getLocation().distanceSquared(at) <= 6.25) hurt(p, 3, null, cap);
                }
            });
        }
        tasks.later(70 + strikes * 4L + 6, () -> {
            world.strikeLightningEffect(throne);
            fx.sound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, throne, 3f, 0.6f);
            after.run();
        });
    }

    // ---------------------------------------------------------------- abilities

    private void runAbility(BossFight fight, long now) {
        LivingEntity boss = fight.entity;
        Player target = target(boss);
        if (target == null) {
            fight.nextAbilityAt = now + 1500;
            return;
        }
        AbilityDef pick = null;
        if (fight.forced != null) {
            for (AbilityDef a : fight.def.abilities) if (a.id.equals(fight.forced)) pick = a;
            fight.forced = null;
        }
        if (pick == null) {
            List<AbilityDef> ready = new ArrayList<>();
            int total = 0;
            for (AbilityDef a : fight.def.abilities) {
                if (fight.cooldowns.getOrDefault(a.id, 0L) > now) continue;
                if (a.params.getBoolean("once", false) && fight.usedOnce.contains(a.id)) continue;
                if (a.id.equals("reposition") && points.get("boss").size() < 2) continue;
                ready.add(a);
                total += a.weight;
            }
            if (ready.isEmpty()) {
                fight.nextAbilityAt = now + 1500;
                return;
            }
            int roll = ThreadLocalRandom.current().nextInt(total);
            for (AbilityDef a : ready) {
                roll -= a.weight;
                if (roll < 0) {
                    pick = a;
                    break;
                }
            }
        }
        fight.cooldowns.put(pick.id, now + pick.cooldownMs);
        if (pick.params.getBoolean("once", false)) fight.usedOnce.add(pick.id);
        long gap = fight.def.gapMinMs + (long) (ThreadLocalRandom.current().nextDouble() * Math.max(0, fight.def.gapMaxMs - fight.def.gapMinMs));
        long busy = perform(fight, pick, target);
        fight.busyUntil = now + busy;
        fight.nextAbilityAt = now + busy + gap;
    }

    private Player target(LivingEntity boss) {
        if (boss instanceof Mob mob && mob.getTarget() instanceof Player p && alive.get().contains(p)) return p;
        Player best = null;
        double bestDistance = 900;
        for (Player p : alive.get()) {
            if (p.getWorld() != boss.getWorld() || p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(boss.getLocation());
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

    /** Starts the ability and returns how many milliseconds it keeps the boss busy. */
    private long perform(BossFight fight, AbilityDef a, Player target) {
        LivingEntity boss = fight.entity;
        ConfigurationSection p = a.params;
        double cap = fight.def.damageCap;
        switch (a.id) {
            case "arrow_rain" -> {
                arrowRain(fight, target, p, cap);
                return 3500;
            }
            case "vanish" -> {
                int seconds = (int) (p.getDouble("min", 5) + ThreadLocalRandom.current().nextDouble() * (p.getDouble("max", 10) - p.getDouble("min", 5)));
                boss.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, seconds * 20, 0, true, false));
                for (int i = 0; i < seconds; i++) {
                    tasks.later(i * 20L + 10, () -> {
                        if (boss.isValid()) fx.sound(Sound.ENTITY_WARDEN_STEP, boss.getLocation(), 0.5f, 0.7f);
                    });
                }
                return 1000;
            }
            case "fireballs" -> {
                int count = p.getInt("count", 3);
                for (int i = 0; i < count; i++) {
                    tasks.later(10L + i * 8L, () -> {
                        if (!boss.isValid() || !target.isOnline()) return;
                        Location from = boss.getEyeLocation();
                        Vector aim = target.getEyeLocation().toVector().subtract(from.toVector()).normalize();
                        SmallFireball ball = boss.getWorld().spawn(from.clone().add(aim.clone().multiply(1.5)), SmallFireball.class);
                        ball.setShooter(boss);
                        ball.setIsIncendiary(false);
                        ball.setYield(0);
                        ball.setDirection(aim);
                        fx.sound(Sound.ENTITY_BLAZE_SHOOT, from, 1f, 0.7f);
                    });
                }
                return 10L * 50 + count * 8L * 50;
            }
            case "thunder_strike" -> {
                Location at = target.getLocation();
                telegraphRing(at, 2.5, 20);
                tasks.later(20, () -> {
                    at.getWorld().strikeLightningEffect(at);
                    for (Player pl : alive.get()) {
                        if (pl.getWorld() == at.getWorld() && pl.getLocation().distanceSquared(at) <= 6.25) hurt(pl, p.getDouble("damage", 6), boss, cap);
                    }
                });
                return 1500;
            }
            case "reposition" -> {
                reposition(fight);
                return 3500;
            }
            case "sonic_shriek" -> {
                sonic(fight, target, p, cap);
                return 2500;
            }
            case "summon_wraiths", "summon_vex" -> {
                summon(fight, p);
                return 2000;
            }
            case "ground_slam" -> {
                slam(fight, p, cap);
                return 1800;
            }
            case "chain_grasp" -> {
                fx.sound(Sound.BLOCK_CHAIN_BREAK, target.getLocation(), 1.5f, 0.6f);
                fx.particle(Particle.CRIT, target.getLocation().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.2);
                int ticks = (int) (p.getDouble("seconds", 2) * 20);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, true, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, true, false));
                String next = p.getString("then", "arrow_rain");
                fight.forced = next;
                return (long) ticks * 50;
            }
            case "warning_volley" -> {
                volley(fight, target, p);
                return 2000;
            }
            default -> {
                plugin.getLogger().warning("bosses.yml: unknown ability " + a.id);
                return 1000;
            }
        }
    }

    private void arrowRain(BossFight fight, Player target, ConfigurationSection p, double cap) {
        LivingEntity boss = fight.entity;
        for (Player pl : alive.get()) {
            pl.showTitle(Title.title(Component.empty(), Text.component("<dark_gray>The sky darkens..."),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1200), Duration.ofMillis(400))));
        }
        fx.sound(Sound.ENTITY_WARDEN_ROAR, boss.getLocation(), 1f, 1.4f);
        Location center = target.getLocation();
        double radius = p.getDouble("radius", 4);
        int waves = p.getInt("waves", 8);
        int perWave = p.getInt("per-wave", 2);
        for (int w = 0; w < waves; w++) {
            tasks.later(30L + w * 5L, () -> {
                if (!boss.isValid()) return;
                for (int i = 0; i < perWave; i++) {
                    double angle = Math.random() * Math.PI * 2;
                    double r = Math.sqrt(Math.random()) * radius;
                    Location from = center.clone().add(Math.cos(angle) * r, 12, Math.sin(angle) * r);
                    Arrow arrow = center.getWorld().spawnArrow(from, new Vector(0, -1, 0), 1.5f, 0f);
                    arrow.setShooter(boss);
                    arrow.setDamage(p.getDouble("damage", 1.2));
                    arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    tasks.later(60, arrow::remove);
                }
            });
        }
    }

    private void telegraphRing(Location at, double radius, int ticks) {
        for (int t = 0; t < ticks; t += 4) {
            tasks.later(Math.max(1, t), () -> {
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8;
                    fx.particle(Particle.ELECTRIC_SPARK, at.clone().add(Math.cos(a) * radius, 0.1, Math.sin(a) * radius), 1, 0, 0, 0, 0);
                }
            });
        }
    }

    private void reposition(BossFight fight) {
        LivingEntity boss = fight.entity;
        List<Points.Spot> spots = new ArrayList<>(points.get("boss"));
        Location here = boss.getLocation();
        spots.removeIf(s -> s.at() == null || s.at().distanceSquared(here) < 16);
        if (spots.isEmpty()) return;
        Location to = spots.get(ThreadLocalRandom.current().nextInt(spots.size())).at();
        boss.setAI(false);
        boss.setInvulnerable(true);
        fx.sound(Sound.ENTITY_WARDEN_DIG, here, 1.5f, 0.7f);
        int[] step = {0};
        tasks.every(2, () -> {
            if (!boss.isValid()) return false;
            step[0]++;
            Location down = boss.getLocation().subtract(0, 0.3, 0);
            boss.teleport(down);
            fx.particle(Particle.LARGE_SMOKE, here.clone().add(0, 0.2, 0), 6, 0.8, 0.1, 0.8, 0.02);
            if (step[0] < 8) return true;
            Mobs.hide(boss);
            mobs.emerge(boss, to, fight.def.scale, fight.def.scale, () -> boss.setInvulnerable(false));
            return false;
        });
    }

    private void sonic(BossFight fight, Player target, ConfigurationSection p, double cap) {
        LivingEntity boss = fight.entity;
        Vector dir = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) dir = boss.getLocation().getDirection().setY(0);
        Vector aim = dir.normalize();
        boss.setAI(false);
        fx.sound(Sound.ENTITY_WARDEN_SONIC_CHARGE, boss.getLocation(), 2f, 1f);
        double range = p.getDouble("range", 16);
        // the wind-up shows the line it will hit, so it can be dodged by stepping off it
        for (int t = 0; t < 24; t += 6) {
            tasks.later(Math.max(1, t), () -> {
                for (double d = 2; d <= range; d += 2) {
                    fx.particle(Particle.SCULK_CHARGE_POP, boss.getLocation().add(aim.clone().multiply(d)).add(0, 1, 0), 1, 0.1, 0.1, 0.1, 0);
                }
            });
        }
        tasks.later(26, () -> {
            if (!boss.isValid()) return;
            boss.setAI(true);
            Location from = boss.getEyeLocation();
            fx.sound(Sound.ENTITY_WARDEN_SONIC_BOOM, from, 3f, 1f);
            for (double d = 2; d <= range; d += 2) {
                fx.particle(Particle.SONIC_BOOM, from.clone().add(aim.clone().multiply(d)), 1, 0, 0, 0, 0);
            }
            double cos = Math.cos(Math.toRadians(p.getDouble("angle", 25)));
            for (Player pl : alive.get()) {
                if (pl.getWorld() != from.getWorld()) continue;
                Vector to = pl.getLocation().toVector().subtract(boss.getLocation().toVector());
                double distance = to.length();
                if (distance > range || distance < 0.01) continue;
                if (to.clone().setY(0).normalize().dot(aim) >= cos) {
                    pl.setVelocity(aim.clone().multiply(1.6).setY(0.5));
                    hurt(pl, p.getDouble("damage", 8), boss, cap);
                }
            }
        });
    }

    private void slam(BossFight fight, ConfigurationSection p, double cap) {
        LivingEntity boss = fight.entity;
        double radius = p.getDouble("radius", 5);
        boss.setAI(false);
        fx.sound(Sound.ENTITY_RAVAGER_ROAR, boss.getLocation(), 1.5f, 0.6f);
        for (int t = 0; t < 16; t += 4) {
            int step = t;
            tasks.later(Math.max(1, t), () -> {
                double r = radius * (step + 4) / 16.0;
                for (int i = 0; i < 20; i++) {
                    double a = i * Math.PI / 10;
                    fx.particle(Particle.CRIT, boss.getLocation().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0);
                }
            });
        }
        tasks.later(20, () -> {
            if (!boss.isValid()) return;
            boss.setAI(true);
            Location at = boss.getLocation();
            fx.particle(Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 6, radius / 2, 0.2, radius / 2, 0);
            fx.sound(Sound.ENTITY_GENERIC_EXPLODE, at, 1.5f, 0.6f);
            for (Player pl : alive.get()) {
                if (pl.getWorld() != at.getWorld() || pl.getLocation().distanceSquared(at) > radius * radius) continue;
                Vector away = pl.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
                pl.setVelocity(away.normalize().multiply(p.getDouble("knockback", 1.4)).setY(0.6));
                int fatigue = p.getInt("fatigue-seconds", 0);
                if (fatigue > 0) pl.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, fatigue * 20, 1, true, true));
                hurt(pl, p.getDouble("damage", 5), boss, cap);
            }
        });
    }

    private void summon(BossFight fight, ConfigurationSection p) {
        LivingEntity boss = fight.entity;
        String mobId = p.getString("mob", "bound_wraith");
        int count = p.getInt("count", 2);
        fx.sound(Sound.ENTITY_EVOKER_PREPARE_SUMMON, boss.getLocation(), 1.5f, 0.8f);
        for (int i = 0; i < count; i++) {
            double angle = (i + Math.random()) * Math.PI * 2 / count;
            Location at = boss.getLocation().add(Math.cos(angle) * 4, 0, Math.sin(angle) * 4);
            at.setY(boss.getLocation().getY() + p.getDouble("height", 0));
            mobs.spawn(mobId, at, null);
        }
    }

    private void volley(BossFight fight, Player target, ConfigurationSection p) {
        LivingEntity boss = fight.entity;
        fx.sound(Sound.ENTITY_SKELETON_SHOOT, boss.getLocation(), 1.5f, 0.6f);
        Vector aim = target.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector()).normalize();
        for (int i = 0; i < p.getInt("arrows", 5); i++) {
            Vector spread = aim.clone().add(new Vector((Math.random() - 0.5) * 0.25, (Math.random() - 0.5) * 0.1, (Math.random() - 0.5) * 0.25)).normalize();
            Arrow arrow = boss.getWorld().spawnArrow(boss.getEyeLocation(), spread, 1.6f, 0f);
            arrow.setShooter(boss);
            arrow.setDamage(p.getDouble("damage", 1.0));
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            tasks.later(80, arrow::remove);
        }
    }

    /**
     * A scripted hit: it hurts (with the hurt animation) but takes at most {@code cap} health and never the last
     * point, however good the armour or however weak the player.
     */
    public static void hurt(Player player, double amount, LivingEntity source, double cap) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        double damage = Math.min(amount, cap);
        player.setHealth(Math.max(1, player.getHealth() - damage));
        player.playHurtAnimation(0);
    }
}
