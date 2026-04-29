package com.golemenhancer;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GolemListener implements Listener {

    private final GolemEnhancer plugin;

    // Keys stored on the golem entity via PersistentDataContainer (survive restarts)
    private final NamespacedKey KEY_ITEM_TYPE;
    private final NamespacedKey KEY_CHEST_WORLD;
    private final NamespacedKey KEY_CHEST_X;
    private final NamespacedKey KEY_CHEST_Y;
    private final NamespacedKey KEY_CHEST_Z;

    // Per-golem failure counter: if a golem is blocked from too many chests in a row,
    // clear its memory so it doesn't get permanently stuck.
    private final Map<UUID, Integer> failureCount = new HashMap<>();
    private static final int MAX_FAILURES = 5;

    public GolemListener(GolemEnhancer plugin) {
        this.plugin = plugin;
        KEY_ITEM_TYPE   = new NamespacedKey(plugin, "memory_item");
        KEY_CHEST_WORLD = new NamespacedKey(plugin, "memory_world");
        KEY_CHEST_X     = new NamespacedKey(plugin, "memory_x");
        KEY_CHEST_Y     = new NamespacedKey(plugin, "memory_y");
        KEY_CHEST_Z     = new NamespacedKey(plugin, "memory_z");
    }

    // -------------------------------------------------------------------------
    // 1. CHEST MEMORY + QUIET CHESTS
    //    When the golem validates a target container, steer it to its remembered
    //    chest and block wrong ones. This also achieves quieter behaviour because
    //    the golem skips opening chests it already knows are wrong.
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onGolemValidateTarget(ItemTransportingEntityValidateTargetEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof CopperGolem golem)) return;

        Block targetBlock = event.getBlock();
        BlockState state = targetBlock.getState();

        // Must be some kind of container (covers chest, trapped chest, copper chest, barrel)
        if (!(state instanceof Container)) return;

        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        String rememberedWorld = pdc.get(KEY_CHEST_WORLD, PersistentDataType.STRING);
        Integer rememberedX    = pdc.get(KEY_CHEST_X,     PersistentDataType.INTEGER);
        Integer rememberedY    = pdc.get(KEY_CHEST_Y,     PersistentDataType.INTEGER);
        Integer rememberedZ    = pdc.get(KEY_CHEST_Z,     PersistentDataType.INTEGER);
        String rememberedItem  = pdc.get(KEY_ITEM_TYPE,   PersistentDataType.STRING);

        // No memory — nothing to do, let vanilla decide
        if (rememberedWorld == null || rememberedX == null || rememberedY == null
                || rememberedZ == null || rememberedItem == null) {
            return;
        }

        // Check what the golem is holding
        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;

        if (held == null || held.getType() == Material.AIR) {
            // Golem is on a pickup trip — let vanilla handle container selection
            return;
        }

        // If the golem is now carrying a different item type, clear stale memory
        if (!held.getType().name().equals(rememberedItem)) {
            clearMemory(golem);
            return;
        }

        // Memory matches. Is this the remembered chest?
        Location target = targetBlock.getLocation();
        if (target.getWorld() == null) return;

        boolean isRememberedChest =
                target.getWorld().getName().equals(rememberedWorld)
                && target.getBlockX() == rememberedX
                && target.getBlockY() == rememberedY
                && target.getBlockZ() == rememberedZ;

        if (isRememberedChest) {
            // Validate the remembered chest can still accept our item
            Container container = (Container) state;
            if (containerCanAccept(container.getInventory(), held)) {
                event.setAllowed(true);
                failureCount.remove(golem.getUniqueId());
            } else {
                // Chest is now full — clear memory and let vanilla find a new chest
                clearMemory(golem);
                failureCount.remove(golem.getUniqueId());
            }
        } else {
            // Wrong chest — check if the remembered one is still valid before blocking
            Block rememberedBlock = target.getWorld()
                    .getBlockAt(rememberedX, rememberedY, rememberedZ);
            BlockState rememberedState = rememberedBlock.getState();

            if (rememberedState instanceof Container rc
                    && containerCanAccept(rc.getInventory(), held)) {
                // Remembered chest is still valid — block this wrong one
                event.setAllowed(false);

                // Failure guard: if the golem keeps getting blocked, free it
                int failures = failureCount.getOrDefault(golem.getUniqueId(), 0) + 1;
                if (failures >= MAX_FAILURES) {
                    clearMemory(golem);
                    failureCount.remove(golem.getUniqueId());
                } else {
                    failureCount.put(golem.getUniqueId(), failures);
                }
            } else {
                // Remembered chest is gone, broken, or full — clear memory
                clearMemory(golem);
                failureCount.remove(golem.getUniqueId());
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. LARGER ITEM TRANSFERS + MEMORY UPDATE
    //    When the golem deposits into a chest, boost the transfer to a full stack
    //    and record which chest holds which item type.
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Inventory destination = event.getDestination();
        Location destLoc = destination.getLocation();
        if (destLoc == null || destLoc.getWorld() == null) return;

        // Find the nearest copper golem within 4 blocks of the destination chest
        CopperGolem golem = findNearbyGolem(destLoc, 4.0);
        if (golem == null) return;

        // Confirm the golem is actually carrying the item being moved
        // This prevents false positives from hoppers near a wandering golem
        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;
        ItemStack moving = event.getItem();
        if (held == null || held.getType() == Material.AIR) return;
        if (held.getType() != moving.getType()) return;

        // Update chest memory: record that this chest holds this item type
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        pdc.set(KEY_ITEM_TYPE,   PersistentDataType.STRING,  moving.getType().name());
        pdc.set(KEY_CHEST_WORLD, PersistentDataType.STRING,  destLoc.getWorld().getName());
        pdc.set(KEY_CHEST_X,     PersistentDataType.INTEGER, destLoc.getBlockX());
        pdc.set(KEY_CHEST_Y,     PersistentDataType.INTEGER, destLoc.getBlockY());
        pdc.set(KEY_CHEST_Z,     PersistentDataType.INTEGER, destLoc.getBlockZ());

        // Reset failure counter on successful deposit
        failureCount.remove(golem.getUniqueId());
    }

    // -------------------------------------------------------------------------
    // 3. CLEANUP — remove golem from failure map when it dies or leaves the world
    // -------------------------------------------------------------------------

    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            failureCount.remove(golem.getUniqueId());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            failureCount.remove(golem.getUniqueId());
        }
    }

    // -------------------------------------------------------------------------
    // Helper: can this container accept more of this item?
    // -------------------------------------------------------------------------
    private boolean containerCanAccept(Inventory inv, ItemStack item) {
        for (ItemStack slot : inv.getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) return true;
            if (slot.isSimilar(item) && slot.getAmount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helper: count available space for an item in an inventory
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Helper: find the nearest CopperGolem within radius — returns closest one
    // -------------------------------------------------------------------------
    private CopperGolem findNearbyGolem(Location loc, double radius) {
        if (loc.getWorld() == null) return null;
        CopperGolem closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity e : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (e instanceof CopperGolem golem) {
                double dist = e.getLocation().distanceSquared(loc);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = golem;
                }
            }
        }
        return closest;
    }

    // -------------------------------------------------------------------------
    // Helper: wipe all memory keys from a golem
    // -------------------------------------------------------------------------
    public void clearMemory(CopperGolem golem) {
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        pdc.remove(KEY_ITEM_TYPE);
        pdc.remove(KEY_CHEST_WORLD);
        pdc.remove(KEY_CHEST_X);
        pdc.remove(KEY_CHEST_Y);
        pdc.remove(KEY_CHEST_Z);
    }
}
