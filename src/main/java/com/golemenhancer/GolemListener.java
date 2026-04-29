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
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
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

    private final NamespacedKey KEY_ITEM;
    private final NamespacedKey KEY_WORLD;
    private final NamespacedKey KEY_X;
    private final NamespacedKey KEY_Y;
    private final NamespacedKey KEY_Z;

    // Track what each golem was holding last tick
    private final Map<UUID, Material> lastHeld = new HashMap<>();
    // Track the last chest each golem validated toward
    private final Map<UUID, Location> lastValidated = new HashMap<>();

    public GolemListener(Plugin plugin) {
        this.plugin = plugin;

        KEY_ITEM  = new NamespacedKey(plugin, "item");
        KEY_WORLD = new NamespacedKey(plugin, "world");
        KEY_X     = new NamespacedKey(plugin, "x");
        KEY_Y     = new NamespacedKey(plugin, "y");
        KEY_Z     = new NamespacedKey(plugin, "z");

        // Scheduler: poll every 5 ticks to detect hand going empty
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : plugin.getServer().getWorlds()) {
                    for (Entity e : world.getEntities()) {
                        if (!(e instanceof CopperGolem golem)) continue;
                        UUID id = golem.getUniqueId();

                        ItemStack held = golem.getEquipment() != null
                                ? golem.getEquipment().getItemInMainHand() : null;
                        Material current = (held == null || held.getType().isAir())
                                ? Material.AIR : held.getType();
                        Material last = lastHeld.getOrDefault(id, Material.AIR);

                        // Hand just went empty — golem deposited something
                        if (last != Material.AIR && current == Material.AIR) {
                            plugin.getLogger().info("[SCHEDULER] Golem hand went empty! Was holding: " + last);
                            Location lastChest = lastValidated.get(id);
                            if (lastChest != null) {
                                plugin.getLogger().info("[SCHEDULER] Last validated chest was: "
                                        + lastChest.getBlockX() + "," + lastChest.getBlockY() + "," + lastChest.getBlockZ());
                                writeMemory(golem, last, lastChest);
                            } else {
                                plugin.getLogger().info("[SCHEDULER] No validated chest recorded - can't write memory");
                            }
                        }

                        // Hand just got something
                        if (last == Material.AIR && current != Material.AIR) {
                            plugin.getLogger().info("[SCHEDULER] Golem picked up: " + current);
                        }

                        // Item changed while carrying
                        if (last != Material.AIR && current != Material.AIR && last != current) {
                            plugin.getLogger().info("[SCHEDULER] Golem item changed from " + last + " to " + current + " - clearing memory");
                            clearMemory(golem);
                        }

                        lastHeld.put(id, current);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    // APPROACH 1: InventoryMoveItemEvent - fires for hoppers, maybe golems?
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMove(InventoryMoveItemEvent event) {
        if (!(event.getSource().getHolder() instanceof CopperGolem golem)) return;
        plugin.getLogger().info("[INVENTORYMOVE] Fired! Source is CopperGolem!");

        if (!(event.getDestination().getHolder() instanceof Container container)) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;

        Location loc = container.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        plugin.getLogger().info("[INVENTORYMOVE] Writing memory from InventoryMoveItemEvent: "
                + item.getType().name() + " -> " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
        writeMemory(golem, item.getType(), loc);
    }

    // APPROACH 2: InventoryPickupItemEvent - fires when inventory picks up item
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(InventoryPickupItemEvent event) {
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof CopperGolem golem) {
            plugin.getLogger().info("[PICKUPITEM] CopperGolem picked up: " + event.getItem().getItemStack().getType());
        }
        if (inv.getHolder() instanceof Container) {
            // Check if a golem is nearby
            Location loc = inv.getLocation();
            if (loc == null) return;
            for (Entity e : loc.getWorld().getNearbyEntities(loc, 3, 3, 3)) {
                if (e instanceof CopperGolem golem) {
                    ItemStack held = golem.getEquipment() != null
                            ? golem.getEquipment().getItemInMainHand() : null;
                    if (held != null && !held.getType().isAir()) {
                        plugin.getLogger().info("[PICKUPITEM] Container picked up item near golem holding "
                                + held.getType() + " - possible deposit!");
                    }
                }
            }
        }
    }

    // APPROACH 3: ValidateTarget - track which chest golem is heading to
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem)) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Container)) return;

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;
        if (held == null || held.getType().isAir()) return;

        Location targetLoc = block.getLocation();

        plugin.getLogger().info("[VALIDATE] Golem holding " + held.getType()
                + " validating chest at " + targetLoc.getBlockX() + "," + targetLoc.getBlockY() + "," + targetLoc.getBlockZ()
                + " allowed=" + event.isAllowed());

        // Record last validated chest for this golem
        lastValidated.put(golem.getUniqueId(), targetLoc);

        // Check memory
        PersistentDataContainer data = golem.getPersistentDataContainer();
        String memItem  = data.get(KEY_ITEM,  PersistentDataType.STRING);
        String memWorld = data.get(KEY_WORLD, PersistentDataType.STRING);
        Integer memX    = data.get(KEY_X,     PersistentDataType.INTEGER);
        Integer memY    = data.get(KEY_Y,     PersistentDataType.INTEGER);
        Integer memZ    = data.get(KEY_Z,     PersistentDataType.INTEGER);

        boolean hasMemory = memItem != null && memWorld != null
                && memX != null && memY != null && memZ != null;

        if (!hasMemory) {
            plugin.getLogger().info("[VALIDATE] No memory - vanilla");
            return;
        }

        if (!held.getType().name().equals(memItem)) {
            plugin.getLogger().info("[VALIDATE] Different item - clearing memory");
            clearMemory(golem);
            return;
        }

        boolean isRemembered = targetLoc.getWorld().getName().equals(memWorld)
                && targetLoc.getBlockX() == memX
                && targetLoc.getBlockY() == memY
                && targetLoc.getBlockZ() == memZ;

        if (isRemembered) {
            plugin.getLogger().info("[VALIDATE] Remembered chest - allowing!");
            event.setAllowed(true);
        } else {
            plugin.getLogger().info("[VALIDATE] Wrong chest - blocking!");
            event.setAllowed(false);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            lastHeld.remove(golem.getUniqueId());
            lastValidated.remove(golem.getUniqueId());
        }
    }

    private void writeMemory(CopperGolem golem, Material item, Location loc) {
        if (loc.getWorld() == null) return;
        PersistentDataContainer data = golem.getPersistentDataContainer();
        data.set(KEY_ITEM,  PersistentDataType.STRING,  item.name());
        data.set(KEY_WORLD, PersistentDataType.STRING,  loc.getWorld().getName());
        data.set(KEY_X,     PersistentDataType.INTEGER, loc.getBlockX());
        data.set(KEY_Y,     PersistentDataType.INTEGER, loc.getBlockY());
        data.set(KEY_Z,     PersistentDataType.INTEGER, loc.getBlockZ());
        plugin.getLogger().info("[MEMORY] Written: " + item.name()
                + " -> " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    public void clearMemory(CopperGolem golem) {
        PersistentDataContainer data = golem.getPersistentDataContainer();
        data.remove(KEY_ITEM);
        data.remove(KEY_WORLD);
        data.remove(KEY_X);
        data.remove(KEY_Y);
        data.remove(KEY_Z);
        lastValidated.remove(golem.getUniqueId());
        plugin.getLogger().info("[MEMORY] Cleared for golem " + golem.getUniqueId());
    }
}
