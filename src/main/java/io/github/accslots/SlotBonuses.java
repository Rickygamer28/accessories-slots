package io.github.accslots;

import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.slot.ExtraSlotTypeProperties;
import io.wispforest.accessories.api.slot.SlotType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Sets one of our bonus modifiers, clamped so that base + our two bonuses never goes below zero
     * (a negative container size would crash Accessories). Returns the value actually applied.
     */
    public static int setBonus(AccessoriesContainer container, ResourceLocation id, int desired) {
        ResourceLocation otherId = id.equals(PLAYER_ID) ? GLOBAL_ID : PLAYER_ID;
        int min = -(baseAmount(container) + bonus(container, otherId));
        int value = Math.max(desired, min);

        if (bonus(container, id) == value && (value != 0) == container.hasModifier(id)) return value;

        // removeModifier first: adding a modifier with an existing id would double-count it.
        container.removeModifier(id);
        if (value != 0) {
            container.addPersistentModifier(new AttributeModifier(id, value, AttributeModifier.Operation.ADD_VALUE));
        }
        // markChanged() is called by Accessories inside add/remove; the container is resized and
        // synced to clients on the entity's next tick.
        return value;
    }

    /** Brings a player's accslots:global modifiers in line with the saved server-wide bonuses. */
    public static void syncGlobal(ServerPlayer player, Map<String, Integer> globalBonuses) {
        AccessoriesCapability capability = AccessoriesCapability.get(player);
        if (capability == null) return;

        for (var entry : capability.getContainers().entrySet()) {
            String slot = entry.getKey();
            AccessoriesContainer container = entry.getValue();
            int desired = globalBonuses.getOrDefault(slot, 0);

            if (desired != 0 && !canResize(slot)) continue;
            if (desired == 0 && !container.hasModifier(GLOBAL_ID)) continue;

            setBonus(container, GLOBAL_ID, desired);
        }
    }
}
