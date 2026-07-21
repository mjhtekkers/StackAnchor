package com.saico.stackanchor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StackedMobListener implements Listener {

    private final StackAnchorPlugin plugin;
    private final Set<EntityType> trackedTypes = new HashSet<>();
    private final String externalMetaKey;
    private final int defaultStackSize;

    // Reflection handle for LivingEntity#setAI(boolean), which is a Spigot-API
    // addition not guaranteed to exist on every 1.8.8 build. We probe for it once
    // at startup instead of per-entity, and just skip it silently if unavailable -
    // the per-tick velocity anchor in StackAnchorPlugin covers movement either way.
    private Method setAiMethod;

    public StackedMobListener(StackAnchorPlugin plugin) {
        this.plugin = plugin;

        for (String s : plugin.getConfig().getStringList("tracked-entities")) {
            try {
                trackedTypes.add(EntityType.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown entity type in config: " + s);
            }
        }

        this.externalMetaKey = plugin.getConfig().getString("external-stack-metadata-key", "");
        this.defaultStackSize = plugin.getConfig().getInt("default-stack-size", 1);

        try {
            setAiMethod = LivingEntity.class.getMethod("setAI", boolean.class);
        } catch (NoSuchMethodException ex) {
            setAiMethod = null; // not available on this build - fine, velocity anchor still works
        }
    }

    // ------------------------------------------------------------------
    // Spawn: tag tracked entities, strip invulnerability frames, disable AI
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (!trackedTypes.contains(entity.getType())) return;

        // Requirement 1: zero hit delay from the moment the mob exists.
        entity.setMaximumNoDamageTicks(0);
        entity.setNoDamageTicks(0);

        // Requirement 3: try real AI-disable first, fall back to velocity anchoring
        // (handled every tick by StackAnchorPlugin regardless of whether this succeeds).
        tryDisableAI(entity);

        int amount = getStackAmount(entity);
        plugin.stackData.put(entity, amount);
    }

    // ------------------------------------------------------------------
    // Damage: intercept fatal hits before vanilla applies them
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        if (event.getEntity() instanceof Player) return;

        LivingEntity mob = (LivingEntity) event.getEntity();
        Integer stack = plugin.stackData.get(mob);
        if (stack == null) return; // not a tracked stacked mob, let vanilla handle it

        // Belt-and-braces: re-zero every hit in case something upstream reset it this tick.
        mob.setNoDamageTicks(0);
        mob.setMaximumNoDamageTicks(0);

        double finalDamage = event.getFinalDamage();
        double currentHealth = mob.getHealth();

        if (finalDamage < currentHealth) {
            // Non-fatal hit - let it apply normally, velocity gets re-zeroed next tick
            // by the anchoring task in StackAnchorPlugin.
            return;
        }

        // ---- Fatal hit: intercept before health hits 0 ----
        // setDamage(0) instead of setCancelled(true) so the hurt sound / red flash
        // still plays (the hit still "feels" real) but health never drops to 0,
        // so no death packet / no sideways death animation is ever sent.
        event.setDamage(0);

        int remaining = stack - 1;
        Player killer = (event.getDamager() instanceof Player) ? (Player) event.getDamager() : null;

        if (remaining <= 0) {
            // Last unit in the stack - let it actually die. Stop managing it here;
            // EntityDeathEvent below will handle real drops/xp/cleanup.
            plugin.stackData.remove(mob);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!mob.isDead()) {
                    mob.setHealth(0.0); // triggers a real, single, vanilla death - fine for the last unit
                }
            });
            return;
        }

        // Still units left: fake the kill.
        plugin.stackData.put(mob, remaining);
        writeExternalStackMeta(mob, remaining);

        spawnDrops(mob, killer);

        // Reset on the exact same tick: full health, no fire, no residual damage state.
        mob.setHealth(mob.getMaxHealth());
        mob.setFireTicks(0);
        mob.setVelocity(new Vector(0, 0, 0));
        mob.setNoDamageTicks(0);
    }

    // ------------------------------------------------------------------
    // Real death (last unit in a stack, or an untracked mob): clear vanilla
    // drops/xp and replace with the same custom drop table for consistency.
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (!trackedTypes.contains(mob.getType())) return;

        event.getDrops().clear();
        event.setDroppedExp(0);

        Player killer = mob.getKiller();
        spawnDrops(mob, killer);

        plugin.stackData.remove(mob);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void tryDisableAI(LivingEntity entity) {
        if (setAiMethod == null) return;
        try {
            setAiMethod.invoke(entity, false);
        } catch (Exception ignored) {
            // swallow - not fatal, velocity anchoring still keeps it in place
        }
    }

    /**
     * Determines the starting stack size for a freshly spawned tracked entity.
     *
     * Integration point: if you're running a real mob-stacker plugin (WildStacker,
     * RoseStacker, EntityStacker, etc.), replace the body of this method with a call
     * into its API, e.g.:
     *
     *   StackedEntity se = WildStackerAPI.getStackedEntity(entity);
     *   return se != null ? se.getStackAmount() : 1;
     *
     * By default this reads a metadata key you configure in config.yml
     * (external-stack-metadata-key), and falls back to the configured
     * default-stack-size if that metadata isn't present.
     */
    private int getStackAmount(LivingEntity entity) {
        if (!externalMetaKey.isEmpty() && entity.hasMetadata(externalMetaKey)) {
            try {
                return entity.getMetadata(externalMetaKey).get(0).asInt();
            } catch (Exception ignored) {
                // fall through to default
            }
        }
        return Math.max(1, defaultStackSize);
    }

    /** Mirrors a stack count change back onto external-stacker metadata, if configured. */
    private void writeExternalStackMeta(LivingEntity entity, int amount) {
        if (externalMetaKey.isEmpty()) return;
        entity.setMetadata(externalMetaKey, new FixedMetadataValue(plugin, amount));
    }

    private void spawnDrops(LivingEntity mob, Player killer) {
        Location loc = mob.getLocation();
        String typeName = mob.getType().name();

        List<String> entries = plugin.getConfig().getStringList("drops." + typeName);
        for (String entry : entries) {
            // format: MATERIAL:MIN:MAX  (uniform random amount between MIN and MAX inclusive)
            String[] parts = entry.split(":");
            if (parts.length != 3) continue;

            Material mat = Material.matchMaterial(parts[0]);
            if (mat == null) continue;

            int min = safeInt(parts[1], 1);
            int max = safeInt(parts[2], min);
            int amount = (max > min) ? min + (int) (Math.random() * (max - min + 1)) : min;
            if (amount <= 0) continue;

            mob.getWorld().dropItemNaturally(loc, new ItemStack(mat, amount));
        }

        if (killer != null) {
            List<String> commands = plugin.getConfig().getStringList("drop-commands");
            for (String cmd : commands) {
                String parsed = cmd
                        .replace("{player}", killer.getName())
                        .replace("{x}", String.valueOf(loc.getBlockX()))
                        .replace("{y}", String.valueOf(loc.getBlockY()))
                        .replace("{z}", String.valueOf(loc.getBlockZ()))
                        .replace("{world}", loc.getWorld().getName())
                        .replace("{type}", typeName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            }
        }
    }

    private int safeInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
