package fr.nexnpc.fabric.entity;

import com.mojang.serialization.Codec;
import fr.nexnpc.fabric.NexNpcMod;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Durable ID reservation index; NPC configuration itself remains on each entity. */
public final class NpcIdIndex extends SavedData {
    private static final Codec<NpcIdIndex> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING)
            .xmap(NpcIdIndex::new, index -> Map.copyOf(index.ids));
    private static final SavedDataType<NpcIdIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(NexNpcMod.MOD_ID, "npc_ids"),
            NpcIdIndex::new,
            CODEC,
            null
    );

    private final Map<String, String> ids = new LinkedHashMap<>();

    private NpcIdIndex(Map<String, String> data) {
        data.forEach((id, uuid) -> ids.put(normalize(id), uuid));
    }

    public NpcIdIndex() {
    }

    public static NpcIdIndex get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public UUID owner(String id) {
        String value = ids.get(normalize(id));
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public Map<String, String> entries() {
        return Map.copyOf(ids);
    }

    public boolean claim(String id, UUID uuid) {
        String key = normalize(id);
        String value = uuid.toString();
        String existing = ids.putIfAbsent(key, value);
        if (existing != null && !existing.equals(value)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean rename(String oldId, String newId, UUID uuid) {
        String oldKey = normalize(oldId);
        String newKey = normalize(newId);
        String uuidText = uuid.toString();
        String owner = ids.get(newKey);
        if (owner != null && !owner.equals(uuidText)) {
            return false;
        }
        if (!uuidText.equals(ids.get(oldKey))) {
            return false;
        }
        ids.remove(oldKey);
        ids.put(newKey, uuidText);
        setDirty();
        return true;
    }

    public void release(String id, UUID uuid) {
        String key = normalize(id);
        if (uuid.toString().equals(ids.get(key))) {
            ids.remove(key);
            setDirty();
        }
    }

    private static String normalize(String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
