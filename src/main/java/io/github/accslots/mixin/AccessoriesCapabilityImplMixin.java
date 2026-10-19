package io.github.accslots.mixin;

import io.github.accslots.ContainerRebuild;
import io.wispforest.accessories.impl.AccessoriesCapabilityImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * reset(false) is Accessories' intentional full rebuild: it creates fresh containers and copies the old items
 * into them. Mark it so {@link AccessoriesHolderImplMixin} lets that one case replace containers as before.
 */
@Mixin(value = AccessoriesCapabilityImpl.class, remap = false)
public abstract class AccessoriesCapabilityImplMixin {

    @Inject(method = "reset", at = @At("HEAD"))
    private void accslots$beginReset(boolean loadedFromTag, CallbackInfo ci) {
        if (!loadedFromTag) ContainerRebuild.begin();
    }

    @Inject(method = "reset", at = @At("RETURN"))
    private void accslots$endReset(boolean loadedFromTag, CallbackInfo ci) {
        if (!loadedFromTag) ContainerRebuild.end();
    }
}
