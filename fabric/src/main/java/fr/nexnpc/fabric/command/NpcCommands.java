package fr.nexnpc.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.nexnpc.fabric.NexNpcMod;
import fr.nexnpc.fabric.entity.NpcActionType;
import fr.nexnpc.fabric.entity.NpcEntityData;
import fr.nexnpc.fabric.entity.NpcIdIndex;
import fr.nexnpc.fabric.network.NpcNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.Mannequin;

import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class NpcCommands {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern COMMAND = Pattern.compile("[^\\r\\n\\u0000]{1,256}");

    private NpcCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> action = Commands.literal("action")
                .then(Commands.literal("add")
                        .then(commandAction("command"))
                        .then(serverAction("server")))
                // Keep the 1.0 syntax as aliases for existing server scripts.
                .then(commandAction("command"))
                .then(serverAction("server"))
                .then(Commands.literal("none").executes(context -> setNoAction(context.getSource())));

        dispatcher.register(Commands.literal("npc")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> create(context.getSource(), StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("select")
                        .executes(context -> toggleClickSelection(context.getSource()))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> selectById(context.getSource(), StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("remove").executes(context -> removeSelected(context.getSource())))
                .then(Commands.literal("list").executes(context -> listNpcs(context.getSource())))
                .then(Commands.literal("rename")
                        .then(Commands.argument("nom", StringArgumentType.greedyString())
                                .executes(context -> rename(context.getSource(), StringArgumentType.getString(context, "nom")))))
                .then(Commands.literal("change")
                        .then(Commands.literal("id")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(context -> changeId(context.getSource(), StringArgumentType.getString(context, "id"))))))
                .then(Commands.literal("skin")
                        .then(Commands.argument("pseudo", StringArgumentType.word())
                                .executes(context -> setSkin(context.getSource(), StringArgumentType.getString(context, "pseudo")))))
                .then(action)
                .then(Commands.literal("look")
                        .then(Commands.literal("true").executes(context -> setLook(context.getSource(), true)))
                        .then(Commands.literal("false").executes(context -> setLook(context.getSource(), false)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> commandAction(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("commande", StringArgumentType.greedyString())
                        .executes(context -> setCommandAction(context.getSource(), StringArgumentType.getString(context, "commande"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> serverAction(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("serveur", StringArgumentType.word())
                        .suggests((context, builder) -> NpcNetworking.suggestServers(context.getSource(), builder))
                        .executes(context -> setServerAction(context.getSource(), StringArgumentType.getString(context, "serveur"))));
    }

    private static int create(CommandSourceStack source, String id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!NpcEntityData.isValidId(id)) {
            source.sendFailure(Component.literal("ID invalide : utilisez 1 à 32 lettres, chiffres, tirets ou underscores."));
            return 0;
        }

        NpcIdIndex index = NpcIdIndex.get(source.getServer());
        if (index.owner(id) != null) {
            source.sendFailure(Component.literal("L'ID « " + id + " » est déjà utilisé."));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        Mannequin npc = net.minecraft.world.entity.EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
        if (npc == null) {
            source.sendFailure(Component.literal("Impossible de créer le mannequin natif."));
            return 0;
        }

        String normalizedId = id.toLowerCase(Locale.ROOT);
        npc.setPos(player.getX(), player.getY(), player.getZ());
        npc.setYRot(player.getYRot());
        npc.setYHeadRot(player.getYRot());
        npc.setYBodyRot(player.getYRot());
        npc.setXRot(0.0F);
        NpcEntityData.initialize(npc, normalizedId, id, "Steve");

        if (!index.claim(normalizedId, npc.getUUID())) {
            source.sendFailure(Component.literal("L'ID « " + id + " » est déjà réservé par un NPC."));
            return 0;
        }
        if (!level.addFreshEntity(npc)) {
            index.release(normalizedId, npc.getUUID());
            source.sendFailure(Component.literal("Le mannequin n'a pas pu être ajouté au monde."));
            return 0;
        }

        NpcAdminState.select(player, npc);
        NexNpcMod.LOGGER.info("NPC {} créé par {} dans {} à {}, {}, {}", normalizedId, player.getPlainTextName(),
                level.dimension().identifier(), npc.getX(), npc.getY(), npc.getZ());
        source.sendSuccess(() -> Component.literal("NPC « " + normalizedId + " » créé et sélectionné."), false);
        return 1;
    }

    private static int toggleClickSelection(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean enabled = NpcAdminState.toggleClickSelection(player);
        source.sendSuccess(() -> Component.literal(enabled
                ? "Mode sélection activé : faites un clic droit sur un NPC NexNPC."
                : "Mode sélection désactivé."), false);
        return 1;
    }

    private static int selectById(CommandSourceStack source, String textId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity npc = findById(source.getServer(), textId);
        if (npc == null) {
            try {
                npc = findByUuid(source.getServer(), UUID.fromString(textId));
            } catch (IllegalArgumentException ignored) {
                // The public identifier is normally the readable NPC ID.
            }
        }
        if (npc == null) {
            source.sendFailure(Component.literal("NPC introuvable ou dans un chunk déchargé : " + textId));
            return 0;
        }
        NpcAdminState.select(player, npc);
        Entity selectedNpc = npc;
        source.sendSuccess(() -> Component.literal("NPC sélectionné : " + NpcEntityData.getId(selectedNpc)), false);
        return 1;
    }

    private static int removeSelected(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity npc = getSelected(player);
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        String id = NpcEntityData.getId(npc);
        NpcIdIndex.get(source.getServer()).release(id, npc.getUUID());
        npc.discard();
        NpcAdminState.clear(player);
        NexNpcMod.LOGGER.info("NPC {} supprimé par {}", id, player.getPlainTextName());
        source.sendSuccess(() -> Component.literal("NPC supprimé : " + id), false);
        return 1;
    }

    private static int listNpcs(CommandSourceStack source) {
        var entries = NpcIdIndex.get(source.getServer()).entries().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .toList();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Aucun NPC enregistré."), false);
            return 0;
        }
        for (var entry : entries) {
            UUID uuid;
            try {
                uuid = UUID.fromString(entry.getValue());
            } catch (IllegalArgumentException ignored) {
                source.sendSuccess(() -> Component.literal("NPC " + entry.getKey() + " — index à réparer"), false);
                continue;
            }
            Entity npc = findByUuid(source.getServer(), uuid);
            String status = npc == null ? "chunk déchargé" : npc.level().dimension().identifier() + " — "
                    + (int) npc.getX() + ", " + (int) npc.getY() + ", " + (int) npc.getZ();
            String display = npc == null ? entry.getKey() : NpcEntityData.getDisplayName(npc);
            source.sendSuccess(() -> Component.literal("NPC " + entry.getKey() + " — « " + display + " » — " + status), false);
        }
        return entries.size();
    }

    private static int rename(CommandSourceStack source, String rawName) throws CommandSyntaxException {
        String name = stripOuterQuotes(rawName.strip());
        if (name.isEmpty() || name.length() > 128) {
            source.sendFailure(Component.literal("Le nom affiché doit contenir entre 1 et 128 caractères."));
            return 0;
        }
        Entity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        NpcEntityData.setDisplayName(npc, name);
        npc.setCustomName(parseFormattedText(name));
        npc.setCustomNameVisible(true);
        source.sendSuccess(() -> Component.literal("Nom affiché du NPC « " + NpcEntityData.getId(npc) + " » modifié."), false);
        return 1;
    }

    private static int changeId(CommandSourceStack source, String newId) throws CommandSyntaxException {
        if (!NpcEntityData.isValidId(newId)) {
            source.sendFailure(Component.literal("ID invalide : utilisez 1 à 32 lettres, chiffres, tirets ou underscores."));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        Entity npc = getSelected(player);
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        String oldId = NpcEntityData.getId(npc);
        String normalizedNewId = newId.toLowerCase(Locale.ROOT);
        if (oldId.equalsIgnoreCase(normalizedNewId)) {
            source.sendFailure(Component.literal("Ce NPC possède déjà l'ID « " + normalizedNewId + " »."));
            return 0;
        }
        NpcIdIndex index = NpcIdIndex.get(source.getServer());
        if (!index.rename(oldId, normalizedNewId, npc.getUUID())) {
            source.sendFailure(Component.literal("L'ID « " + normalizedNewId + " » est déjà utilisé ou réservé."));
            return 0;
        }
        NpcEntityData.setId(npc, normalizedNewId);
        NexNpcMod.LOGGER.info("NPC {} renommé en {} par {}", oldId, normalizedNewId, player.getPlainTextName());
        source.sendSuccess(() -> Component.literal("ID du NPC modifié : " + normalizedNewId), false);
        return 1;
    }

    private static int setSkin(CommandSourceStack source, String username) throws CommandSyntaxException {
        if (!USERNAME.matcher(username).matches()) {
            source.sendFailure(Component.literal("Pseudo Minecraft invalide."));
            return 0;
        }
        Entity selected = getSelected(source.getPlayerOrException());
        if (!(selected instanceof Mannequin npc)) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        try {
            NpcEntityData.applySkin(npc, username);
        } catch (RuntimeException exception) {
            NexNpcMod.LOGGER.error("Échec du changement de skin du NPC {} vers {}", NpcEntityData.getId(npc), username, exception);
            source.sendFailure(Component.literal("Impossible de modifier le skin du mannequin. Consultez les logs du serveur."));
            return 0;
        }
        NexNpcMod.LOGGER.info("Skin du NPC {} défini sur {}", NpcEntityData.getId(npc), username);
        source.sendSuccess(() -> Component.literal("Skin du NPC « " + NpcEntityData.getId(npc) + " » défini sur « " + username + " »."), false);
        return 1;
    }

    private static int setCommandAction(CommandSourceStack source, String rawCommand) throws CommandSyntaxException {
        String command = stripOuterQuotes(rawCommand.strip());
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (!COMMAND.matcher(command).matches()) {
            source.sendFailure(Component.literal("La commande doit contenir 1 à 256 caractères sur une seule ligne."));
            return 0;
        }
        String commandToValidate = command.replace("%player%", "NexNpcValidationPlayer");
        var parse = source.getServer().getCommands().getDispatcher()
                .parse(commandToValidate, source.getServer().createCommandSourceStack());
        if (!parse.getExceptions().isEmpty() || parse.getReader().canRead() || parse.getContext().getCommand() == null) {
            source.sendFailure(Component.literal("Commande inconnue ou syntaxe invalide."));
            return 0;
        }
        Entity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        NpcEntityData.setAction(npc, NpcActionType.COMMAND, command);
        source.sendSuccess(() -> Component.literal("Commande enregistrée. Elle sera exécutée par la console; %player% sera remplacé par le joueur."), false);
        return 1;
    }

    private static int setServerAction(CommandSourceStack source, String serverName) throws CommandSyntaxException {
        if (!SERVER_NAME.matcher(serverName).matches()) {
            source.sendFailure(Component.literal("Nom de serveur invalide."));
            return 0;
        }
        Entity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        NpcEntityData.setAction(npc, NpcActionType.SERVER, serverName);
        source.sendSuccess(() -> Component.literal("Action de transfert définie vers « " + serverName + " »."), false);
        return 1;
    }

    private static int setNoAction(CommandSourceStack source) throws CommandSyntaxException {
        Entity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        NpcEntityData.setAction(npc, NpcActionType.NONE, "");
        source.sendSuccess(() -> Component.literal("Action du NPC effacée."), false);
        return 1;
    }

    private static int setLook(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        Entity npc = getSelected(source.getPlayerOrException());
        if (npc == null) {
            source.sendFailure(Component.literal("Aucun NPC chargé n'est sélectionné."));
            return 0;
        }
        NpcEntityData.setLookAtNearestPlayer(npc, enabled);
        source.sendSuccess(() -> Component.literal(enabled
                ? "Le NPC regarde le joueur vivant le plus proche dans un rayon de 16 blocs."
                : "Le regard automatique du NPC est désactivé."), false);
        return 1;
    }

    private static Entity getSelected(ServerPlayer player) {
        UUID id = NpcAdminState.getSelectedId(player);
        Entity entity = id == null ? null : findByUuid(((ServerLevel) player.level()).getServer(), id);
        return NpcEntityData.isNpc(entity) ? entity : null;
    }

    private static Entity findById(MinecraftServer server, String id) {
        UUID uuid = NpcIdIndex.get(server).owner(id);
        return uuid == null ? null : findByUuid(server, uuid);
    }

    private static Entity findByUuid(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (NpcEntityData.isNpc(entity)) {
                return entity;
            }
        }
        return null;
    }

    private static String stripOuterQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Component parseFormattedText(String input) {
        MutableComponent result = Component.empty();
        StringBuilder text = new StringBuilder();
        Style style = Style.EMPTY;
        for (int index = 0; index < input.length(); index++) {
            char marker = input.charAt(index);
            if ((marker == '&' || marker == '\u00a7') && index + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(index + 1));
                if (code == '#' && index + 7 < input.length()) {
                    String hex = input.substring(index + 2, index + 8);
                    if (hex.matches("[0-9a-fA-F]{6}")) {
                        appendSegment(result, text, style);
                        style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
                        index += 7;
                        continue;
                    }
                }
                ChatFormatting formatting = ChatFormatting.getByCode(code);
                if (formatting != null) {
                    appendSegment(result, text, style);
                    if (formatting == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (formatting.isColor()) {
                        style = Style.EMPTY.withColor(formatting);
                    } else {
                        style = switch (formatting) {
                            case OBFUSCATED -> style.withObfuscated(true);
                            case BOLD -> style.withBold(true);
                            case STRIKETHROUGH -> style.withStrikethrough(true);
                            case UNDERLINE -> style.withUnderlined(true);
                            case ITALIC -> style.withItalic(true);
                            default -> style;
                        };
                    }
                    index++;
                    continue;
                }
            }
            text.append(marker);
        }
        appendSegment(result, text, style);
        return result;
    }

    private static void appendSegment(MutableComponent target, StringBuilder text, Style style) {
        if (!text.isEmpty()) {
            target.append(Component.literal(text.toString()).withStyle(style));
            text.setLength(0);
        }
    }
}
