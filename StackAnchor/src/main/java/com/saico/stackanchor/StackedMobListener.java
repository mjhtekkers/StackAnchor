package com.saico.stackanchor;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.plugin.Plugin;
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

    // The real owning plugin of external-stack-metadata-key (e.g. MobStacker), resolved once
    // at startup. If found, we write metadata using ITS identity, not ours - Bukkit's metadata
    // store keys entries by owning plugin, so writing under our own identity would just sit
    // alongside MobStacker's entry rather than replacing it, and MobStacker's own .get(0) reads
    // would keep returning its stale value. Writing under its identity makes our updates visible
    // to it (and to anything else reading that key) as if MobStacker itself had set them.
    private Plugin externalOwner;

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

        if (!externalMetaKey.isEmpty()) {
            Plugin mobStacker = Bukkit.getPluginManager().getPlugin("MobStacker");
            if (mobStacker != null) {
                this.externalOwner = mobStacker;
                plugin.getLogger().info("StackAnchor: found MobStacker - will read/write '" + externalMetaKey
                        + "' metadata using MobStacker's own plugin identity so both plugins stay in sync.");
            } else {
                // Fall back to our own identity. This only actually helps if whatever plugin
                // owns this key reads metadata from ALL registered owners rather than just its
                // own - which most stacker plugins do not. If you're using a different stacker
                // plugin, tell me its exact plugin name and I'll point this at it instead.
                this.externalOwner = plugin;
                plugin.getLogger().warning("StackAnchor: external-stack-metadata-key is set to '" + externalMetaKey
                        + "' but no plugin named 'MobStacker' was found - falling back to StackAnchor's own "
                        + "metadata identity, which the external plugin likely won't see.");
            }
        }
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

        if (minHitsPerLayer > 1) {
            double cap = mob.getMaxHealth() / minHitsPerLayer;
            if (event.getDamage() > cap) {
                event.setDamage(cap);
            }
        }

        mob.setNoDamageTicks(0);
        mob.setMaximumNoDamageTicks(0);

        if (stack > 1) {
            double healthAfter = mob.getHealth() - event.getFinalDamage();

            if (healthAfter <= 0) {
                double allowedDamage = Math.max(0, mob.getHealth() - 1.0);
                event.setDamage(allowedDamage);

                int remaining = stack - 1;
                plugin.stackData.put(mob, remaining);
                writeExternalStackMeta(mob, remaining);
                updateDisplayName(mob, remaining);

                if (debugDamageLog) {
                    plugin.getLogger().info(String.format(
                        "[StackAnchor DEBUG] %s LAYER DECREMENT via onDamage: %d -> %d (quantity metadata + name updated)",
                        mob.getType(), stack, remaining));
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

        // By the time we get here for the TRUE final layer, our onDamage() writes above have
        // already kept the "quantity" metadata in sync, so MobStacker's own death listener
        // (which runs at NORMAL priority, before this HIGHEST handler) will have already read
        // the correct final count and correctly declined to spawn a replacement.

        event.getDrops().clear();
        event.setDroppedExp(0);

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
            plugin.stackData.remove(mob);
            Player killer = mob.getKiller();
            spawnDrops(mob, killer);
            if (debugDamageLog) {
                plugin.getLogger().info("[StackAnchor DEBUG] " + mob.getType() + " TRUE final kill via onDeath (stack was " + stack + ")");
            }
            return;
        }

        // Fallback path for death causes onDamage() never sees (fire, fall, drowning, etc.)
        int remaining = stack - 1;
        plugin.stackData.put(mob, remaining);
        writeExternalStackMeta(mob, remaining);
        updateDisplayName(mob, remaining);

        Player killer = mob.getKiller();
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

    private int getStackAmount(LivingEntity entity) {
        if (!externalMetaKey.isEmpty() && entity.hasMetadata(externalMetaKey)) {
            try {
                return entity.getMetadata(externalMetaKey).get(0).asInt();
            } catch (Exception ignored) {}
        }
        return Math.max(1, defaultStackSize);
    }

    private void writeExternalStackMeta(LivingEntity entity, int amount) {
        if (externalMetaKey.isEmpty() || externalOwner == null) return;
        entity.setMetadata(externalMetaKey, new FixedMetadataValue(externalOwner, amount));
    }

    // Mirrors the count in the mob's visible name on every intercepted kill, since MobStacker
    // itself only renames a mob during its own real death->respawn cycle - which we're
    // deliberately avoiding for every layer except the true last one. Format is a plain
    // "Nx Pig" style; if this doesn't match what you're used to seeing from MobStacker, send me
    // the exact format/colours you want and I'll match it precisely.
    private void updateDisplayName(LivingEntity entity, int amount) {
        if (amount <= 1) {
            entity.setCustomNameVisible(false);
            return;
        }
        String typeName = entity.getType().name();
        String display = typeName.charAt(0) + typeName.substring(1).toLowerCase();
        entity.setCustomName(ChatColor.YELLOW + String.valueOf(amount) + "x " + ChatColor.RESET + display);
        entity.setCustomNameVisible(true);
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
