package io.github.accslots.dev;

import com.mojang.authlib.GameProfile;
import io.github.accslots.AccSlots;
import io.github.accslots.GlobalSlotData;
import io.github.accslots.SlotBonuses;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.data.SlotTypeLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/**
 * Development-only end-to-end check, run by the "selftest" Gradle run (CI). Not included in the released jar.
 * Boots with Accessories, gives a test player extra ring slots, puts an item in one, creates a new slot through
 * the real command (which reloads data while the player exists) and checks nothing was lost.
 */
public final class SelfTest {

    private static int tick = 0;
    private static int step = 0;
    private static ServerPlayer player;
    private static int reloadWaitStart;

    private SelfTest() {}

    public static void register() {
        AccSlots.LOGGER.info("[accslots selftest] enabled");
        NeoForge.EVENT_BUS.addListener(SelfTest::onTick);
    }

    private static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tick++;
        try {
            run(server);
        } catch (Throwable t) {
            fail("exception: " + t, t);
        }
    }

    private static void run(MinecraftServer server) {
        switch (step) {
            case 0 -> {
                if (tick < 20) return;
                player = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("4c2a1d1e-0000-4000-8000-000000000001"), "accslots_test"));
                AccessoriesCapability cap = AccessoriesCapability.get(player);
                check(cap != null, "player has an accessories capability");
                AccessoriesContainer ring = cap.getContainers().get("ring");
                check(ring != null, "player has a ring slot");
                int base = ring.getSize();
                info("ring base size " + base + ", slots: " + cap.getContainers().keySet());

                SlotBonuses.setBonus(ring, SlotBonuses.PLAYER_ID, 2);
                cap.updateContainers();
                check(ring.getSize() == base + 2, "ring grew by 2 (" + ring.getSize() + ")");

                ring.getAccessories().setItem(base + 1, new ItemStack(Items.DIAMOND));
                check(ring.getAccessories().getItem(base + 1).is(Items.DIAMOND), "diamond placed in an extra ring slot");

                GlobalSlotData.get(server).set("ring", 1);
                SlotBonuses.syncGlobal(player, GlobalSlotData.get(server).bonuses());
                cap.updateContainers();
                check(ring.getSize() == base + 3, "server-wide bonus stacks (" + ring.getSize() + ")");

                int result = server.getCommands().getDispatcher()
                        .execute("accslots create selftest_slot 2", server.createCommandSourceStack());
                check(result == 1, "/accslots create returned 1");
                reloadWaitStart = tick;
                step = 1;
            }
            case 1 -> {
                if (!SlotTypeLoader.INSTANCE.getSlotTypes(false).containsKey("selftest_slot")) {
                    check(tick - reloadWaitStart < 20 * 60, "reload finished within 60s");
                    return;
                }
                info("reload applied, new slot registered after " + (tick - reloadWaitStart) + " ticks");
                step = 2;
            }
            case 2 -> {
                // This is the path that used to wipe containers: slot set changed -> Accessories re-inits the holder.
                AccessoriesCapability cap = AccessoriesCapability.get(player);
                var containers = cap.getContainers();
                info("slots after reload: " + containers.keySet());

                AccessoriesContainer created = containers.get("selftest_slot");
                check(created != null, "new slot is on the player without relogging");
                check(created.getSize() == 2, "new slot has 2 slots (" + created.getSize() + ")");

                AccessoriesContainer ring = containers.get("ring");
                check(ring != null, "ring slot still there");
                check(SlotBonuses.bonus(ring, SlotBonuses.PLAYER_ID) == 2, "per-player bonus kept");
                check(SlotBonuses.bonus(ring, SlotBonuses.GLOBAL_ID) == 1, "server-wide bonus kept");
                cap.updateContainers();
                int base = SlotBonuses.baseAmount(ring);
                check(ring.getSize() == base + 3, "ring size kept (" + ring.getSize() + ")");
                check(ring.getAccessories().getItem(base + 1).is(Items.DIAMOND), "diamond survived the reload");

                int result = server.getCommands().getDispatcher()
                        .execute("accslots allow selftest_slot item minecraft:stick", server.createCommandSourceStack());
                check(result == 1, "/accslots allow returned 1");

                info("ALL CHECKS PASSED");
                System.out.println("ACCSLOTS SELFTEST PASSED");
                step = 3;
                server.halt(false);
            }
            default -> {}
        }
    }

    private static void info(String msg) {
        AccSlots.LOGGER.info("[accslots selftest] {}", msg);
    }

    private static void check(boolean ok, String what) {
        if (!ok) fail("check failed: " + what, null);
        info("ok - " + what);
    }

    private static void fail(String msg, Throwable t) {
        AccSlots.LOGGER.error("[accslots selftest] FAILED: {}", msg, t);
        System.out.println("ACCSLOTS SELFTEST FAILED: " + msg);
        Runtime.getRuntime().halt(1);
    }
}
