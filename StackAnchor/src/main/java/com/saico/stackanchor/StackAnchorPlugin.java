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

    private java.lang.reflect.Method setAiMethod;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            setAiMethod = LivingEntity.class.getMethod("setAI", boolean.class);
        } catch (NoSuchMethodException ex) {
            setAiMethod = null;
        }

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
     * Disables vanilla AI/pathfinding via reflection (safe no-op on servers where
     * setAI doesn't exist). Called once on spawn AND re-asserted every tick from
     * anchorTick(), since nothing else guarantees AI stays off forever - a chunk
     * reload, another plugin, or an NMS-side reset after setHealth can silently
     * re-enable it otherwise.
     */
    public void tryDisableAI(LivingEntity entity) {
        if (setAiMethod == null) return;
        try {
            setAiMethod.invoke(entity, false);
        } catch (Exception ignored) {}
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
