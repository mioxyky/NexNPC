package fr.nexnpc.fabric.entity;

import fr.nexnpc.fabric.NexNpcMod;
import fr.nexnpc.fabric.command.NpcAdminState;
import fr.nexnpc.fabric.network.NpcNetworking;
import net.minecraft.commands.CommandSource;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class NpcInteractionHandler {
    private static final long CLICK_COOLDOWN_TICKS = 8L;
    private static final Pattern SAFE_PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SAFE_COMMAND = Pattern.compile("[^\\r\\n\\u0000]{1,256}");
    private static final Map<InteractionKey, Long> LAST_INTERACTION = new HashMap<>();
    private static final CommandSource COMMAND_LOG = new CommandSource() {
        @Override
        public void sendSystemMessage(Component message) {
            NexNpcMod.LOGGER.info("[Commande NPC] {}", message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    };

    private NpcInteractionHandler() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register(NpcInteractionHandler::interact);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!level.isClientSide() && NpcEntityData.isNpc(entity)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    private static InteractionResult interact(Player player, net.minecraft.world.level.Level level,
                                              InteractionHand hand, Entity entity,
                                              net.minecraft.world.phys.EntityHitResult hit) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !NpcEntityData.isNpc(entity)) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(entity instanceof Mannequin mannequin)) {
            return InteractionResult.SUCCESS;
        }

        if (NpcAdminState.consumeSelectionClick(serverPlayer, mannequin)) {
            return InteractionResult.SUCCESS;
        }
        if (!allowInteraction(serverPlayer, mannequin)) {
            return InteractionResult.SUCCESS;
        }

        String npcId = NpcEntityData.getId(mannequin);
        switch (NpcEntityData.getActionType(mannequin)) {
            case COMMAND -> executeCommand(serverPlayer, mannequin, npcId);
            case SERVER -> {
                NexNpcMod.LOGGER.info("Le joueur {} utilise le NPC {} pour demander un transfert vers {}",
                        serverPlayer.getPlainTextName(), npcId, NpcEntityData.getActionValue(mannequin));
                NpcNetworking.requestTransfer(serverPlayer, NpcEntityData.getActionValue(mannequin));
            }
            case NONE -> {
                // Le clic est consommé pour laisser les mannequins NexNPC inertes.
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean allowInteraction(ServerPlayer player, Mannequin mannequin) {
        long now = player.level().getGameTime();
        InteractionKey key = new InteractionKey(mannequin.getUUID(), player.getUUID());
        Long previous = LAST_INTERACTION.put(key, now);
        if (LAST_INTERACTION.size() > 4096) {
            LAST_INTERACTION.entrySet().removeIf(entry -> now - entry.getValue() > 200L);
        }
        return previous == null || now - previous >= CLICK_COOLDOWN_TICKS;
    }

    private static void executeCommand(ServerPlayer player, Mannequin mannequin, String npcId) {
        String configured = NpcEntityData.getActionValue(mannequin).strip();
        while (configured.startsWith("/")) {
            configured = configured.substring(1);
        }
        String playerName = player.getPlainTextName();
        if (!SAFE_COMMAND.matcher(configured).matches() || !SAFE_PLAYER_NAME.matcher(playerName).matches()) {
            NexNpcMod.LOGGER.error("Commande NPC {} rejetée : contenu invalide (joueur {})", npcId, playerName);
            player.sendSystemMessage(Component.literal("La commande de ce NPC est invalide. Consultez les logs du serveur."));
            return;
        }

        String command = configured.replace("%player%", playerName);
        NexNpcMod.LOGGER.info("Commande du NPC {} déclenchée par {} en console : {}", npcId, playerName, command);
        try {
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().getCommands().performPrefixedCommand(
                    level.getServer().createCommandSourceStack()
                            .withSource(COMMAND_LOG)
                            .withLevel(level)
                            .withPosition(mannequin.position()),
                    command);
        } catch (RuntimeException exception) {
            NexNpcMod.LOGGER.error("Erreur pendant l'exécution de la commande du NPC {}", npcId, exception);
            player.sendSystemMessage(Component.literal("La commande du NPC a échoué. Consultez les logs du serveur."));
        }
    }

    private record InteractionKey(UUID npcId, UUID playerId) {
    }
}
