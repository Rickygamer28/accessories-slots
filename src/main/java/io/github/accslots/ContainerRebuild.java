package io.github.accslots;

/**
 * Tracks when Accessories is deliberately rebuilding an entity's slot containers
 * (AccessoriesCapabilityImpl#reset(false), which copies the items over itself). Outside of that,
 * {@code AccessoriesHolderImplMixin} stops Accessories from replacing existing containers with empty ones.
 */
public final class ContainerRebuild {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private ContainerRebuild() {}

    public static void begin() {
        DEPTH.get()[0]++;
    }

    public static void end() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    public static boolean isRebuilding() {
        return DEPTH.get()[0] > 0 || Boolean.getBoolean("accslots.selftest.nofix");
    }
}
