package io.github.accslots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Structural changes (binding a slot to an entity type, defining a new slot, allowing items in a slot)
 * have to go through Accessories' data-driven files. This class keeps a small state file and regenerates
 * a world datapack from it:
 *
 *   world/datapacks/accslots_generated/
 *     pack.mcmeta
 *     accslots_state.json                                  (our own bookkeeping, ignored by Minecraft)
 *     data/accslots/accessories/entity/<target>.json       (entity bindings, merged with other mods' bindings)
 *     data/accslots/accessories/slot/<name>.json           (slots created with /accslots create)
 *     data/<ns>/tags/item/<slot>.json                      (items allowed with /accslots allow)
 *
 * After every change the datapacks are reloaded so it applies straight away (see {@link #reloadNow}).
 */
public final class GeneratedPack {

    public static final String PACK_FOLDER = "accslots_generated";
    /** World datapacks are registered with the id "file/<folder name>". */
    public static final String PACK_ID = "file/" + PACK_FOLDER;
    private static final String STATE_FILE = "accslots_state.json";
    private static final int PACK_FORMAT = 48; // 1.21 / 1.21.1 data packs

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** What /accslots has asked for. Plain collections so Gson can round-trip it. */
    public static final class State {
        /** entity id or #tag -> slot names bound to it */
        public LinkedHashMap<String, ArrayList<String>> bindings = new LinkedHashMap<>();
        /** slot name -> definition, for slots created by /accslots create */
        public LinkedHashMap<String, CreatedSlot> created = new LinkedHashMap<>();
        /** item tag id (e.g. accessories:ring) -> item ids or #tags */
        public LinkedHashMap<String, ArrayList<String>> allowed = new LinkedHashMap<>();

        public boolean isEmpty() {
            return bindings.isEmpty() && created.isEmpty() && allowed.isEmpty();
        }
    }

    public static final class CreatedSlot {
        public int amount;
        public String icon;
        public int order;

        public CreatedSlot() {}

        public CreatedSlot(int amount, String icon, int order) {
            this.amount = amount;
            this.icon = icon;
            this.order = order;
        }
    }

    private GeneratedPack() {}

    public static Path packDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_FOLDER);
    }

    public static State load(MinecraftServer server) {
        Path file = packDir(server).resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) return new State();

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            State state = GSON.fromJson(reader, State.class);
            if (state == null) return new State();
            if (state.bindings == null) state.bindings = new LinkedHashMap<>();
            if (state.created == null) state.created = new LinkedHashMap<>();
            if (state.allowed == null) state.allowed = new LinkedHashMap<>();
            return state;
        } catch (Exception e) {
            AccSlots.LOGGER.error("[accslots] Could not read {}, starting from an empty state", file, e);
            return new State();
        }
    }

    /** Writes the state file and regenerates every datapack file from it. */
    public static void save(MinecraftServer server, State state) throws IOException {
        Path root = packDir(server);
        Files.createDirectories(root);

        // Regenerate data/ from scratch so removed entries disappear. Only touches our own pack folder.
        Path data = root.resolve("data");
        if (Files.exists(data)) {
            try (Stream<Path> walk = Files.walk(data)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }

        JsonObject mcmeta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", PACK_FORMAT);
        pack.addProperty("description", "Slots added with /accslots (Accessories Slot Commands)");
        mcmeta.add("pack", pack);
        write(root.resolve("pack.mcmeta"), mcmeta);

        // Entity bindings: one file per target so each binds only its own slots.
        for (Map.Entry<String, ArrayList<String>> entry : state.bindings.entrySet()) {
            if (entry.getValue().isEmpty()) continue;

            JsonObject json = new JsonObject();
            json.addProperty("replace", false);
            JsonArray entities = new JsonArray();
            entities.add(entry.getKey());
            json.add("entities", entities);
            JsonArray slots = new JsonArray();
            entry.getValue().forEach(slots::add);
            json.add("slots", slots);

            write(root.resolve("data/accslots/accessories/entity/" + fileSafe(entry.getKey()) + ".json"), json);
        }

        // New slot definitions. A single-part path means the slot's name is exactly <name>.
        for (Map.Entry<String, CreatedSlot> entry : state.created.entrySet()) {
            CreatedSlot slot = entry.getValue();

            JsonObject json = new JsonObject();
            json.addProperty("amount", slot.amount);
            json.addProperty("operation", "set");
            json.addProperty("order", slot.order);
            json.addProperty("icon", slot.icon);
            JsonArray validators = new JsonArray();
            validators.add("accessories:tag");
            validators.add("accessories:component");
            json.add("validators", validators);

            write(root.resolve("data/accslots/accessories/slot/" + entry.getKey() + ".json"), json);
        }

        // Item tags that decide what can go in a slot (Accessories' "accessories:tag" validator).
        for (Map.Entry<String, ArrayList<String>> entry : state.allowed.entrySet()) {
            if (entry.getValue().isEmpty()) continue;

            String[] parts = entry.getKey().split(":", 2);
            String namespace = parts.length == 2 ? parts[0] : "minecraft";
            String path = parts.length == 2 ? parts[1] : parts[0];

            JsonObject json = new JsonObject();
            json.addProperty("replace", false);
            JsonArray values = new JsonArray();
            for (String value : entry.getValue()) {
                // required:false so the pack still loads if the item's mod is removed later.
                JsonObject v = new JsonObject();
                v.addProperty("id", value);
                v.addProperty("required", false);
                values.add(v);
            }
            json.add("values", values);

            write(root.resolve("data/" + namespace + "/tags/item/" + path + ".json"), json);
        }

        try (Writer writer = Files.newBufferedWriter(root.resolve(STATE_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(state, writer);
        }
    }

    /**
     * Reloads datapacks (like /reload), making sure our pack is enabled, then tells the source when done.
     * Safe for online players thanks to AccessoriesHolderImplMixin keeping their existing slot containers.
     */
    public static void reloadNow(CommandSourceStack source, Supplier<Component> onDone) {
        MinecraftServer server = source.getServer();
        PackRepository repository = server.getPackRepository();
        repository.reload(); // picks up the pack folder if it was just created

        List<String> selected = new ArrayList<>(repository.getSelectedIds());
        if (!selected.contains(PACK_ID) && repository.getAvailableIds().contains(PACK_ID)) selected.add(PACK_ID);

        server.reloadResources(selected)
                .thenRunAsync(() -> {
                    // Items in slot types that were just removed go back to their owners.
                    int returned = 0;
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        returned += SlotBonuses.returnItemsFromRemovedSlots(player);
                    }
                    // Newly bound slots start without the server-wide bonus; apply it now.
                    returned += SlotBonuses.syncGlobalAll(server);

                    source.sendSuccess(onDone, true);
                    AccSlotsCommand.sendReturned(source, returned);
                }, server)
                .exceptionally(error -> {
                    AccSlots.LOGGER.error("[accslots] Datapack reload failed", error);
                    source.sendFailure(Component.literal("Reloading data failed - check the server log. Your change is saved and will apply on the next start."));
                    return null;
                });
    }

    public static boolean addTo(Map<String, ArrayList<String>> map, String key, String value) {
        List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.contains(value)) return false;
        list.add(value);
        return true;
    }

    public static boolean removeFrom(Map<String, ArrayList<String>> map, String key, String value) {
        List<String> list = map.get(key);
        if (list == null || !list.remove(value)) return false;
        if (list.isEmpty()) map.remove(key);
        return true;
    }

    private static String fileSafe(String s) {
        return s.replace("#", "tag_").replaceAll("[^a-z0-9_.-]", "_");
    }

    private static void write(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
    }
}
