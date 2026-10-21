package fr.nexnpc.fabric.entity;

import fr.nexnpc.fabric.NexNpcMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/** Metadata NexNPC stored on the native mannequin entity itself. */
public final class NpcEntityData {
    public static final String MARKER = "nexnpc:npc";
    private static final String PREFIX = "nexnpc:";
    private static final String ID = "id";
    private static final String DISPLAY_NAME = "display";
    private static final String SKIN = "skin";
    private static final String ACTION = "action";
    private static final String ACTION_VALUE = "value";
    private static final String LOOK = "look";

    private NpcEntityData() {
    }

    public static boolean isNpc(Entity entity) {
        return entity instanceof Mannequin && entity.entityTags().contains(MARKER);
    }

    public static String getId(Entity entity) {
        return getValue(entity, ID).orElse("");
    }

    public static void setId(Entity entity, String id) {
        setValue(entity, ID, id.toLowerCase(Locale.ROOT));
    }

    public static String getDisplayName(Entity entity) {
        return decode(getValue(entity, DISPLAY_NAME).orElse(encode(getId(entity))));
    }

    public static void setDisplayName(Entity entity, String displayName) {
        setValue(entity, DISPLAY_NAME, encode(displayName));
    }

    public static String getSkinName(Entity entity) {
        return getValue(entity, SKIN).orElse("Steve");
    }

    public static void setSkinName(Entity entity, String skinName) {
        setValue(entity, SKIN, skinName);
    }

    public static NpcActionType getActionType(Entity entity) {
        try {
            return NpcActionType.valueOf(getValue(entity, ACTION).orElse("NONE"));
        } catch (IllegalArgumentException exception) {
            return NpcActionType.NONE;
        }
    }

    public static String getActionValue(Entity entity) {
        return decode(getValue(entity, ACTION_VALUE).orElse(""));
    }

    public static void setAction(Entity entity, NpcActionType type, String value) {
        setValue(entity, ACTION, type.name());
        setValue(entity, ACTION_VALUE, encode(type == NpcActionType.NONE ? "" : value));
    }

    public static boolean shouldLookAtNearestPlayer(Entity entity) {
        return getValue(entity, LOOK).map("1"::equals).orElse(false);
    }

    public static void setLookAtNearestPlayer(Entity entity, boolean enabled) {
        setValue(entity, LOOK, enabled ? "1" : "0");
    }

    public static void initialize(Mannequin mannequin, String id, String displayName, String skinName) {
        mannequin.addTag(MARKER);
        setId(mannequin, id);
        setDisplayName(mannequin, displayName);
        setSkinName(mannequin, skinName);
        setAction(mannequin, NpcActionType.NONE, "");
        setLookAtNearestPlayer(mannequin, false);
        mannequin.setCustomNameVisible(true);
        mannequin.setCustomName(Component.literal(displayName));
        mannequin.setInvulnerable(true);
        mannequin.setNoGravity(true);
        mannequin.setDeltaMovement(0.0D, 0.0D, 0.0D);
        updateNativeProperties(mannequin, skinName, true);
    }

    /**
     * Mannequin 26.1.2 exposes its profile and immovable flag as native entity data,
     * but not public setters. Reloading its normal serialized entity data invokes
     * Mojang's own readAdditionalSaveData path and updates the synchronized fields.
     */
    public static void updateNativeProperties(Mannequin mannequin, String skinName, boolean immovable) {
        HolderLookup.Provider registries = mannequin.level().registryAccess();
        TagValueOutput entityOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        if (!mannequin.save(entityOutput)) {
            throw new IllegalStateException("Minecraft n'a pas pu sérialiser le mannequin " + mannequin.getUUID());
        }
        CompoundTag entityData = entityOutput.buildResult();

        TagValueOutput profileOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        profileOutput.store("profile", ResolvableProfile.CODEC, ResolvableProfile.createUnresolved(skinName));
        CompoundTag encodedProfile = profileOutput.buildResult();
        net.minecraft.nbt.Tag profile = encodedProfile.get("profile");
        if (profile == null) {
            throw new IllegalStateException("Le profil du mannequin n'a pas pu être encodé");
        }
        entityData.put("profile", profile);
        entityData.putBoolean("immovable", immovable);

        mannequin.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, entityData));
        mannequin.setInvulnerable(true);
        mannequin.setNoGravity(true);
    }

    public static void applySkin(Mannequin mannequin, String skinName) {
        updateNativeProperties(mannequin, skinName, true);
        setSkinName(mannequin, skinName);
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static Optional<String> getValue(Entity entity, String key) {
        String prefix = PREFIX + key + ":";
        return entity.entityTags().stream()
                .filter(tag -> tag.startsWith(prefix))
                .map(tag -> tag.substring(prefix.length()))
                .findFirst();
    }

    private static void setValue(Entity entity, String key, String value) {
        String prefix = PREFIX + key + ":";
        entity.entityTags().removeIf(tag -> tag.startsWith(prefix));
        if (!entity.addTag(prefix + value)) {
            NexNpcMod.LOGGER.warn("Impossible d'enregistrer le champ {} du NPC {} dans ses tags natifs", key, entity.getUUID());
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            // Accept plain text tags from early development builds.
            return value;
        }
    }
}
