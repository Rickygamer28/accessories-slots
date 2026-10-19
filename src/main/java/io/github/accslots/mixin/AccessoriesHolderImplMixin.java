package io.github.accslots.mixin;

import io.github.accslots.ContainerRebuild;
import io.github.accslots.HolderAccess;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.slot.SlotType;
import io.wispforest.accessories.impl.AccessoriesHolderImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * When the slots bound to an entity change (a datapack reload that adds or removes a slot), Accessories
 * re-runs {@code init}, which does {@code slotContainers.put(name, new container)} for every slot. That swaps
 * every existing container - with the items and slot modifiers in it - for an empty one.
 *
 * This keeps containers that already exist and only creates the missing ones, which is what Accessories
 * already does when loading a player from disk (putIfAbsent). Containers for slots that were removed are left
 * alone too, so Accessories' own reload handling can give their items back to the player.
 */
@Mixin(value = AccessoriesHolderImpl.class, remap = false)
public abstract class AccessoriesHolderImplMixin implements HolderAccess {

    @Shadow @Final private Map<String, AccessoriesContainer> slotContainers;

    @Override
    public Map<String, AccessoriesContainer> accslots$allContainers() {
        return this.slotContainers;
    }

    @Redirect(
            method = "init",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"),
            require = 1)
    private void accslots$keepExistingContainers(Map<String, SlotType> entitySlots, BiConsumer<? super String, ? super SlotType> action) {
        if (ContainerRebuild.isRebuilding()) {
            entitySlots.forEach(action);
            return;
        }

        entitySlots.forEach((name, slotType) -> {
            if (!this.slotContainers.containsKey(name)) action.accept(name, slotType);
        });
    }
}
