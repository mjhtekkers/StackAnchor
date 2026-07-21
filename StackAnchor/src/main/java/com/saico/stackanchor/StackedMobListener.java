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
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        if (event.getEntity() instanceof Player) return;

        LivingEntity mob = (LivingEntity) event.getEntity();
        Integer stack = plugin.stackData.get(mob);
        if (stack == null) return;

        // Sub-tick protection lock against rapid packet spam
        if (mob.hasMetadata("StackAnchor_HitLock")) {
            event.setCancelled(true);
            return;
        }
        mob.setMetadata("StackAnchor_HitLock", new FixedMetadataValue(plugin, true));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mob.isValid() && !mob.isDead()) {
                mob.removeMetadata("StackAnchor_HitLock", plugin);
            }
        });

        mob.setNoDamageTicks(0);
        mob.setMaximumNoDamageTicks(0);

        Player killer = (event.getDamager() instanceof Player) ? (Player) event.getDamager() : null;

        double damage = event.getFinalDamage();
        double currentHealth = mob.getHealth();

        // If the hit does not deplete the current health layer, let vanilla handle normal damage tracking
        if (damage < currentHealth) {
            return;
        }

        // Fatal hit on the current layer reached. Prevent actual death animation.
        event.setDamage(0);

        int remaining = stack - 1;

        if (remaining <= 0) {
            plugin.stackData.remove(mob);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!mob.isDead()) {
                    mob.setHealth(0.0);
                }
            });
            return;
        }

        plugin.stackData.put(mob, remaining);
        writeExternalStackMeta(mob, remaining);

        spawnDrops(mob, killer);

        mob.setHealth(mob.getMaxHealth());
        mob.setFireTicks(0);
        mob.setVelocity(new Vector(0, 0, 0));
        mob.setNoDamageTicks(0);
    }

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

        String commandToRun = null;
        if (typeName.equals("SHEEP")) {
            commandToRun = "serveritem drop sheephead 1 " + worldName + " " + x + " " + y + " " + z;
        } else if (typeName.equals("PIG")) {
            commandToRun = "serveritem drop pighead 1 " + worldName + " " + x + " " + y + " " + z;
        }

        if (commandToRun != null) {
            org.bukkit.command.CommandSender silentSender = new org.bukkit.command.CommandSender() {
                @Override public void sendMessage(String message) {}
                @Override public void sendMessage(String[] messages) {}
                @Override public org.bukkit.Server getServer() { return Bukkit.getServer(); }
                @Override public String getName() { return "StackAnchorSilent"; }
                @Override public boolean isOp() { return true; }
                @Override public void setOp(boolean value) {}
                @Override public void sendRawMessage(String message) {}
                @Override public boolean isPermissionSet(String name) { return true; }
                @Override public boolean isPermissionSet(org.bukkit.permissions.Permission perm) { return true; }
                @Override public boolean hasPermission(String name) { return true; }
                @Override public boolean hasPermission(org.bukkit.permissions.Permission perm) { return true; }
                @Override public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) { return null; }
                @Override public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) { return null; }
                @Override public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) { return null; }
                @Override public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) { return null; }
                @Override public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) {}
                @Override public void recalculatePermissions() {}
                @Override public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() { return java.util.Collections.emptySet(); }
            };
            Bukkit.dispatchCommand(silentSender, commandToRun);
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
