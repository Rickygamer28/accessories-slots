package io.github.accslots;

import io.wispforest.accessories.api.AccessoriesContainer;

import java.util.Map;

/** Added to Accessories' holder by AccessoriesHolderImplMixin: every container it has, including ones for slots that were removed. */
public interface HolderAccess {
    Map<String, AccessoriesContainer> accslots$allContainers();
}
