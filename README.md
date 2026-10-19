# Accessories Slot Commands

An addon for [Accessories](https://www.curseforge.com/minecraft/mc-mods/accessories) (NeoForge, Minecraft 1.21.1) that adds the `/accslots` command for giving players more accessory slots — including slots that come from **other mods** (anything registered with Accessories, e.g. slots from mods built on Accessories, or Curios slots when the Curios compat layer is installed).

Server-side only: install it on the server (or in your own game for singleplayer). Clients without it can still join. Requires Accessories (and owo-lib, which Accessories already needs). All commands need operator permission (level 2).

## Live commands (take effect immediately)

| Command | What it does |
|---|---|
| `/accslots list [<entity>]` | Shows every slot the entity has, with a breakdown (base / per-player / server-wide / from items), plus slots registered by mods that the entity *doesn't* have. Click a slot to fill in a command. From the console it lists every registered slot. |
| `/accslots add <targets> <slot> [amount]` | Give the targets extra slots of that type (default 1). |
| `/accslots remove <targets> <slot> [amount]` | Take extra slots away again. |
| `/accslots set <targets> <slot> <amount>` | Set the per-player bonus directly (can be negative to go below the default). |
| `/accslots reset <targets> [<slot>]` | Remove per-player bonuses. |
| `/accslots global add\|remove\|set <slot> [amount]` | Server-wide bonus for **every** player, including people who join later. |
| `/accslots global reset [<slot>]` / `global list` | Clear / show server-wide bonuses. |

Examples:
```
/accslots add @s ring 2          # two extra ring slots for you
/accslots global add charm 1     # everyone gets one more charm slot
/accslots add Steve back 1
```

Per-player and server-wide bonuses stack. They're stored the same way Accessories resizes slots itself (attribute modifiers on the slot), so they're saved with the player and kept through death. If slots are removed, items that no longer fit are given back to the player.

## Datapack commands (apply on the next world/server start)

Some changes have to go through Accessories' data-driven files: making a slot exist on an entity at all, creating a brand-new slot, or choosing which items fit. These commands write a datapack to `world/datapacks/accslots_generated/` that loads on the next start.

| Command | What it does |
|---|---|
| `/accslots enable <slot> [<entity_type>]` | Bind a slot to players (or another entity type). Use this for slots another mod registers but doesn't put on players. |
| `/accslots disable <slot> [<entity_type>]` | Undo a binding made with `enable`. |
| `/accslots create <name> [amount] [icon]` | Create a brand-new slot type and give it to players. Icon defaults to `accessories:gui/slot/empty`; any of the built-in `accessories:gui/slot/...` icons are suggested. |
| `/accslots delete <name>` | Remove a slot made with `create`. |
| `/accslots allow <slot> item <item>` | Let an item (from any mod) be equipped in that slot. |
| `/accslots allow <slot> tag <item_tag>` | Same, for a whole item tag. |
| `/accslots disallow <slot> item\|tag <id>` | Undo an `allow`. |
| `/accslots pending` | Show what's in the generated datapack. |

Why a restart instead of `/reload`? When the set of slots on an online player changes, Accessories rebuilds that player's slot containers, which can lose equipped items. Restarting avoids that. (Singleplayer: Save & Quit, then reopen the world.)

A created slot's label uses the translation key `accessories.slot.<name>`; add it in a resource pack's `en_us.json` to give it a nice name.

## Building

```
./gradlew build
```
The jar ends up in `build/libs/`. Every push to this repo also builds it on GitHub Actions — download it from the run's **Artifacts**.

To match the Accessories version in your modpack, change `accessories_version` in `gradle.properties`.
