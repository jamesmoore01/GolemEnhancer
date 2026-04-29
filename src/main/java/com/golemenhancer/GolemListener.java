package com.golemenhancer;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GolemListener implements Listener {

    private final Plugin plugin;

    private final NamespacedKey KEY_ITEM_TYPE;
    private final NamespacedKey KEY_CHEST_WORLD;
    private final NamespacedKey KEY_CHEST_X;
    private final NamespacedKey KEY_CHEST_Y;
    private final NamespacedKey KEY_CHEST_Z;

    private final Map<UUID, Material> lastHeld = new HashMap<>();
    private final Map<UUID, String> pendingChest = new HashMap<>();
    private final Map<UUID, String> pendingItem = new HashMap<>();

    public GolemListener(Plugin plugin) {
        this.plugin = plugin;

        KEY_ITEM_TYPE   = new NamespacedKey(plugin, "memory_item");
        KEY_CHEST_WORLD = new NamespacedKey(plugin, "memory_world");
        KEY_CHEST_X     = new NamespacedKey(plugin, "memory_x");
        KEY_CHEST_Y     = new NamespacedKey(plugin, "memory_y");
        KEY_CHEST_Z     = new NamespacedKey(plugin, "memory_z");

        // Track deposits + gently guide golems
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    for (Entity e : world.getEntities()) {
                        if (!(e instanceof CopperGolem golem)) continue;

                        trackGolem(golem);
                        guideGolem(golem); // ⭐ NEW: actual behaviour fix
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    // -------------------------
    // TRACK ITEM STATE CHANGES
    // -------------------------
    private void trackGolem(CopperGolem golem) {
        UUID id = golem.getUniqueId();

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;

        Material current = (held == null || held.getType() == Material.AIR)
                ? Material.AIR : held.getType();

        Material last = lastHeld.getOrDefault(id, Material.AIR);

        // Detect successful deposit (had item → now empty)
        if (last != Material.AIR && current == Material.AIR) {
            String pending = pendingChest.get(id);
            String item = pendingItem.get(id);

            if (pending != null && item != null) {
                String[] parts = pending.split(",");

                PersistentDataContainer pdc = golem.getPersistentDataContainer();

                pdc.set(KEY_ITEM_TYPE,   PersistentDataType.STRING,  item);
                pdc.set(KEY_CHEST_WORLD, PersistentDataType.STRING,  parts[0]);
                pdc.set(KEY_CHEST_X,     PersistentDataType.INTEGER, Integer.parseInt(parts[1]));
                pdc.set(KEY_CHEST_Y,     PersistentDataType.INTEGER, Integer.parseInt(parts[2]));
                pdc.set(KEY_CHEST_Z,     PersistentDataType.INTEGER, Integer.parseInt(parts[3]));
            }

            pendingChest.remove(id);
            pendingItem.remove(id);
        }

        // If item type changed mid-carry → reset memory
        if (last != Material.AIR && current != Material.AIR && last != current) {
            clearMemory(golem);
            pendingChest.remove(id);
            pendingItem.remove(id);
        }

        lastHeld.put(id, current);
    }

    // -------------------------
    // ⭐ ACTUAL FIX: GUIDE GOLEM
    // -------------------------
    private void guideGolem(CopperGolem golem) {

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;

        if (held == null || held.getType() == Material.AIR) return;

        PersistentDataContainer pdc = golem.getPersistentDataContainer();

        String worldName = pdc.get(KEY_CHEST_WORLD, PersistentDataType.STRING);
        Integer x = pdc.get(KEY_CHEST_X, PersistentDataType.INTEGER);
        Integer y = pdc.get(KEY_CHEST_Y, PersistentDataType.INTEGER);
        Integer z = pdc.get(KEY_CHEST_Z, PersistentDataType.INTEGER);
        String item = pdc.get(KEY_ITEM_TYPE, PersistentDataType.STRING);

        if (worldName == null || x == null || y == null || z == null || item == null) return;

        if (!held.getType().name().equals(item)) {
            clearMemory(golem);
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Block block = world.getBlockAt(x, y, z);
        if (!(block.getState() instanceof Container container)) {
            clearMemory(golem);
            return;
        }

        if (!containerCanAccept(container.getInventory(), held)) {
            clearMemory(golem);
            return;
        }

        Location target = block.getLocation().add(0.5, 0.5, 0.5);

        // Only guide if reasonably close (prevents weird long-distance pulls)
        if (golem.getLocation().distanceSquared(target) < 32 * 32) {
            golem.getPathfinder().moveTo(target);
        }
    }

    // -------------------------
    // TARGET VALIDATION EVENT
    // -------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGolemValidateTarget(ItemTransportingEntityValidateTargetEvent event) {

        if (!(event.getEntity() instanceof CopperGolem golem)) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Container)) return;

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;

        if (held == null || held.getType() == Material.AIR) return;

        Location loc = block.getLocation();
        if (loc.getWorld() == null) return;

        // Just record candidate (DON’T block anymore)
        String chestKey = loc.getWorld().getName() + "," + loc.getBlockX()
                + "," + loc.getBlockY() + "," + loc.getBlockZ();

        pendingChest.put(golem.getUniqueId(), chestKey);
        pendingItem.put(golem.getUniqueId(), held.getType().name());
    }

    // -------------------------
    // CLEANUP
    // -------------------------
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            UUID id = golem.getUniqueId();
            lastHeld.remove(id);
            pendingChest.remove(id);
            pendingItem.remove(id);
        }
    }

    // -------------------------
    // HELPERS
    // -------------------------
    private boolean containerCanAccept(Inventory inv, ItemStack item) {
        for (ItemStack slot : inv.getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) return true;
            if (slot.isSimilar(item) && slot.getAmount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    public void clearMemory(CopperGolem golem) {
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        pdc.remove(KEY_ITEM_TYPE);
        pdc.remove(KEY_CHEST_WORLD);
        pdc.remove(KEY_CHEST_X);
        pdc.remove(KEY_CHEST_Y);
        pdc.remove(KEY_CHEST_Z);

        pendingChest.remove(golem.getUniqueId());
        pendingItem.remove(golem.getUniqueId());
    }
}
