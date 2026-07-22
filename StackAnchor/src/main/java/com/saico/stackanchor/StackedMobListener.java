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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StackedMobListener implements Listener {

    private final StackAnchorPlugin plugin;
    private final Set<EntityType> trackedTypes = new HashSet<>();
    private final String externalMetaKey;
    private final int defaultStackSize;
    private final int minHitsPerLayer;
    private final boolean debugDamageLog;

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
        this.minHitsPerLayer = Math.max(1, plugin.getConfig().getInt("min-hits-per-layer", 1));
        this.debugDamageLog = plugin.getConfig().getBoolean("debug-damage-log", false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (!trackedTypes.contains(entity.getType())) return;

        entity.setMaximumNoDamageTicks(0);
        entity.setNoDamageTicks(0);

        plugin.tryDisableAI(entity);

        int amount = getStackAmount(entity);
        plugin.stackData.put(entity, amount);
        entity.setMetadata("StackAnchor_Count", new FixedMetadataValue(plugin, amount));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        if (event.getEntity() instanceof Player) return;

        LivingEntity mob = (LivingEntity) event.getEntity();
        Integer stack = plugin.stackData.get(mob);
        if (stack == null) return;

        double rawDamage = event.getDamage();
        double maxHealth = mob.getMaxHealth();
        double healthBefore = mob.getHealth();

        // Cap damage per hit to a fraction of max health so no single swing - sword,
        // axe, whatever - can one-shot a layer. This is deliberately weapon-agnostic:
        // no tool-type checks, just a flat ceiling on the damage number itself, which
        // avoids the old fragile sword-vs-shovel branching that broke before.
        double cap = maxHealth / minHitsPerLayer;
        if (minHitsPerLayer > 1 && event.getDamage() > cap) {
            event.setDamage(cap);
        }

        if (debugDamageLog) {
            plugin.getLogger().info(String.format(
                "[StackAnchor DEBUG] %s hit: rawDamage=%.2f maxHealth=%.2f cap=%.2f " +
                "healthBefore=%.2f finalDamageAfterCap=%.2f stackRemaining=%d",
                mob.getType(), rawDamage, maxHealth, cap, healthBefore, event.getFinalDamage(), stack
            ));
        }

        // Zero hit-delay per swing so every hit registers instantly without invulnerability frames
        mob.setNoDamageTicks(0);
        mob.setMaximumNoDamageTicks(0);

        // If this hit would bring health to 0 or below AND this isn't the final layer,
        // intercept it here instead of letting a real EntityDeathEvent happen. Vanilla's
        // death-flop animation plays the instant health hits 0, client-side, before any
        // of our revival code runs - reviving the mob a tick later can't undo an
        // animation that's already played. Spam-killing intermediate layers this way
        // is what causes the repeated spin/twitch. Clamping health to stay above 0 for
        // every non-final layer means vanilla's death code (and its animation) is never
        // triggered at all except for the true last layer.
        if (stack > 1) {
            double healthAfter = mob.getHealth() - event.getFinalDamage();
            if (healthAfter <= 0) {
                double allowedDamage = Math.max(0, mob.getHealth() - 1.0);
                event.setDamage(allowedDamage);

                int remaining = stack - 1;
                plugin.stackData.put(mob, remaining);
                writeExternalStackMeta(mob, remaining);

                if (debugDamageLog) {
                    plugin.getLogger().info(String.format(
                        "[StackAnchor DEBUG] LAYER DECREMENT fired: %s stack %d -> %d",
                        mob.getType(), stack, remaining
                    ));
                }

                Player killer = (event.getDamager() instanceof Player) ? (Player) event.getDamager() : null;
                spawnDrops(mob, killer);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (mob.isValid()) {
                        mob.setHealth(mob.getMaxHealth());
                        mob.setFireTicks(0);
                        mob.setVelocity(new Vector(0, 0, 0));
                        mob.setNoDamageTicks(0);
                    }
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (!trackedTypes.contains(mob.getType())) return;

        // NOTE: for damage dealt by another entity (sword hits, etc.), onDamage()
        // above now intercepts intermediate layers before real death ever occurs,
        // to avoid the vanilla death-flop animation replaying on every hit. So this
        // method's "intermediate layer" branch below now mainly exists as a fallback
        // for death causes onDamage() doesn't see - fire, fall damage, drowning,
        // poison, etc. (plain EntityDamageEvent, not EntityDamageByEntityEvent).
        // The true final-layer kill (stack <= 1) always comes through here regardless
        // of cause, since we deliberately let that last death happen for real.

        // 1. Wipe default vanilla drops completely to avoid native duplicate item injection
        event.getDrops().clear();
        event.setDroppedExp(0);

        // 2. Strict anti-duplication lock: prevents Spigot from firing duplicate death packets
        // on the exact same tick, ensuring spawnDrops only triggers once per layer break.
        if (mob.hasMetadata("StackAnchor_DropLock")) {
            return;
        }
        mob.setMetadata("StackAnchor_DropLock", new FixedMetadataValue(plugin, true));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mob.isValid()) {
                mob.removeMetadata("StackAnchor_DropLock", plugin);
            }
        });

        Integer stack = plugin.stackData.get(mob);
        if (stack == null || stack <= 1) {
            // Last unit dying for real - clean up registry and drop 1 final layer of items
            if (debugDamageLog) {
                plugin.getLogger().info(String.format(
                    "[StackAnchor DEBUG] onDeath FINAL kill fired: %s (stack was %s)",
                    mob.getType(), stack
                ));
            }
            plugin.stackData.remove(mob);
            Player killer = mob.getKiller();
            spawnDrops(mob, killer);
            return;
        }

        // Intermediate layer: decrement stack count by 1, write metadata, drop EXACTLY 1 layer of loot
        if (debugDamageLog) {
            plugin.getLogger().info(String.format(
                "[StackAnchor DEBUG] onDeath INTERMEDIATE kill fired (fallback path - " +
                "onDamage's interception should normally catch this instead): %s stack=%d",
                mob.getType(), stack
            ));
        }
        int remaining = stack - 1;
        plugin.stackData.put(mob, remaining);
        writeExternalStackMeta(mob, remaining);

        Player killer = mob.getKiller();
        spawnDrops(mob, killer);

        // Revive / reset the mob health back to full for the next stack unit on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mob.isValid()) {
                mob.setHealth(mob.getMaxHealth());
                mob.setFireTicks(0);
                mob.setVelocity(new Vector(0, 0, 0));
                mob.setNoDamageTicks(0);
            }
        });
    }

    private int getStackAmount(LivingEntity entity) {
        if (!externalMetaKey.isEmpty() && entity.hasMetadata(externalMetaKey)) {
            try {
                return entity.getMetadata(externalMetaKey).get(0).asInt();
            } catch (Exception ignored) {}
        }
        return Math.max(1, defaultStackSize);
    }

    private void writeExternalStackMeta(LivingEntity entity, int amount) {
        if (externalMetaKey.isEmpty()) return;
        entity.setMetadata(externalMetaKey, new FixedMetadataValue(plugin, amount));
    }

    private void spawnDrops(LivingEntity mob, Player killer) {
        Location loc = mob.getLocation();
        String typeName = mob.getType().name();

        List<String> entries = plugin.getConfig().getStringList("drops." + typeName);
        for (String entry : entries) {
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

        String worldName = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        if (typeName.equals("SHEEP")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "serveritem drop sheephead 1 " + worldName + " " + x + " " + y + " " + z);
        } else if (typeName.equals("PIG")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "serveritem drop pighead 1 " + worldName + " " + x + " " + y + " " + z);
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
