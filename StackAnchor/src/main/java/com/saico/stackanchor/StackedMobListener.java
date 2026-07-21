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
            setAiMethod = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (!trackedTypes.contains(entity.getType())) return;

        entity.setMaximumNoDamageTicks(0);
        entity.setNoDamageTicks(0);

        tryDisableAI(entity);

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

        // Zero hit-delay per swing so every hit registers instantly without invulnerability frames
        mob.setNoDamageTicks(0);
        mob.setMaximumNoDamageTicks(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (!trackedTypes.contains(mob.getType())) return;

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
            plugin.stackData.remove(mob);
            Player killer = mob.getKiller();
            spawnDrops(mob, killer);
            return;
        }

        // Intermediate layer: decrement stack count by 1, write metadata, drop EXACTLY 1 layer of loot
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

    private void tryDisableAI(LivingEntity entity) {
        if (setAiMethod == null) return;
        try {
            setAiMethod.invoke(entity, false);
        } catch (Exception ignored) {}
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
