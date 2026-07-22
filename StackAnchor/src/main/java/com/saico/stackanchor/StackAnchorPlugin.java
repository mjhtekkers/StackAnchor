package com.saico.stackanchor;

import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StackAnchor
 *
 * Lightweight 1.8.8 plugin that:
 *  - removes hit-delay (noDamageTicks) on tracked mobs
 *  - intercepts fatal hits to decrement an internal/external stack count instead
 *    of letting the mob actually die (no death animation, no despawn)
 *  - anchors stacked mobs in place every tick so they never drift/knockback
 *
 * See StackedMobListener for the actual event handling.
 */
public class StackAnchorPlugin extends JavaPlugin {

    // Live registry of every entity currently being managed as a "stack".
    // Key: the LivingEntity instance. Value: remaining units in the stack.
    public final ConcurrentHashMap<LivingEntity, Integer> stackData = new ConcurrentHashMap<>();

    private static final Vector ZERO = new Vector(0, 0, 0);

    private final java.util.Map<String, java.lang.reflect.Field> fieldCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(new StackedMobListener(this), this);

        // Per-tick anchoring pass. This is what actually keeps stacked mobs stationary:
        // vanilla knockback is applied by NMS *after* the damage event finishes processing,
        // so zeroing velocity inside the event handler alone gets overwritten. Running the
        // zero-out every tick (rather than only on a 1-tick delay after each hit) also
        // catches drift from things like nearby explosions, piston pushes, or entity collision.
        getServer().getScheduler().runTaskTimer(this, this::anchorTick, 1L, 1L);

        getLogger().info("StackAnchor enabled - tracking types: " + getConfig().getStringList("tracked-entities"));
    }

    @Override
    public void onDisable() {
        stackData.clear();
    }

    /**
     * Empties the mob's goalSelector and targetSelector (wander, look-around,
     * attack, etc.) via NMS reflection, WITHOUT setting vanilla NoAI.
     *
     * This is deliberate: vanilla's NoAI flag also disables the entity's
     * participation in collision-push physics entirely (confirmed vanilla
     * behavior, not a Bukkit quirk - see Mojang bug MC-89583, which was filed
     * because NoAI mobs pushing the player was considered unintended). Since
     * goalSelector/targetSelector are separate from the collision/physics
     * tick, clearing them stops all autonomous behavior (wandering, turning,
     * attacking, targeting) while leaving the mob fully pushable by players
     * walking into it.
     *
     * Locked to v1_8_R3 (this server's exact NMS revision, matching
     * spigot-api 1.8.8-R0.1-SNAPSHOT in pom.xml). If this server is ever
     * moved to a different 1.8.8 build (v1_8_R1/R2), this reflection will
     * throw ClassNotFoundException and silently no-op via the catch below -
     * it will NOT crash the server, but AI will stop being disabled.
     */
    public void tryDisableAI(LivingEntity entity) {
        try {
            Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
            clearSelector(handle, "goalSelector");
            clearSelector(handle, "targetSelector");
        } catch (Exception ignored) {
            // Server isn't v1_8_R3, or field names differ - fails safe (does nothing).
        }
    }

    private void clearSelector(Object nmsEntity, String fieldName) throws Exception {
        java.lang.reflect.Field selectorField = findField(nmsEntity.getClass(), fieldName);
        if (selectorField == null) return;
        selectorField.setAccessible(true);
        Object selector = selectorField.get(nmsEntity);
        if (selector == null) return;

        // PathfinderGoalSelector's internal goal list field name is obfuscated
        // and varies by build, so instead of guessing it, clear every
        // Collection-typed field found on the selector object - this is the
        // defensive, version-tolerant approach.
        for (java.lang.reflect.Field f : selector.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object value = f.get(selector);
            if (value instanceof java.util.Collection) {
                ((java.util.Collection<?>) value).clear();
            }
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) {
        String cacheKey = clazz.getName() + "#" + name;
        java.lang.reflect.Field cached = fieldCache.get(cacheKey);
        if (cached != null) return cached;

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Field f = current.getDeclaredField(name);
                fieldCache.put(cacheKey, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private void anchorTick() {
        if (stackData.isEmpty()) return;

        Set<LivingEntity> dead = new HashSet<>();

        for (LivingEntity entity : stackData.keySet()) {
            if (entity == null || entity.isDead() || !entity.isValid()) {
                dead.add(entity);
                continue;
            }

            if (!entity.getVelocity().equals(ZERO)) {
                entity.setVelocity(ZERO);
            }

            // Defensive re-assertion in case something else on the server resets it
            // (e.g. another plugin, or a fresh chunk load).
            if (entity.getNoDamageTicks() != 0) {
                entity.setNoDamageTicks(0);
            }
            if (entity.getMaximumNoDamageTicks() != 0) {
                entity.setMaximumNoDamageTicks(0);
            }

            // Same defensive re-assertion, but for AI - this is the fix for NoAI
            // "not staying off": we no longer trust the single spawn-time call.
            tryDisableAI(entity);
        }

        for (LivingEntity gone : dead) {
            stackData.remove(gone);
        }
    }
}
