package io.github.accslots;

import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.slot.ExtraSlotTypeProperties;
import io.wispforest.accessories.api.slot.SlotType;
import io.wispforest.accessories.data.EntitySlotLoader;
import io.wispforest.accessories.impl.AccessoriesHolderImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Extra slots are stored the same way Accessories itself resizes slots: as an ADD_VALUE
 * attribute modifier on the slot's container. Persistent modifiers are saved with the
 * player's data by Accessories and carried over on death/respawn.
 *
 * Two independent modifiers are used so per-player and server-wide bonuses stack:
 *   accslots:extra  - set with /accslots add|remove|set <targets> ...
 *   accslots:global - set with /accslots global ...
 */
public final class SlotBonuses {

    public static final ResourceLocation PLAYER_ID = AccSlots.id("extra");
    public static final ResourceLocation GLOBAL_ID = AccSlots.id("global");

    private SlotBonuses() {}

    @Nullable
    public static AccessoriesContainer container(LivingEntity entity, String slotName) {
        AccessoriesCapability capability = AccessoriesCapability.get(entity);
        if (capability == null) return null;
        return capability.getContainers().get(slotName);
    }

    public static boolean canResize(String slotName) {
        return ExtraSlotTypeProperties.getProperty(slotName, false).allowResizing();
    }

    public static int bonus(AccessoriesContainer container, ResourceLocation id) {
        AttributeModifier modifier = container.getModifiers().get(id);
        return modifier == null ? 0 : (int) Math.round(modifier.amount());
    }

    public static int baseAmount(AccessoriesContainer container) {
        SlotType type = container.slotType();
        return type == null ? 0 : type.amount();
    }

    /** Base amount + every flat (ADD_VALUE) modifier, i.e. the size the container will have after its next update. */
    public static int expectedSize(AccessoriesContainer container) {
        double size = baseAmount(container);
        for (AttributeModifier modifier : container.getModifiers().values()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) size += modifier.amount();
        }
        return Math.max(0, (int) Math.round(size));
    }

    /** What a bonus change did: the bonus actually applied and how many items were handed back. */
    public record Change(int applied, int returnedItems) {}

    /**
     * Sets one of our bonus modifiers, clamped so that base + our two bonuses never goes below zero
     * (a negative container size would crash Accessories).
     *
     * If the slot shrinks, anything in the slots that disappear (accessory and cosmetic) is taken out first and
     * given to the entity: into the player's inventory, or dropped at their feet if it's full.
     */
    public static Change setBonus(AccessoriesContainer container, ResourceLocation id, int desired) {
        ResourceLocation otherId = id.equals(PLAYER_ID) ? GLOBAL_ID : PLAYER_ID;
        int min = -(baseAmount(container) + bonus(container, otherId));
        int value = Math.max(desired, min);
        int current = bonus(container, id);

        if (current == value && (value != 0) == container.hasModifier(id)) return new Change(value, 0);

        int returned = 0;
        int newSize = Math.max(0, expectedSize(container) - current + value);
        if (newSize < container.getAccessories().getContainerSize()) {
            returned = returnItemsFrom(container, newSize);
        }

        // removeModifier first: adding a modifier with an existing id would double-count it.
        container.removeModifier(id);
        if (value != 0) {
            container.addPersistentModifier(new AttributeModifier(id, value, AttributeModifier.Operation.ADD_VALUE));
        }
        // markChanged() is called by Accessories inside add/remove; the container is resized and
        // synced to clients on the entity's next tick.
        return new Change(value, returned);
    }

    /**
     * Takes every item out of the container's slots from {@code fromIndex} upwards (accessories and cosmetics)
     * and gives them to the container's owner. Emptying the slots goes through Accessories' normal change
     * detection on the next tick, so unequip effects and item attribute modifiers are removed properly.
     */
    public static int returnItemsFrom(AccessoriesContainer container, int fromIndex) {
        LivingEntity owner = container.capability().entity();
        int returned = 0;
        for (Container inventory : List.of(container.getAccessories(), container.getCosmeticAccessories())) {
            for (int i = Math.max(0, fromIndex); i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                inventory.setItem(i, ItemStack.EMPTY);
                give(owner, stack);
                returned++;
            }
        }
        return returned;
    }

    /** Puts the stack in the player's inventory (dropping it at their feet if full), or drops it for other entities. */
    public static void give(LivingEntity owner, ItemStack stack) {
        if (owner instanceof ServerPlayer player) {
            ItemHandlerHelper.giveItemToPlayer(player, stack);
        } else {
            owner.spawnAtLocation(stack);
        }
    }

    /**
     * After a reload removes a slot type from an entity, Accessories keeps the old container on it (until the
     * player relogs) even though the slot no longer exists. Give back anything still in such containers, then drop
     * the leftover containers. Returns the number of item stacks returned.
     */
    public static int returnItemsFromRemovedSlots(LivingEntity entity) {
        AccessoriesCapability capability = AccessoriesCapability.get(entity);
        if (capability == null || !(capability.getHolder() instanceof HolderAccess holder)) return 0;

        Map<String, SlotType> valid = EntitySlotLoader.getEntitySlots(entity);
        Map<String, AccessoriesContainer> all = holder.accslots$allContainers();

        List<String> removed = all.keySet().stream().filter(name -> !valid.containsKey(name)).toList();
        if (removed.isEmpty()) return 0;

        int returned = 0;
        for (String name : removed) {
            returned += returnItemsFrom(all.get(name), 0);
            all.remove(name);
        }
        if (holder instanceof AccessoriesHolderImpl impl) impl.setValidTypes(valid.keySet());
        return returned;
    }

    /** Re-applies the saved server-wide bonuses to everyone online. Returns how many items were handed back. */
    public static int syncGlobalAll(MinecraftServer server) {
        Map<String, Integer> bonuses = GlobalSlotData.get(server).bonuses();
        int returned = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) returned += syncGlobal(player, bonuses);
        return returned;
    }

    /** Brings a player's accslots:global modifiers in line with the saved server-wide bonuses. Returns items handed back. */
    public static int syncGlobal(ServerPlayer player, Map<String, Integer> globalBonuses) {
        AccessoriesCapability capability = AccessoriesCapability.get(player);
        if (capability == null) return 0;

        int returned = 0;
        for (var entry : capability.getContainers().entrySet()) {
            String slot = entry.getKey();
            AccessoriesContainer container = entry.getValue();
            int desired = globalBonuses.getOrDefault(slot, 0);

            if (desired != 0 && !canResize(slot)) continue;
            if (desired == 0 && !container.hasModifier(GLOBAL_ID)) continue;

            returned += setBonus(container, GLOBAL_ID, desired).returnedItems();
        }
        return returned;
    }
}
