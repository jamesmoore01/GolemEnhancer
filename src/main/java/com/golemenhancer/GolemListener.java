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
import org.bukkit.scheduler.BukkitRunnable;

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

    private final Map<UUID, Material> lastHeld = new HashMap<>();
    private final Map<UUID, String> pendingChest = new HashMap<>();
    private final Map<UUID, String> pendingItem = new HashMap<>();

    public GolemListener(GolemEnhancer plugin) {
        this.plugin = plugin;
        KEY_ITEM_TYPE   = new NamespacedKey(plugin, "memory_item");
        KEY_CHEST_WORLD = new NamespacedKey(plugin, "memory_world");
        KEY_CHEST_X     = new NamespacedKey(plugin, "memory_x");
        KEY_CHEST_Y     = new NamespacedKey(plugin, "memory_y");
        KEY_CHEST_Z     = new NamespacedKey(plugin, "memory_z");

        new BukkitRunnable() {
            @Override
            public void run() {
                for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                    for (Entity e : world.getEntities()) {
                        if (!(e instanceof CopperGolem golem)) continue;
                        trackGolem(golem);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void trackGolem(CopperGolem golem) {
        UUID id = golem.getUniqueId();
        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;
        Material current = (held == null || held.getType() == Material.AIR)
                ? Material.AIR : held.getType();
        Material last = lastHeld.getOrDefault(id, Material.AIR);

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

        if (last != Material.AIR && current != Material.AIR && last != current) {
            clearMemory(golem);
            pendingChest.remove(id);
            pendingItem.remove(id);
        }

        lastHeld.put(id, current);
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

        Location loc = targetBlock.getLocation();
        if (loc.getWorld() == null) return;

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
            String chestKey = loc.getWorld().getName() + "," + loc.getBlockX()
                    + "," + loc.getBlockY() + "," + loc.getBlockZ();
            pendingChest.put(golem.getUniqueId(), chestKey);
            pendingItem.put(golem.getUniqueId(), held.getType().name());
            return;
        }

        boolean isRememberedChest = loc.getWorld().getName().equals(rememberedWorld)
                && loc.getBlockX() == rememberedX
                && loc.getBlockY() == rememberedY
                && loc.getBlockZ() == rememberedZ;

        if (isRememberedChest) {
            if (containerCanAccept(container.getInventory(), held)) {
                event.setAllowed(true);
                String chestKey = loc.getWorld().getName() + "," + loc.getBlockX()
                        + "," + loc.getBlockY() + "," + loc.getBlockZ();
                pendingChest.put(golem.getUniqueId(), chestKey);
                pendingItem.put(golem.getUniqueId(), held.getType().name());
            } else {
                clearMemory(golem);
            }
        } else {
            Block rememberedBlock = loc.getWorld().getBlockAt(rememberedX, rememberedY, rememberedZ);
            BlockState rememberedState = rememberedBlock.getState();

            if (rememberedState instanceof Container rc && containerCanAccept(rc.getInventory(), held)) {
                event.setAllowed(false);
            } else {
                clearMemory(golem);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            lastHeld.remove(golem.getUniqueId());
            pendingChest.remove(golem.getUniqueId());
            pendingItem.remove(golem.getUniqueId());
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
        pendingChest.remove(golem.getUniqueId());
        pendingItem.remove(golem.getUniqueId());
    }
}
