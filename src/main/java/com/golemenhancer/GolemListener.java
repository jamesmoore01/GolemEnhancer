package com.golemenhancer;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

import java.util.UUID;

public class GolemListener implements Listener {

    private final Plugin plugin;

    private final NamespacedKey KEY_ITEM;
    private final NamespacedKey KEY_WORLD;
    private final NamespacedKey KEY_X;
    private final NamespacedKey KEY_Y;
    private final NamespacedKey KEY_Z;

    public GolemListener(Plugin plugin) {
        this.plugin = plugin;

        KEY_ITEM  = new NamespacedKey(plugin, "item");
        KEY_WORLD = new NamespacedKey(plugin, "world");
        KEY_X     = new NamespacedKey(plugin, "x");
        KEY_Y     = new NamespacedKey(plugin, "y");
        KEY_Z     = new NamespacedKey(plugin, "z");
    }

    // -------------------------------------------------------
    // STEP 1: CONFIRM SUCCESSFUL DEPOSIT → STORE MEMORY
    // -------------------------------------------------------
    @EventHandler
    public void onItemMove(InventoryMoveItemEvent event) {

        if (!(event.getSource().getHolder() instanceof Entity entity)) return;
        if (!(entity instanceof CopperGolem golem)) return;

        if (!(event.getDestination().getHolder() instanceof Container container)) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;

        Location loc = container.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        // store memory: item → chest
        PersistentDataContainer data = golem.getPersistentDataContainer();

        data.set(KEY_ITEM,  PersistentDataType.STRING, item.getType().name());
        data.set(KEY_WORLD, PersistentDataType.STRING, loc.getWorld().getName());
        data.set(KEY_X,     PersistentDataType.INTEGER, loc.getBlockX());
        data.set(KEY_Y,     PersistentDataType.INTEGER, loc.getBlockY());
        data.set(KEY_Z,     PersistentDataType.INTEGER, loc.getBlockZ());
    }

    // -------------------------------------------------------
    // STEP 2: BIAS TARGET SELECTION (NO FORCING, NO SCANNING)
    // -------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {

        if (!(event.getEntity() instanceof CopperGolem golem)) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Container)) return;

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand()
                : null;

        if (held == null || held.getType().isAir()) return;

        PersistentDataContainer data = golem.getPersistentDataContainer();

        String item = data.get(KEY_ITEM, PersistentDataType.STRING);
        String world = data.get(KEY_WORLD, PersistentDataType.STRING);
        Integer x = data.get(KEY_X, PersistentDataType.INTEGER);
        Integer y = data.get(KEY_Y, PersistentDataType.INTEGER);
        Integer z = data.get(KEY_Z, PersistentDataType.INTEGER);

        // No memory → pure vanilla
        if (item == null || world == null || x == null || y == null || z == null) return;

        // Wrong item → ignore memory
        if (!held.getType().name().equals(item)) return;

        Location loc = block.getLocation();

        boolean isRemembered =
                loc.getWorld().getName().equals(world) &&
                loc.getBlockX() == x &&
                loc.getBlockY() == y &&
                loc.getBlockZ() == z;

        // If this is remembered chest → ALWAYS allow
        if (isRemembered) {
            event.setAllowed(true);
            return;
        }

        // Otherwise:
        // Do NOT hard block anything
        // Let vanilla run, but memory chest stays "preferred"
    }
}
