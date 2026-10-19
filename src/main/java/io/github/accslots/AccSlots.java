package io.github.accslots;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

@Mod(AccSlots.MOD_ID)
public final class AccSlots {

    public static final String MOD_ID = "accslots";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AccSlots(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(AccSlots::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(AccSlots::onLogin);
        NeoForge.EVENT_BUS.addListener(AccSlots::onRespawn);

        // Development-only end-to-end test (the "selftest" Gradle run). The class isn't in the released jar.
        if (Boolean.getBoolean("accslots.selftest")) {
            try {
                Class.forName("io.github.accslots.dev.SelfTest").getMethod("register").invoke(null);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[accslots] Could not start self-test", e);
            }
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        AccSlotsCommand.register(event.getDispatcher());
    }

    // Server-wide bonuses are re-applied whenever a player joins or respawns, so players who were
    // offline when /accslots global changed still end up with the right number of slots.
    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) scheduleGlobalSync(player);
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) scheduleGlobalSync(player);
    }

    private static void scheduleGlobalSync(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Wait one tick so Accessories has finished loading/initialising the player's containers.
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            if (player.isRemoved() || player.hasDisconnected()) return;
            try {
                SlotBonuses.returnItemsFromRemovedSlots(player);
                SlotBonuses.syncGlobal(player, GlobalSlotData.get(server).bonuses());
            } catch (Exception e) {
                LOGGER.error("[accslots] Failed to apply server-wide slot bonuses to {}", player.getGameProfile().getName(), e);
            }
        }));
    }
}
