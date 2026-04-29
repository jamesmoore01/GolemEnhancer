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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GolemListener implements Listener {

    private final GolemEnhancer plugin;

    private final NamespacedKey KEY_ITEM_TYPE;
    private final NamespacedKey KEY_CHEST_WORLD;
    private final NamespacedKey KEY_CHEST_X;
    private final NamespacedKey KEY_CHEST_Y;
    private final NamespacedKey KEY_CHEST_Z;

    private final Map<UUID, Integer> failureCount = new HashMap<>();
    private final Map<UUID, String> chosenChest = new HashMap<>();
    private static final int MAX_FAILURES = 5;

    public GolemListener(GolemEnhancer plugin) {
        this.plugin = plugin;
        KEY_ITEM_TYPE   = new NamespacedKey(plugin, "memory_item");
        KEY_CHEST_WORLD = new NamespacedKey(plugin, "memory_world");
        KEY_CHEST_X     = new NamespacedKey(plugin, "memory_x");
        KEY_CHEST_Y     = new NamespacedKey(plugin, "memory_y");
        KEY_CHEST_Z     = new NamespacedKey(plugin, "memory_z");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGolemValidateTarget(ItemTransportingEntityValidateTargetEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof CopperGolem golem)) return;

        Block targetBlock = event.getBlock();
        BlockState state = targetBlock.getState();
        if (!(state instanceof Container container)) return;

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;
        if (held == null || held.getType() == Material.AIR) return;

        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        String rememberedItem  = pdc.get(KEY_ITEM_TYPE,   PersistentDataType.STRING);
        String rememberedWorld = pdc.get(KEY_CHEST_WORLD, PersistentDataType.STRING);
        Integer rememberedX   = pdc.get(KEY_CHEST_X,     PersistentDataType.INTEGER);
        Integer rememberedY   = pdc.get(KEY_CHEST_Y,     PersistentDataType.INTEGER);
        Integer rememberedZ   = pdc.get(KEY_CHEST_Z,     PersistentDataType.INTEGER);

        boolean hasMemory = rememberedItem != null && rememberedWorld != null
                && rememberedX != null && rememberedY != null && rememberedZ != null;

        // Clear stale memory if item type changed
        if (hasMemory && !held.getType().name().equals(rememberedItem)) {
            clearMemory(golem);
            hasMemory = false;
        }

        Location loc = targetBlock.getLocation();
        if (loc.getWorld() == null) return;
        String chestKey = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();

        if (!hasMemory) {
            // Check if we already chose a chest this trip
            String alreadyChosen = chosenChest.get(golem.getUniqueId());

            if (alreadyChosen != null) {
                // Already picked a chest this trip — allow only that one
                if (alreadyChosen.equals(chestKey)) {
                    event.setAllowed(true);
                } else {
                    event.setAllowed(false);
                }
                return;
            }

            // First chest evaluated this trip
            if (containerCanAccept(container.getInventory(), held)) {
                // This chest works — write memory and allow it
                pdc.set(KEY_ITEM_TYPE,   PersistentDataType.STRING,  held.getType().name());
                pdc.set(KEY_CHEST_WORLD, PersistentDataType.STRING,  loc.getWorld().getName());
                pdc.set(KEY_CHEST_X,     PersistentDataType.INTEGER, loc.getBlockX());
                pdc.set(KEY_CHEST_Y,     PersistentDataType.INTEGER, loc.getBlockY());
                pdc.set(KEY_CHEST_Z,     PersistentDataType.INTEGER, loc.getBlockZ());
                chosenChest.put(golem.getUniqueId(), chestKey);
                failureCount.remove(golem.getUniqueId());
                event.setAllowed(true);
            } else {
                // This chest can't accept the item — skip it, try the next one
                event.setAllowed(false);
            }
            return;
        }

        // Has memory — steer the golem
        boolean isRememberedChest = loc.getWorld().getName().equals(rememberedWorld)
                && loc.getBlockX() == rememberedX
                && loc.getBlockY() == rememberedY
                && loc.getBlockZ() == rememberedZ;

        if (isRememberedChest) {
            if (containerCanAccept(container.getInventory(), held)) {
                event.setAllowed(true);
                chosenChest.remove(golem.getUniqueId());
                failureCount.remove(golem.getUniqueId());
            } else {
                // Remembered chest is full — clear and start fresh
                clearMemory(golem);
                failureCount.remove(golem.getUniqueId());
            }
        } else {
            // Wrong chest — check if remembered one is still valid
            Block rememberedBlock = loc.getWorld().getBlockAt(rememberedX, rememberedY, rememberedZ);
            BlockState rememberedState = rememberedBlock.getState();

            if (rememberedState instanceof Container rc && containerCanAccept(rc.getInventory(), held)) {
                event.setAllowed(false);
                int failures = failureCount.getOrDefault(golem.getUniqueId(), 0) + 1;
                if (failures >= MAX_FAILURES) {
                    clearMemory(golem);
                    failureCount.remove(golem.getUniqueId());
                } else {
                    failureCount.put(golem.getUniqueId(), failures);
                }
            } else {
                // Remembered chest gone or full — clear and start fresh
                clearMemory(golem);
                failureCount.remove(golem.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            failureCount.remove(golem.getUniqueId());
            chosenChest.remove(golem.getUniqueId());
        }
    }

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
        chosenChest.remove(golem.getUniqueId());
    }
}
