package com.golemenhancer;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GolemListener implements org.bukkit.event.Listener {

    private final Plugin plugin;

    private final NamespacedKey KEY_ITEM;
    private final NamespacedKey KEY_WORLD;
    private final NamespacedKey KEY_X;
    private final NamespacedKey KEY_Y;
    private final NamespacedKey KEY_Z;

    // Tracks last item held (for detecting successful deposit)
    private final Map<UUID, Material> lastHeld = new HashMap<>();

    // Temporary "just saw a chest interaction"
    private final Map<UUID, String> pendingChest = new HashMap<>();
    private final Map<UUID, String> pendingItem = new HashMap<>();

    public GolemListener(Plugin plugin) {
        this.plugin = plugin;

        KEY_ITEM  = new NamespacedKey(plugin, "item");
        KEY_WORLD = new NamespacedKey(plugin, "world");
        KEY_X     = new NamespacedKey(plugin, "x");
        KEY_Y     = new NamespacedKey(plugin, "y");
        KEY_Z     = new NamespacedKey(plugin, "z");

        // Main loop
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    for (Entity e : world.getEntities()) {
                        if (!(e instanceof CopperGolem golem)) continue;

                        trackDepositMemory(golem);
                        applyMemoryMovement(golem);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    // -------------------------------------------------
    // STEP 1: detect successful deposit → store memory
    // -------------------------------------------------
    private void trackDepositMemory(CopperGolem golem) {

        UUID id = golem.getUniqueId();

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand()
                : null;

        Material current = (held == null || held.getType().isAir())
                ? Material.AIR
                : held.getType();

        Material last = lastHeld.getOrDefault(id, Material.AIR);

        // deposit detected (had item → now empty)
        if (last != Material.AIR && current == Material.AIR) {

            String pending = pendingChest.get(id);
            String item = pendingItem.get(id);

            if (pending != null && item != null) {
                String[] p = pending.split(",");

                PersistentDataContainer data = golem.getPersistentDataContainer();

                data.set(KEY_ITEM,  PersistentDataType.STRING, item);
                data.set(KEY_WORLD, PersistentDataType.STRING, p[0]);
                data.set(KEY_X,     PersistentDataType.INTEGER, Integer.parseInt(p[1]));
                data.set(KEY_Y,     PersistentDataType.INTEGER, Integer.parseInt(p[2]));
                data.set(KEY_Z,     PersistentDataType.INTEGER, Integer.parseInt(p[3]));
            }

            pendingChest.remove(id);
            pendingItem.remove(id);
        }

        lastHeld.put(id, current);
    }

    // -------------------------------------------------
    // STEP 2: ONLY override movement if memory exists
    // -------------------------------------------------
    private void applyMemoryMovement(CopperGolem golem) {

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand()
                : null;

        if (held == null || held.getType().isAir()) return;

        PersistentDataContainer data = golem.getPersistentDataContainer();

        String worldName = data.get(KEY_WORLD, PersistentDataType.STRING);
        Integer x = data.get(KEY_X, PersistentDataType.INTEGER);
        Integer y = data.get(KEY_Y, PersistentDataType.INTEGER);
        Integer z = data.get(KEY_Z, PersistentDataType.INTEGER);
        String item = data.get(KEY_ITEM, PersistentDataType.STRING);

        // 🟢 NO MEMORY → VANILLA BEHAVIOUR
        if (worldName == null || x == null || y == null || z == null || item == null) {
            return;
        }

        // wrong item → ignore memory
        if (!held.getType().name().equals(item)) {
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Block block = world.getBlockAt(x, y, z);

        if (!(block.getState() instanceof Container container)) return;

        if (!containerCanAccept(container, held)) return;

        Location target = block.getLocation().add(0.5, 0.5, 0.5);

        // ⭐ ONLY intervention point
        golem.getPathfinder().moveTo(target);
    }

    // -------------------------------------------------
    // helper: check chest space
    // -------------------------------------------------
    private boolean containerCanAccept(Container container, ItemStack item) {

        for (ItemStack stack : container.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) return true;
            if (stack.isSimilar(item) &&
                stack.getAmount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }
}
