package fr.nexnpc.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.nexnpc.fabric.entity.NpcEntity;
import fr.nexnpc.fabric.entity.NpcEntityTypes;
import fr.nexnpc.fabric.network.NpcNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.UUID;
import java.util.regex.Pattern;

public final class NpcCommands {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private NpcCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("npc")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("create")
                        .then(Commands.argument("nom", StringArgumentType.word())
                                .executes(context -> create(context.getSource(), StringArgumentType.getString(context, "nom")))))
                .then(Commands.literal("select")
                        .executes(context -> toggleClickSelection(context.getSource()))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> selectById(context.getSource(), StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("remove")
                        .executes(context -> removeSelected(context.getSource())))
                .then(Commands.literal("list")
                        .executes(context -> listNpcs(context.getSource())))
                .then(Commands.literal("skin")
                        .then(Commands.argument("pseudo", StringArgumentType.word())
                                .executes(context -> setSkin(context.getSource(), StringArgumentType.getString(context, "pseudo")))))
                .then(Commands.literal("action")
                        .then(Commands.literal("command")
                                .then(Commands.argument("commande", StringArgumentType.greedyString())
                                        .executes(context -> setCommandAction(context.getSource(), StringArgumentType.getString(context, "commande")))))
                        .then(Commands.literal("server")
                                .then(Commands.argument("serveur", StringArgumentType.word())
                                        .suggests((context, builder) -> NpcNetworking.suggestServers(context.getSource(), builder))
                                        .executes(context -> setServerAction(context.getSource(), StringArgumentType.getString(context, "serveur")))))
                        .then(Commands.literal("none")
                                .executes(context -> setNoAction(context.getSource()))))
                .then(Commands.literal("look")
                        .then(Commands.literal("true")
                                .executes(context -> setLook(context.getSource(), true)))
                        .then(Commands.literal("false")
                                .executes(context -> setLook(context.getSource(), false)))));
    }

    private static int create(CommandSourceStack source, String name) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String normalizedName = name.strip();
        if (normalizedName.isEmpty() || normalizedName.length() > 32) {
            source.sendFailure(Component.literal("Le nom du NPC doit contenir entre 1 et 32 caractères."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        NpcEntity npc = NpcEntityTypes.NPC.create(level, EntitySpawnReason.COMMAND);
        if (npc == null) {
            source.sendFailure(Component.literal("Impossible de créer le NPC."));
            return 0;
        }
        npc.setPos(player.getX(), player.getY(), player.getZ());
        npc.setYRot(player.getYRot());
        npc.setXRot(0.0F);
        npc.setCustomName(Component.literal(normalizedName));
        npc.setCustomNameVisible(true);
        level.addFreshEntity(npc);
        NpcAdminState.select(player, npc);
        source.sendSuccess(() -> Component.literal("NPC « " + normalizedName + " » créé et sélectionné (ID " + npc.getUUID() + ")."), false);
        return 1;
    }

    private static int toggleClickSelection(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean enabled = NpcAdminState.toggleClickSelection(player);
        source.sendSuccess(() -> Component.literal(enabled
                ? "Mode sélection activé : faites un clic droit sur un NPC."
                : "Mode sélection désactivé."), false);
        return 1;
    }

    private static int selectById(CommandSourceStack source, String textId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID id;
        try {
            id = UUID.fromString(textId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("ID invalide. Utilisez l'UUID affiché par /npc list."));
            return 0;
        }
        NpcEntity npc = findNpc(((ServerLevel) player.level()).getServer(), id);
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé ne correspond à cet ID."));
            return 0;
        }
        NpcAdminState.select(player, npc);
        source.sendSuccess(() -> Component.literal("NPC sélectionné : " + npc.getUUID()), false);
        return 1;
    }

    private static int removeSelected(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NpcEntity npc = getSelected(player);
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC sélectionné."));
            return 0;
        }
        UUID id = npc.getUUID();
        npc.discard();
        NpcAdminState.clear(player);
        source.sendSuccess(() -> Component.literal("NPC supprimé : " + id), false);
        return 1;
    }

    private static int listNpcs(CommandSourceStack source) {
        int count = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof NpcEntity npc) {
                    String name = npc.getCustomName() == null ? "Sans nom" : npc.getCustomName().getString();
                    String entry = "NPC " + npc.getUUID() + " — « " + name + " » — "
                            + level.dimension() + " — "
                            + (int) npc.getX() + ", " + (int) npc.getY() + ", " + (int) npc.getZ();
                    source.sendSuccess(() -> Component.literal(entry), false);
                    count++;
                }
            }
        }
        if (count == 0) {
            source.sendSuccess(() -> Component.literal("Aucun NPC chargé."), false);
        }
        return count;
    }

    private static int setSkin(CommandSourceStack source, String username) throws CommandSyntaxException {
        if (!USERNAME.matcher(username).matches()) {
            source.sendFailure(Component.literal("Pseudo Minecraft invalide."));
            return 0;
        }
        NpcEntity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC sélectionné."));
            return 0;
        }
        npc.setSkinName(username);
        source.sendSuccess(() -> Component.literal("Skin du NPC défini sur « " + username + " »."), false);
        return 1;
    }

    private static int setCommandAction(CommandSourceStack source, String command) throws CommandSyntaxException {
        String value = command.strip();
        if (value.isEmpty() || value.length() > 256) {
            source.sendFailure(Component.literal("La commande doit contenir entre 1 et 256 caractères."));
            return 0;
        }
        NpcEntity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC sélectionné."));
            return 0;
        }
        npc.setAction(NpcEntity.ActionType.COMMAND, value);
        source.sendSuccess(() -> Component.literal("Action de commande enregistrée. Elle sera exécutée avec les permissions du joueur."), false);
        return 1;
    }

    private static int setServerAction(CommandSourceStack source, String serverName) throws CommandSyntaxException {
        if (!SERVER_NAME.matcher(serverName).matches()) {
            source.sendFailure(Component.literal("Nom de serveur invalide."));
            return 0;
        }
        NpcEntity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC sélectionné."));
            return 0;
        }
        npc.setAction(NpcEntity.ActionType.SERVER, serverName);
        source.sendSuccess(() -> Component.literal("Action de transfert définie vers « " + serverName + " »."), false);
        return 1;
    }

    private static int setNoAction(CommandSourceStack source) throws CommandSyntaxException {
        NpcEntity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC sélectionné."));
            return 0;
        }
        npc.setAction(NpcEntity.ActionType.NONE, "");
        source.sendSuccess(() -> Component.literal("Action du NPC effacée."), false);
        return 1;
    }

    private static int setLook(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        NpcEntity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC sélectionné."));
            return 0;
        }
        npc.setLookAtNearestPlayer(enabled);
        source.sendSuccess(() -> Component.literal(enabled
                ? "Le NPC regarde le joueur le plus proche."
                : "Le NPC conserve son orientation."), false);
        return 1;
    }

    private static NpcEntity getSelected(ServerPlayer player) {
        UUID id = NpcAdminState.getSelectedId(player);
        return id == null ? null : findNpc(((ServerLevel) player.level()).getServer(), id);
    }

    private static NpcEntity findNpc(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof NpcEntity npc) {
                return npc;
            }
        }
        return null;
    }
}
