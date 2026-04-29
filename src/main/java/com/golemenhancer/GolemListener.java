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

        if (hasMemory && !held.getType().name().equals(rememberedItem)) {
            clearMemory(golem);
            hasMemory = false;
        }

        if (!hasMemory) {
            Inventory inv = container.getInventory();
            if (containerCanAccept(inv, held)) {
                Location loc = targetBlock.getLocation();
                if (loc.getWorld() != null) {
                    pdc.set(KEY_ITEM_TYPE,   PersistentDataType.STRING,  held.getType().name());
                    pdc.set(KEY_CHEST_WORLD, PersistentDataType.STRING,  loc.getWorld().getName());
                    pdc.set(KEY_CHEST_X,     PersistentDataType.INTEGER, loc.getBlockX());
                    pdc.set(KEY_CHEST_Y,     PersistentDataType.INTEGER, loc.getBlockY());
                    pdc.set(KEY_CHEST_Z,     PersistentDataType.INTEGER, loc.getBlockZ());
                    failureCount.remove(golem.getUniqueId());
                }
            }
            return;
        }

        Location target = targetBlock.getLocation();
        if (target.getWorld() == null) return;

        boolean isRememberedChest = target.getWorld().getName().equals(rememberedWorld)
                && target.getBlockX() == rememberedX
                && target.getBlockY() == rememberedY
                && target.getBlockZ() == rememberedZ;

        if (isRememberedChest) {
            if (containerCanAccept(container.getInventory(), held)) {
                event.setAllowed(true);
                failureCount.remove(golem.getUniqueId());
            } else {
                clearMemory(golem);
                failureCount.remove(golem.getUniqueId());
            }
        } else {
            Block rememberedBlock = target.getWorld().getBlockAt(rememberedX, rememberedY, rememberedZ);
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
                clearMemory(golem);
                failureCount.remove(golem.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            failureCount.remove(golem.getUniqueId());
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
    }
}
