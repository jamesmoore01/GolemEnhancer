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
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GolemListener implements Listener {

    private final GolemEnhancer plugin;
    private static final int SCAN_RADIUS = 16;

    private final NamespacedKey KEY_WORLD;
    private final NamespacedKey KEY_X;
    private final NamespacedKey KEY_Y;
    private final NamespacedKey KEY_Z;

    private final Map<UUID, Material> lastHeld = new HashMap<>();

    public GolemListener(GolemEnhancer plugin) {
        this.plugin = plugin;
        KEY_WORLD = new NamespacedKey(plugin, "target_world");
        KEY_X     = new NamespacedKey(plugin, "target_x");
        KEY_Y     = new NamespacedKey(plugin, "target_y");
        KEY_Z     = new NamespacedKey(plugin, "target_z");

        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : plugin.getServer().getWorlds()) {
                    for (Entity e : world.getEntities()) {
                        if (!(e instanceof CopperGolem golem)) continue;
                        trackGolem(golem);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private void trackGolem(CopperGolem golem) {
        UUID id = golem.getUniqueId();

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;
        Material current = (held == null || held.getType().isAir())
                ? Material.AIR : held.getType();
        Material last = lastHeld.getOrDefault(id, Material.AIR);

        if (last == Material.AIR && current != Material.AIR) {
            plugin.getLogger().info("[PICKUP] Golem picked up " + current + " - scanning...");
            Location best = findBestChest(golem, held);
            if (best != null) {
                plugin.getLogger().info("[SCAN] Best chest found at " + best.getBlockX() + "," + best.getBlockY() + "," + best.getBlockZ());
                setTarget(golem, best);
            } else {
                plugin.getLogger().info("[SCAN] No suitable chest found - going vanilla");
                clearTarget(golem);
            }
        }

        if (last != Material.AIR && current == Material.AIR) {
            plugin.getLogger().info("[DEPOSIT] Golem deposited - clearing target");
            clearTarget(golem);
        }

        if (last != Material.AIR && current != Material.AIR && last != current) {
            plugin.getLogger().info("[CHANGE] Item changed to " + current + " - rescanning...");
            Location best = findBestChest(golem, held);
            if (best != null) {
                setTarget(golem, best);
            } else {
                clearTarget(golem);
            }
        }

        lastHeld.put(id, current);
    }

    private Location findBestChest(CopperGolem golem, ItemStack item) {
        Location golemLoc = golem.getLocation();
        Location bestMatch = null;
        Location bestEmpty = null;
        double bestMatchDist = Double.MAX_VALUE;
        double bestEmptyDist = Double.MAX_VALUE;
        int containersScanned = 0;

        int r = SCAN_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    Block block = golemLoc.getWorld().getBlockAt(
                            golemLoc.getBlockX() + x,
                            golemLoc.getBlockY() + y,
                            golemLoc.getBlockZ() + z);
                    BlockState state = block.getState();
                    if (!(state instanceof Container container)) continue;
                    containersScanned++;

                    Inventory inv = container.getInventory();
                    boolean hasItems = false;
                    boolean hasSameItem = false;
                    boolean hasSpace = false;

                    for (ItemStack slot : inv.getStorageContents()) {
                        if (slot == null || slot.getType().isAir()) {
                            hasSpace = true;
                        } else {
                            hasItems = true;
                            if (slot.isSimilar(item)) {
                                hasSameItem = true;
                                if (slot.getAmount() < slot.getMaxStackSize()) {
                                    hasSpace = true;
                                }
                            }
                        }
                    }

                    if (!hasSpace) continue;

                    Location loc = block.getLocation();
                    double dist = golemLoc.distanceSquared(loc);

                    if (hasItems && hasSameItem) {
                        if (dist < bestMatchDist) {
                            bestMatchDist = dist;
                            bestMatch = loc;
                        }
                    } else if (!hasItems) {
                        if (dist < bestEmptyDist) {
                            bestEmptyDist = dist;
                            bestEmpty = loc;
                        }
                    }
                }
            }
        }

        plugin.getLogger().info("[SCAN] Scanned " + containersScanned + " containers - match=" + (bestMatch != null) + " empty=" + (bestEmpty != null));
        return bestMatch != null ? bestMatch : bestEmpty;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem)) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Container)) return;

        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;
        if (held == null || held.getType().isAir()) return;

        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        String targetWorld = pdc.get(KEY_WORLD, PersistentDataType.STRING);
        Integer targetX    = pdc.get(KEY_X,     PersistentDataType.INTEGER);
        Integer targetY    = pdc.get(KEY_Y,     PersistentDataType.INTEGER);
        Integer targetZ    = pdc.get(KEY_Z,     PersistentDataType.INTEGER);

        if (targetWorld == null || targetX == null || targetY == null || targetZ == null) {
            plugin.getLogger().info("[VALIDATE] No target - vanilla");
            return;
        }

        Location targetLoc = block.getLocation();
        if (targetLoc.getWorld() == null) return;

        boolean isTarget = targetLoc.getWorld().getName().equals(targetWorld)
                && targetLoc.getBlockX() == targetX
                && targetLoc.getBlockY() == targetY
                && targetLoc.getBlockZ() == targetZ;

        if (isTarget) {
            plugin.getLogger().info("[VALIDATE] Correct chest - allowing");
            event.setAllowed(true);
        } else {
            plugin.getLogger().info("[VALIDATE] Wrong chest at " + targetLoc.getBlockX() + "," + targetLoc.getBlockY() + "," + targetLoc.getBlockZ() + " - blocking. Target is " + targetX + "," + targetY + "," + targetZ);
            event.setAllowed(false);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            lastHeld.remove(golem.getUniqueId());
            clearTarget(golem);
        }
    }

    private void setTarget(CopperGolem golem, Location loc) {
        if (loc.getWorld() == null) return;
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        pdc.set(KEY_WORLD, PersistentDataType.STRING,  loc.getWorld().getName());
        pdc.set(KEY_X,     PersistentDataType.INTEGER, loc.getBlockX());
        pdc.set(KEY_Y,     PersistentDataType.INTEGER, loc.getBlockY());
        pdc.set(KEY_Z,     PersistentDataType.INTEGER, loc.getBlockZ());
    }

    private void clearTarget(CopperGolem golem) {
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        pdc.remove(KEY_WORLD);
        pdc.remove(KEY_X);
        pdc.remove(KEY_Y);
        pdc.remove(KEY_Z);
    }

    public void clearMemory(CopperGolem golem) {
        clearTarget(golem);
        lastHeld.remove(golem.getUniqueId());
    }
}
