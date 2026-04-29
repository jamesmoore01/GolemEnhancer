package com.golemenhancer;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GolemListener implements Listener {

    private final GolemEnhancer plugin;

    // Keys used to persist memory on the golem entity itself
    private final NamespacedKey KEY_ITEM_TYPE;     // what item the golem last deposited
    private final NamespacedKey KEY_CHEST_WORLD;   // world name of the remembered chest
    private final NamespacedKey KEY_CHEST_X;       // remembered chest X
    private final NamespacedKey KEY_CHEST_Y;       // remembered chest Y
    private final NamespacedKey KEY_CHEST_Z;       // remembered chest Z

    // How many extra items we allow the golem to move per inventory transfer
    // Vanilla is 16; we boost to a full stack (64).
    private static final int BOOST_AMOUNT = 64;

    // Track which golem UUIDs are currently heading to their remembered chest
    // so we can suppress the chest-open sound for those interactions.
    private final Set<UUID> golems_with_memory = new HashSet<>();

    public GolemListener(GolemEnhancer plugin) {
        this.plugin = plugin;
        KEY_ITEM_TYPE  = new NamespacedKey(plugin, "memory_item");
        KEY_CHEST_WORLD = new NamespacedKey(plugin, "memory_world");
        KEY_CHEST_X    = new NamespacedKey(plugin, "memory_x");
        KEY_CHEST_Y    = new NamespacedKey(plugin, "memory_y");
        KEY_CHEST_Z    = new NamespacedKey(plugin, "memory_z");
    }

    // -------------------------------------------------------------------------
    // 1. CHEST MEMORY
    //    When the golem validates a target chest, check if we already know
    //    which chest holds the item type it's currently carrying.
    //    If we do, steer it to that chest (allow) or away from wrong chests (disallow).
    //    When a transfer completes, record the chest location on the golem.
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onGolemValidateTarget(ItemTransportingEntityValidateTargetEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof CopperGolem golem)) return;

        Block targetBlock = event.getBlock();
        BlockState state = targetBlock.getState();

        // Only intercept chest interactions (normal chest, trapped chest, copper chest)
        if (!(state instanceof Chest)) return;

        // Check if this golem has a remembered chest for the item it's carrying
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        String rememberedWorld = pdc.get(KEY_CHEST_WORLD, PersistentDataType.STRING);
        Integer rememberedX    = pdc.get(KEY_CHEST_X, PersistentDataType.INTEGER);
        Integer rememberedY    = pdc.get(KEY_CHEST_Y, PersistentDataType.INTEGER);
        Integer rememberedZ    = pdc.get(KEY_CHEST_Z, PersistentDataType.INTEGER);
        String rememberedItem  = pdc.get(KEY_ITEM_TYPE, PersistentDataType.STRING);

        if (rememberedWorld == null || rememberedX == null || rememberedY == null
                || rememberedZ == null || rememberedItem == null) {
            // No memory yet — let vanilla decide, but mark this golem as not using memory
            golems_with_memory.remove(golem.getUniqueId());
            return;
        }

        // Check if the golem is currently carrying the item it has memory for
        ItemStack held = golem.getEquipment() != null
                ? golem.getEquipment().getItemInMainHand() : null;
        if (held == null || held.getType() == Material.AIR) {
            // Not carrying anything — golem is picking up, clear stale memory
            clearMemory(golem);
            golems_with_memory.remove(golem.getUniqueId());
            return;
        }

        // Check if the held item matches the remembered item type
        if (!held.getType().name().equals(rememberedItem)) {
            // Carrying a different item type — memory is stale, clear it
            clearMemory(golem);
            golems_with_memory.remove(golem.getUniqueId());
            return;
        }

        // We have valid memory. Check if this target block is the remembered chest.
        Location target = targetBlock.getLocation();
        boolean isRememberedChest =
                target.getWorld() != null
                && target.getWorld().getName().equals(rememberedWorld)
                && target.getBlockX() == rememberedX
                && target.getBlockY() == rememberedY
                && target.getBlockZ() == rememberedZ;

        if (isRememberedChest) {
            // This IS the remembered chest — validate it still has our item
            Chest chest = (Chest) state;
            boolean stillValid = chestContainsItem(chest.getInventory(), held.getType());
            if (stillValid) {
                // Allow and mark as using memory (suppress sound)
                event.setAllowed(true);
                golems_with_memory.add(golem.getUniqueId());
            } else {
                // Chest no longer has our item (emptied by player etc.) — clear memory
                clearMemory(golem);
                golems_with_memory.remove(golem.getUniqueId());
                // Don't override vanilla decision
            }
        } else {
            // This is NOT the remembered chest — disallow to force the golem toward the right one
            // But only if the remembered chest is in the same world and loaded
            if (target.getWorld() != null
                    && target.getWorld().getName().equals(rememberedWorld)) {
                Block rememberedBlock = target.getWorld()
                        .getBlockAt(rememberedX, rememberedY, rememberedZ);
                if (rememberedBlock.getState() instanceof Chest rc) {
                    boolean stillValid = chestContainsItem(rc.getInventory(), held.getType());
                    if (stillValid) {
                        // Redirect — disallow this wrong chest
                        event.setAllowed(false);
                        golems_with_memory.add(golem.getUniqueId());
                    } else {
                        // Remembered chest is now empty — clear memory, let vanilla decide
                        clearMemory(golem);
                        golems_with_memory.remove(golem.getUniqueId());
                    }
                } else {
                    // Block is no longer a chest (broken?) — clear memory
                    clearMemory(golem);
                    golems_with_memory.remove(golem.getUniqueId());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. LARGER ITEM TRANSFERS
    //    When a copper golem moves items into a chest via InventoryMoveItemEvent,
    //    boost the transfer amount to a full stack instead of vanilla's 16.
    //    We also record which chest received the item (chest memory update).
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        // The initiator for copper golem transfers is the golem itself.
        // We identify it by checking if a CopperGolem is nearby the destination chest.
        Inventory destination = event.getDestination();
        if (destination.getLocation() == null) return;

        // Find a copper golem near the destination chest
        CopperGolem golem = findNearbyGolem(destination.getLocation(), 3.0);
        if (golem == null) return;

        // Boost the transfer: set the item amount to max stack size
        ItemStack item = event.getItem().clone();
        int maxStack = item.getMaxStackSize();
        int currentAmount = item.getAmount();

        // Only boost if we're currently below the max stack
        if (currentAmount < maxStack) {
            // Check how much space the destination has for this item
            int available = countSpaceFor(destination, item);
            int boostedAmount = Math.min(maxStack, available);
            if (boostedAmount > currentAmount) {
                item.setAmount(boostedAmount);
                event.setItem(item);
            }
        }

        // Update the golem's chest memory: record this chest as the home for this item type
        if (destination.getLocation() != null && destination.getLocation().getWorld() != null) {
            Location loc = destination.getLocation();
            PersistentDataContainer pdc = golem.getPersistentDataContainer();
            pdc.set(KEY_ITEM_TYPE,   PersistentDataType.STRING,  item.getType().name());
            pdc.set(KEY_CHEST_WORLD, PersistentDataType.STRING,  loc.getWorld().getName());
            pdc.set(KEY_CHEST_X,     PersistentDataType.INTEGER, loc.getBlockX());
            pdc.set(KEY_CHEST_Y,     PersistentDataType.INTEGER, loc.getBlockY());
            pdc.set(KEY_CHEST_Z,     PersistentDataType.INTEGER, loc.getBlockZ());
        }
    }

    // -------------------------------------------------------------------------
    // 3. QUIETER GOLEMS
    //    Suppress the chest-open/close sound when the golem is using its memory
    //    (i.e. it already knows exactly where to go — no need to "check" the chest).
    //    We cancel the sound by listening to when nearby sound would play.
    //    The best hook we have in the public API is EntityChangeBlockEvent for
    //    block state changes (chest open/close), but sounds are server-side so
    //    we instead just stop the world sound at the chest location for all players.
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveQuiet(InventoryMoveItemEvent event) {
        Inventory destination = event.getDestination();
        if (destination.getLocation() == null) return;

        CopperGolem golem = findNearbyGolem(destination.getLocation(), 3.0);
        if (golem == null) return;

        // If this golem is using its memory, suppress the chest sound for nearby players
        if (golems_with_memory.contains(golem.getUniqueId())) {
            Location chestLoc = destination.getLocation();
            if (chestLoc.getWorld() != null) {
                // Stop the chest open sound for all players within hearing range
                chestLoc.getWorld().getPlayers().forEach(player -> {
                    if (player.getLocation().distanceSquared(chestLoc) <= 64 * 64) {
                        player.stopSound(Sound.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS);
                        player.stopSound(Sound.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS);
                    }
                });
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper: check if an inventory contains at least one of the given material
    // -------------------------------------------------------------------------
    private boolean chestContainsItem(Inventory inv, Material type) {
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack != null && stack.getType() == type) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helper: count how many more of this item the inventory can accept
    // -------------------------------------------------------------------------
    private int countSpaceFor(Inventory inv, ItemStack item) {
        int space = 0;
        int maxStack = item.getMaxStackSize();
        for (ItemStack slot : inv.getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                space += maxStack;
            } else if (slot.isSimilar(item)) {
                space += maxStack - slot.getAmount();
            }
        }
        return space;
    }

    // -------------------------------------------------------------------------
    // Helper: find the nearest CopperGolem within range of a location
    // -------------------------------------------------------------------------
    private CopperGolem findNearbyGolem(Location loc, double radius) {
        if (loc.getWorld() == null) return null;
        for (Entity e : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (e instanceof CopperGolem golem) return golem;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helper: clear all memory keys from a golem's persistent data
    // -------------------------------------------------------------------------
    private void clearMemory(CopperGolem golem) {
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        pdc.remove(KEY_ITEM_TYPE);
        pdc.remove(KEY_CHEST_WORLD);
        pdc.remove(KEY_CHEST_X);
        pdc.remove(KEY_CHEST_Y);
        pdc.remove(KEY_CHEST_Z);
    }
}
