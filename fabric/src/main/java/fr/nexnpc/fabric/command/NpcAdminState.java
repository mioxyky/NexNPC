package fr.nexnpc.fabric.command;

import fr.nexnpc.fabric.entity.NpcEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcAdminState {
    private static final Map<UUID, UUID> SELECTED_NPCS = new ConcurrentHashMap<>();
    private static final Set<UUID> CLICK_SELECTION_MODE = ConcurrentHashMap.newKeySet();

    private NpcAdminState() {
    }

    public static void select(ServerPlayer player, NpcEntity npc) {
        SELECTED_NPCS.put(player.getUUID(), npc.getUUID());
        CLICK_SELECTION_MODE.remove(player.getUUID());
    }

    public static UUID getSelectedId(ServerPlayer player) {
        return SELECTED_NPCS.get(player.getUUID());
    }

    public static void clear(ServerPlayer player) {
        SELECTED_NPCS.remove(player.getUUID());
        CLICK_SELECTION_MODE.remove(player.getUUID());
    }

    public static boolean toggleClickSelection(ServerPlayer player) {
        UUID id = player.getUUID();
        if (CLICK_SELECTION_MODE.remove(id)) {
            return false;
        }
        CLICK_SELECTION_MODE.add(id);
        return true;
    }

    public static boolean consumeSelectionClick(ServerPlayer player, NpcEntity npc) {
        if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                || !CLICK_SELECTION_MODE.remove(player.getUUID())) {
            return false;
        }
        select(player, npc);
        player.sendSystemMessage(Component.literal("NPC sélectionné : " + npc.getUUID()));
        return true;
    }
}
