package io.github.accslots;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.slot.SlotType;
import io.wispforest.accessories.data.EntitySlotLoader;
import io.wispforest.accessories.data.SlotTypeLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * /accslots - manage Accessories slots.
 *
 * Live (no reload needed):
 *   /accslots list [<entity>]
 *   /accslots add|remove <targets> <slot> [<amount>]
 *   /accslots set <targets> <slot> <amount>
 *   /accslots reset <targets> [<slot>]
 *   /accslots global add|remove <slot> [<amount>]
 *   /accslots global set <slot> <amount>
 *   /accslots global reset [<slot>]
 *   /accslots global list
 *
 * Datapack-backed (written to world/datapacks/accslots_generated, applied immediately with a data reload):
 *   /accslots enable|disable <slot> [<entity_type>]
 *   /accslots create <name> [<amount>] [<icon>]
 *   /accslots delete <name>
 *   /accslots allow|disallow <slot> item <item>
 *   /accslots allow|disallow <slot> tag <item_tag>
 *   /accslots changes
 */
public final class AccSlotsCommand {

    private static final Pattern SLOT_NAME = Pattern.compile("[a-z0-9_]+");
    private static final ResourceLocation DEFAULT_ICON = ResourceLocation.fromNamespaceAndPath("accessories", "gui/slot/empty");
    private static final String PLAYER_TYPE = "minecraft:player";
    private static final int CREATED_SLOT_ORDER = 500;

    private static final List<String> BUILT_IN_ICONS = List.of(
            "anklet", "back", "belt", "cape", "charm", "empty", "face", "hand",
            "hat", "necklace", "ring", "shoes", "socks", "wrist");

    private static final DynamicCommandExceptionType UNKNOWN_SLOT = new DynamicCommandExceptionType(
            slot -> Component.literal("Unknown accessory slot '" + slot + "'. Use /accslots list to see every slot Accessories knows about."));
    private static final DynamicCommandExceptionType NOT_RESIZABLE = new DynamicCommandExceptionType(
            slot -> Component.literal("The slot '" + slot + "' doesn't allow resizing (locked by the mod that adds it)."));
    private static final DynamicCommandExceptionType NOT_BOUND = new DynamicCommandExceptionType(
            slot -> Component.literal("None of the targets have the '" + slot + "' slot. It exists, but isn't bound to that entity type - run /accslots enable " + slot + "."));
    private static final SimpleCommandExceptionType NO_LIVING_TARGETS = new SimpleCommandExceptionType(
            Component.literal("None of the targets can have accessories."));
    private static final DynamicCommandExceptionType BAD_NAME = new DynamicCommandExceptionType(
            name -> Component.literal("'" + name + "' isn't a valid slot name - use lowercase letters, numbers and _ only."));
    private static final DynamicCommandExceptionType SLOT_EXISTS = new DynamicCommandExceptionType(
            name -> Component.literal("A slot called '" + name + "' already exists. Use /accslots add or /accslots global add to give more of it."));
    private static final DynamicCommandExceptionType UNKNOWN_ENTITY = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown entity type '" + id + "'."));
    private static final DynamicCommandExceptionType UNKNOWN_ITEM = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown item '" + id + "'."));
    private static final DynamicCommandExceptionType NO_SLOT_TAG = new DynamicCommandExceptionType(
            slot -> Component.literal("Can't work out which item tag the '" + slot + "' slot uses; add items with a datapack instead."));
    private static final DynamicCommandExceptionType SAVE_FAILED = new DynamicCommandExceptionType(
            msg -> Component.literal("Couldn't write the datapack: " + msg));

