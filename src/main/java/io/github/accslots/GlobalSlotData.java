package io.github.accslots;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server-wide extra slots (slot name -> amount), saved in world/data/accslots_global.dat. */
public final class GlobalSlotData extends SavedData {

    private static final String FILE_NAME = "accslots_global";

    private static final SavedData.Factory<GlobalSlotData> FACTORY =
            new SavedData.Factory<>(GlobalSlotData::new, GlobalSlotData::load, null);

    private final Map<String, Integer> bonuses = new LinkedHashMap<>();

    public static GlobalSlotData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public Map<String, Integer> bonuses() {
        return Collections.unmodifiableMap(this.bonuses);
    }

    public int get(String slot) {
        return this.bonuses.getOrDefault(slot, 0);
    }

    public void set(String slot, int amount) {
        if (amount == 0) this.bonuses.remove(slot);
        else this.bonuses.put(slot, amount);
        this.setDirty();
    }

    public void clear() {
        this.bonuses.clear();
        this.setDirty();
    }

    private static GlobalSlotData load(CompoundTag tag, HolderLookup.Provider registries) {
        GlobalSlotData data = new GlobalSlotData();
        CompoundTag slots = tag.getCompound("slots");
        for (String key : slots.getAllKeys()) {
            int amount = slots.getInt(key);
            if (amount != 0) data.bonuses.put(key, amount);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag slots = new CompoundTag();
        this.bonuses.forEach(slots::putInt);
        tag.put("slots", slots);
        return tag;
    }
}
