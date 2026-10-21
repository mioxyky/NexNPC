package fr.nexnpc.fabric.entity;

import fr.nexnpc.fabric.NexNpcMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

import java.util.Locale;

/** Rebuilds the ID index when vanilla mannequins carrying NexNPC tags load. */
public final class NpcEntityLoadHandler {
    private NpcEntityLoadHandler() {
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mannequin mannequin && NpcEntityData.isNpc(mannequin)) {
                registerLoadedNpc(mannequin, level);
            }
        });
    }

    private static void registerLoadedNpc(Mannequin entity, ServerLevel level) {
        String id = NpcEntityData.getId(entity);
        if (!NpcEntityData.isValidId(id)) {
            id = "npc-" + entity.getUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
            NpcEntityData.setId(entity, id);
            NexNpcMod.LOGGER.warn("Un mannequin NexNPC sans ID valide a reçu l'ID {}", id);
        }

        NpcIdIndex index = NpcIdIndex.get(level.getServer());
        if (!index.claim(id, entity.getUUID())) {
            String oldId = id;
            id = reserveUniqueId(index, id, entity);
            NpcEntityData.setId(entity, id);
            NexNpcMod.LOGGER.warn("ID NPC dupliqué {} détecté au chargement; l'entité {} a reçu {}", oldId, entity.getUUID(), id);
        }

        entity.setInvulnerable(true);
        entity.setNoGravity(true);
        entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
    }

    private static String reserveUniqueId(NpcIdIndex index, String baseId, Entity entity) {
        for (int suffix = 2; suffix < 100_000; suffix++) {
            String tail = "-" + suffix;
            String candidate = baseId.length() + tail.length() <= 32
                    ? baseId + tail
                    : baseId.substring(0, 32 - tail.length()) + tail;
            if (index.claim(candidate, entity.getUUID())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Impossible de générer un ID unique pour " + entity.getUUID());
    }
}