    // Suggestions are computed on the server, so they work on dedicated servers too.
    private static final SuggestionProvider<CommandSourceStack> ALL_SLOTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(registeredSlots(ctx.getSource()).keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> SLOTS_AND_PENDING = (ctx, builder) -> {
        var names = new ArrayList<>(registeredSlots(ctx.getSource()).keySet());
        GeneratedPack.load(ctx.getSource().getServer()).created.keySet().forEach(n -> { if (!names.contains(n)) names.add(n); });
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> GLOBAL_SLOTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(GlobalSlotData.get(ctx.getSource().getServer()).bonuses().keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> CREATED_SLOTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(GeneratedPack.load(ctx.getSource().getServer()).created.keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> ENTITY_TYPES =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> ITEMS =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> ITEM_TAGS =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.getTagNames().map(TagKey::location), builder);

    private static final SuggestionProvider<CommandSourceStack> ICONS =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(
                    BUILT_IN_ICONS.stream().map(n -> ResourceLocation.fromNamespaceAndPath("accessories", "gui/slot/" + n)), builder);

    private AccSlotsCommand() {}

    private enum Op { ADD, REMOVE, SET }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("accslots")
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS));

        // --- live, per entity ---------------------------------------------------------------
        root.then(Commands.literal("list")
                .executes(ctx -> list(ctx.getSource(), ctx.getSource().getEntity()))
                .then(Commands.argument("entity", EntityArgument.entity())
                        .executes(ctx -> list(ctx.getSource(), EntityArgument.getEntity(ctx, "entity")))));

        root.then(Commands.literal("add").then(targetsSlotAmount(Op.ADD, true)));
        root.then(Commands.literal("remove").then(targetsSlotAmount(Op.REMOVE, true)));
        root.then(Commands.literal("set").then(targetsSlotAmount(Op.SET, false)));

        root.then(Commands.literal("reset")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> reset(ctx, null))
                        .then(slotArg(ALL_SLOTS)
                                .executes(ctx -> reset(ctx, slot(ctx))))));

        // --- live, server-wide --------------------------------------------------------------
        root.then(Commands.literal("global")
                .then(Commands.literal("list").executes(ctx -> globalList(ctx.getSource())))
                .then(Commands.literal("add").then(globalSlotAmount(Op.ADD, true)))
                .then(Commands.literal("remove").then(globalSlotAmount(Op.REMOVE, true)))
                .then(Commands.literal("set").then(globalSlotAmount(Op.SET, false)))
                .then(Commands.literal("reset")
                        .executes(ctx -> globalReset(ctx.getSource(), null))
                        .then(Commands.argument("slot", ResourceLocationArgument.id()).suggests(GLOBAL_SLOTS)
                                .executes(ctx -> globalReset(ctx.getSource(), slot(ctx))))));

        // --- datapack-backed (applied with a reload) ---------------------------------------------
        root.then(Commands.literal("enable")
                .then(slotArg(ALL_SLOTS)
                        .executes(ctx -> enable(ctx.getSource(), slot(ctx), PLAYER_TYPE, true))
                        .then(Commands.argument("entity_type", ResourceLocationArgument.id()).suggests(ENTITY_TYPES)
                                .executes(ctx -> enable(ctx.getSource(), slot(ctx), entityTypeArg(ctx), true)))));

        root.then(Commands.literal("disable")
                .then(slotArg(SLOTS_AND_PENDING)
                        .executes(ctx -> enable(ctx.getSource(), slot(ctx), PLAYER_TYPE, false))
                        .then(Commands.argument("entity_type", ResourceLocationArgument.id()).suggests(ENTITY_TYPES)
                                .executes(ctx -> enable(ctx.getSource(), slot(ctx), entityTypeArg(ctx), false)))));

        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "name"), 1, DEFAULT_ICON))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                        IntegerArgumentType.getInteger(ctx, "amount"), DEFAULT_ICON))
                                .then(Commands.argument("icon", ResourceLocationArgument.id()).suggests(ICONS)
                                        .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "amount"), ResourceLocationArgument.getId(ctx, "icon")))))));

        root.then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(CREATED_SLOTS)
                        .executes(ctx -> delete(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("allow").then(allowArgs(true)));
        root.then(Commands.literal("disallow").then(allowArgs(false)));

        root.then(Commands.literal("changes").executes(ctx -> changes(ctx.getSource())));

        dispatcher.register(root);
    }

    // ===================================================================== argument builders

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> slotArg(SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument("slot", ResourceLocationArgument.id()).suggests(suggestions);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> targetsSlotAmount(Op op, boolean amountOptional) {
        var amount = Commands.argument("amount", IntegerArgumentType.integer(op == Op.SET ? -1000 : 1, 1000))
                .executes(ctx -> modify(ctx, op, IntegerArgumentType.getInteger(ctx, "amount")));

        var slot = slotArg(ALL_SLOTS).then(amount);
        if (amountOptional) slot.executes(ctx -> modify(ctx, op, 1));

        return Commands.argument("targets", EntityArgument.entities()).then(slot);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> globalSlotAmount(Op op, boolean amountOptional) {
        var amount = Commands.argument("amount", IntegerArgumentType.integer(op == Op.SET ? -1000 : 1, 1000))
                .executes(ctx -> globalModify(ctx.getSource(), slot(ctx), op, IntegerArgumentType.getInteger(ctx, "amount")));

        var slot = slotArg(ALL_SLOTS).then(amount);
        if (amountOptional) slot.executes(ctx -> globalModify(ctx.getSource(), slot(ctx), op, 1));
        return slot;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> allowArgs(boolean allow) {
        return slotArg(SLOTS_AND_PENDING)
                .then(Commands.literal("item")
                        .then(Commands.argument("item", ResourceLocationArgument.id()).suggests(ITEMS)
                                .executes(ctx -> allow(ctx.getSource(), slot(ctx), itemArg(ctx), allow))))
                .then(Commands.literal("tag")
                        .then(Commands.argument("tag", ResourceLocationArgument.id()).suggests(ITEM_TAGS)
                                .executes(ctx -> allow(ctx.getSource(), slot(ctx), "#" + ResourceLocationArgument.getId(ctx, "tag"), allow))));
    }

    // ===================================================================== argument readers

    /** Accessories names its own slots without a namespace ("ring"); other mods may use "modid:slot". */
    private static String slot(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "slot");
        return id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE) ? id.getPath() : id.toString();
    }

    private static String entityTypeArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "entity_type");
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) throw UNKNOWN_ENTITY.create(id);
        return id.toString();
    }

    private static String itemArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "item");
        if (!BuiltInRegistries.ITEM.containsKey(id)) throw UNKNOWN_ITEM.create(id);
        return id.toString();
    }

    private static Map<String, SlotType> registeredSlots(CommandSourceStack source) {
        var map = new java.util.LinkedHashMap<>(SlotTypeLoader.getSlotTypes(source.getLevel()));
        map.remove("any");
        return map;
    }

    private static SlotType requireSlot(CommandSourceStack source, String slot) throws CommandSyntaxException {
        SlotType type = registeredSlots(source).get(slot);
        if (type == null) throw UNKNOWN_SLOT.create(slot);
        return type;
    }

    // ===================================================================== per-entity

    private static int modify(CommandContext<CommandSourceStack> ctx, Op op, int amount) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String slot = slot(ctx);
        requireSlot(source, slot);
        if (!SlotBonuses.canResize(slot)) throw NOT_RESIZABLE.create(slot);

        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");

        int changed = 0, missing = 0, living = 0, returned = 0;
        LivingEntity last = null;
        AccessoriesContainer lastContainer = null;

        for (Entity entity : targets) {
            if (!(entity instanceof LivingEntity livingEntity)) continue;
            living++;

            AccessoriesContainer container = SlotBonuses.container(livingEntity, slot);
            if (container == null) { missing++; continue; }

            int current = SlotBonuses.bonus(container, SlotBonuses.PLAYER_ID);
            int desired = switch (op) {
                case ADD -> current + amount;
                case REMOVE -> current - amount;
                case SET -> amount;
            };
            returned += SlotBonuses.setBonus(container, SlotBonuses.PLAYER_ID, desired).returnedItems();

            changed++;
            last = livingEntity;
            lastContainer = container;
        }

        if (living == 0) throw NO_LIVING_TARGETS.create();
        if (changed == 0) throw NOT_BOUND.create(slot);

        if (changed == 1) {
            LivingEntity who = last;
            AccessoriesContainer c = lastContainer;
            source.sendSuccess(() -> Component.literal("")
                    .append(Component.literal(who.getName().getString()).withStyle(ChatFormatting.AQUA))
                    .append(" now has ")
                    .append(Component.literal(String.valueOf(SlotBonuses.expectedSize(c))).withStyle(ChatFormatting.GREEN))
                    .append(" ")
                    .append(slotName(slot))
                    .append(" slot(s) ")
                    .append(breakdown(c)), true);
        } else {
            int n = changed;
            source.sendSuccess(() -> Component.literal("Updated ").append(slotName(slot))
                    .append(" slots for " + n + " entities."), true);
        }

        sendReturned(source, returned);

        if (missing > 0) {
            int m = missing;
            source.sendSystemMessage(Component.literal(m + " target(s) skipped: they don't have the '" + slot
                    + "' slot (see /accslots enable).").withStyle(ChatFormatting.YELLOW));
        }
        return changed;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, @Nullable String slot) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (slot != null) requireSlot(source, slot);

        int count = 0, returned = 0;
        for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
            if (!(entity instanceof LivingEntity living)) continue;
            AccessoriesCapability capability = AccessoriesCapability.get(living);
            if (capability == null) continue;

            for (var entry : capability.getContainers().entrySet()) {
                if (slot != null && !slot.equals(entry.getKey())) continue;
                if (!entry.getValue().hasModifier(SlotBonuses.PLAYER_ID)) continue;
                returned += SlotBonuses.setBonus(entry.getValue(), SlotBonuses.PLAYER_ID, 0).returnedItems();
            }
            count++;
        }

        int n = count;
        String what = slot == null ? "all" : "'" + slot + "'";
        source.sendSuccess(() -> Component.literal("Removed " + what + " extra slots from /accslots for " + n + " entit" + (n == 1 ? "y" : "ies")
                + ". (Server-wide bonuses from /accslots global still apply.)"), true);
        sendReturned(source, returned);
        return n;
    }

    private static int list(CommandSourceStack source, @Nullable Entity entity) {
        Map<String, SlotType> all = registeredSlots(source);

        if (entity instanceof LivingEntity living && AccessoriesCapability.get(living) != null) {
            AccessoriesCapability capability = AccessoriesCapability.get(living);
            Map<String, AccessoriesContainer> containers = capability.getContainers();

            MutableComponent msg = Component.literal("Accessory slots for ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(living.getName().getString()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" (" + containers.size() + "):").withStyle(ChatFormatting.GOLD));

            for (var entry : containers.entrySet()) {
                AccessoriesContainer c = entry.getValue();
                msg.append("\n ").append(slotName(entry.getKey()).withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/accslots add @s " + entry.getKey() + " 1"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to add slots")))))
                        .append(": ")
                        .append(Component.literal(String.valueOf(SlotBonuses.expectedSize(c))).withStyle(ChatFormatting.GREEN))
                        .append(" ").append(breakdown(c));
                if (!SlotBonuses.canResize(entry.getKey())) msg.append(Component.literal(" [locked]").withStyle(ChatFormatting.DARK_GRAY));
            }

            List<String> unbound = all.keySet().stream().filter(name -> !containers.containsKey(name)).toList();
            if (!unbound.isEmpty()) {
                msg.append(Component.literal("\nRegistered by mods but not on this entity (click to enable):").withStyle(ChatFormatting.GOLD));
                for (String name : unbound) {
                    msg.append("\n ").append(slotName(name).withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/accslots enable " + name
                                    + (living.getType() == EntityType.PLAYER ? "" : " " + BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()))))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to bind this slot")))));
                }
            }

            source.sendSuccess(() -> msg, false);
            return containers.size();
        }

        // No entity (e.g. the server console): list every registered slot and whether players have it.
        Map<String, SlotType> onPlayers = EntitySlotLoader.getEntitySlots(source.getLevel(), EntityType.PLAYER);
        MutableComponent msg = Component.literal("Registered accessory slots (" + all.size() + "):").withStyle(ChatFormatting.GOLD);
        for (var entry : all.entrySet()) {
            msg.append("\n ").append(slotName(entry.getKey()))
                    .append(Component.literal(" base " + entry.getValue().amount()).withStyle(ChatFormatting.GRAY))
                    .append(onPlayers.containsKey(entry.getKey())
                            ? Component.literal(" [on players]").withStyle(ChatFormatting.GREEN)
                            : Component.literal(" [not on players]").withStyle(ChatFormatting.DARK_GRAY));
        }
        source.sendSuccess(() -> msg, false);
        return all.size();
    }

    // ===================================================================== server-wide

    private static int globalModify(CommandSourceStack source, String slot, Op op, int amount) throws CommandSyntaxException {
        requireSlot(source, slot);
        if (!SlotBonuses.canResize(slot)) throw NOT_RESIZABLE.create(slot);

        MinecraftServer server = source.getServer();
        GlobalSlotData data = GlobalSlotData.get(server);

        int value = switch (op) {
            case ADD -> data.get(slot) + amount;
            case REMOVE -> data.get(slot) - amount;
            case SET -> amount;
        };
        data.set(slot, value);

        OnlineSync sync = syncAllOnline(server, slot);

        source.sendSuccess(() -> Component.literal("Server-wide bonus for ").append(slotName(slot))
                .append(" is now ").append(Component.literal(signed(value)).withStyle(ChatFormatting.GREEN))
                .append(" (applied to " + sync.affected() + " online player(s); others get it when they join)."), true);
        sendReturned(source, sync.returned());

        boolean boundToPlayers = EntitySlotLoader.getEntitySlots(source.getLevel(), EntityType.PLAYER).containsKey(slot);
        if (!boundToPlayers) {
            source.sendSystemMessage(Component.literal("Note: players don't have the '" + slot
                    + "' slot yet. Run /accslots enable " + slot + " and it will apply.").withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    private static int globalReset(CommandSourceStack source, @Nullable String slot) {
        MinecraftServer server = source.getServer();
        GlobalSlotData data = GlobalSlotData.get(server);

        if (slot == null) data.clear();
        else data.set(slot, 0);

        OnlineSync sync = syncAllOnline(server, slot);

        String what = slot == null ? "All server-wide slot bonuses" : "The server-wide bonus for '" + slot + "'";
        source.sendSuccess(() -> Component.literal(what + " removed."), true);
        sendReturned(source, sync.returned());
        return 1;
    }

    private static int globalList(CommandSourceStack source) {
        Map<String, Integer> bonuses = GlobalSlotData.get(source.getServer()).bonuses();
        if (bonuses.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No server-wide slot bonuses set."), false);
            return 0;
        }
        MutableComponent msg = Component.literal("Server-wide slot bonuses:").withStyle(ChatFormatting.GOLD);
        bonuses.forEach((slot, amount) -> msg.append("\n ").append(slotName(slot)).append(": ")
                .append(Component.literal(signed(amount)).withStyle(ChatFormatting.GREEN)));
        source.sendSuccess(() -> msg, false);
        return bonuses.size();
    }

    private record OnlineSync(int affected, int returned) {}

    /** Re-applies global bonuses to everyone online; counts players with the slot (or all, if null) and items handed back. */
    private static OnlineSync syncAllOnline(MinecraftServer server, @Nullable String slot) {
        Map<String, Integer> bonuses = GlobalSlotData.get(server).bonuses();
        int affected = 0, returned = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            returned += SlotBonuses.syncGlobal(player, bonuses);
            if (slot == null || SlotBonuses.container(player, slot) != null) affected++;
        }
        return new OnlineSync(affected, returned);
    }

    /** Tells the source when items had to be taken out of slots that no longer exist. */
    static void sendReturned(CommandSourceStack source, int returned) {
        if (returned <= 0) return;
        source.sendSuccess(() -> Component.literal("Returned " + returned + " item(s) from removed slots to their owner"
                + " (inventory, or dropped at their feet if full).").withStyle(ChatFormatting.GRAY), true);
    }

    // ===================================================================== datapack-backed

    private static int enable(CommandSourceStack source, String slot, String entityType, boolean enable) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        GeneratedPack.State state = GeneratedPack.load(server);
        boolean pendingCreated = state.created.containsKey(slot);

        if (enable) {
            if (!pendingCreated) requireSlot(source, slot);

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(entityType));
            if (EntitySlotLoader.getEntitySlots(source.getLevel(), type).containsKey(slot)) {
                source.sendSuccess(() -> Component.literal("").append(slotName(slot))
                        .append(" is already on " + entityType + ". Use /accslots add or /accslots global add to give more."), false);
                return 0;
            }
            if (!GeneratedPack.addTo(state.bindings, entityType, slot)) {
                source.sendSuccess(() -> Component.literal("").append(slotName(slot))
                        .append(" is already enabled for " + entityType + " by /accslots - run /reload if it isn't showing."), false);
                return 0;
            }
        } else {
            if (!GeneratedPack.removeFrom(state.bindings, entityType, slot)) {
                source.sendFailure(Component.literal("/accslots never enabled '" + slot + "' for " + entityType
                        + ". It can only undo bindings it made itself (other mods' bindings come from their own data)."));
                return 0;
            }
        }

        String verb = enable ? " added to " : " removed from ";
        saveAndApply(source, state, () -> Component.literal("").append(slotName(slot)).append(verb + entityType + "."));
        return 1;
    }

    private static int create(CommandSourceStack source, String name, int amount, ResourceLocation icon) throws CommandSyntaxException {
        if (!SLOT_NAME.matcher(name).matches() || name.equals("any")) throw BAD_NAME.create(name);

        MinecraftServer server = source.getServer();
        GeneratedPack.State state = GeneratedPack.load(server);
        if (registeredSlots(source).containsKey(name) || state.created.containsKey(name)) throw SLOT_EXISTS.create(name);

        state.created.put(name, new GeneratedPack.CreatedSlot(amount, icon.toString(), CREATED_SLOT_ORDER));
        GeneratedPack.addTo(state.bindings, PLAYER_TYPE, name);
        saveAndApply(source, state, () -> Component.literal("Created slot ").append(slotName(name))
                .append(" with " + amount + " slot(s) for players.")
                .append(Component.literal("\nItems go in it if they're in the item tag #accessories:" + name
                        + " - add some with /accslots allow " + name + " item <item>.").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\nIts label uses the translation key accessories.slot." + name
                        + " (add it in a resource pack to give it a nice name).").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int delete(CommandSourceStack source, String name) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        GeneratedPack.State state = GeneratedPack.load(server);

        if (state.created.remove(name) == null) {
            source.sendFailure(Component.literal("'" + name + "' wasn't created with /accslots create."));
            return 0;
        }
        state.bindings.values().forEach(list -> list.remove(name));
        state.bindings.values().removeIf(List::isEmpty);
        state.allowed.remove("accessories:" + name);
        saveAndApply(source, state, () -> Component.literal("Slot '" + name + "' removed. Anything that was in it was given back to its owner."));
        return 1;
    }

    private static int allow(CommandSourceStack source, String slot, String value, boolean allow) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        GeneratedPack.State state = GeneratedPack.load(server);

        String tag = slotTag(source, state, slot);
        boolean changed = allow ? GeneratedPack.addTo(state.allowed, tag, value) : GeneratedPack.removeFrom(state.allowed, tag, value);

        if (!changed) {
            source.sendFailure(Component.literal(allow
                    ? value + " is already allowed in '" + slot + "'."
                    : value + " wasn't added to '" + slot + "' by /accslots."));
            return 0;
        }
        saveAndApply(source, state, () -> Component.literal(value + (allow ? " can now go in " : " can no longer go in "))
                .append(slotName(slot)).append(" (tag #" + tag + ")."));
        return 1;
    }

    private static String slotTag(CommandSourceStack source, GeneratedPack.State state, String slot) throws CommandSyntaxException {
        SlotType type = registeredSlots(source).get(slot);
        if (type != null) {
            try {
                return AccessoriesAPI.getSlotTag(type).location().toString();
            } catch (Exception e) {
                throw NO_SLOT_TAG.create(slot);
            }
        }
        if (state.created.containsKey(slot)) return "accessories:" + slot;
        throw UNKNOWN_SLOT.create(slot);
    }

    private static int changes(CommandSourceStack source) {
        GeneratedPack.State state = GeneratedPack.load(source.getServer());
        if (state.isEmpty()) {
            source.sendSuccess(() -> Component.literal("/accslots hasn't enabled, created or allowed anything yet."), false);
            return 0;
        }

        MutableComponent msg = Component.literal("accslots datapack (world/datapacks/" + GeneratedPack.PACK_FOLDER + "):").withStyle(ChatFormatting.GOLD);
        state.created.forEach((name, def) -> msg.append("\n created ").append(slotName(name))
                .append(Component.literal(" x" + def.amount + ", icon " + def.icon).withStyle(ChatFormatting.GRAY)));
        state.bindings.forEach((entity, slots) -> msg.append("\n " + entity + ": ")
                .append(Component.literal(String.join(", ", slots)).withStyle(ChatFormatting.YELLOW)));
        state.allowed.forEach((tag, values) -> msg.append("\n #" + tag + ": ")
                .append(Component.literal(String.join(", ", values)).withStyle(ChatFormatting.GRAY)));

        source.sendSuccess(() -> msg, false);
        return 1;
    }

    // ===================================================================== helpers

    private static void saveOrThrow(MinecraftServer server, GeneratedPack.State state) throws CommandSyntaxException {
        try {
            GeneratedPack.save(server, state);
        } catch (IOException e) {
            AccSlots.LOGGER.error("[accslots] Failed to write datapack", e);
            throw SAVE_FAILED.create(e.getMessage());
        }
    }

    private static MutableComponent slotName(String slot) {
        return Component.literal(slot).withStyle(ChatFormatting.YELLOW);
    }

    private static MutableComponent breakdown(AccessoriesContainer c) {
        int base = SlotBonuses.baseAmount(c);
        int player = SlotBonuses.bonus(c, SlotBonuses.PLAYER_ID);
        int global = SlotBonuses.bonus(c, SlotBonuses.GLOBAL_ID);
        int other = SlotBonuses.expectedSize(c) - base - player - global;

        var parts = new ArrayList<String>();
        parts.add("base " + base);
        if (player != 0) parts.add(signed(player) + " player");
        if (global != 0) parts.add(signed(global) + " server");
        if (other != 0) parts.add(signed(other) + " items/other mods");
        return Component.literal("(" + String.join(", ", parts) + ")").withStyle(ChatFormatting.GRAY);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /** Writes the datapack, then reloads data so the change applies right away. */
    private static void saveAndApply(CommandSourceStack source, GeneratedPack.State state, Supplier<Component> done) throws CommandSyntaxException {
        saveOrThrow(source.getServer(), state);
        source.sendSystemMessage(Component.literal("Applying...").withStyle(ChatFormatting.GRAY));
        GeneratedPack.reloadNow(source, done);
    }
}
