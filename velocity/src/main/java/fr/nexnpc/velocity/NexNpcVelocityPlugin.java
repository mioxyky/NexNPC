package fr.nexnpc.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class NexNpcVelocityPlugin {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("nexnpc:proxy");
    private static final int PROTOCOL_VERSION = 2;
    private static final int LIST_REQUEST = 1;
    private static final int TRANSFER_REQUEST = 2;
    private static final int LIST_RESPONSE = 3;
    private static final int TRANSFER_RESPONSE = 4;
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_FAILURE = 2;
    private static final int MAX_PAYLOAD_BYTES = 8192;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<UUID, Long> lastListRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTransfers = new ConcurrentHashMap<>();

    @Inject
    public NexNpcVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        logger.info("Canal NexNPC nexnpc:proxy enregistré (protocole {})", PROTOCOL_VERSION);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        // Prevent channel payloads from reaching clients. Only messages from trusted backend
        // connections are decoded; a player can never submit a transfer request to this plugin.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection backend)) {
            logger.debug("Message NexNPC rejeté : provenance différente d'un serveur backend");
            return;
        }

        Player player = backend.getPlayer();
        if (!isCurrentConnection(player, backend)) {
            logger.debug("Message NexNPC rejeté : connexion backend périmée pour {}", player.getUsername());
            return;
        }

        try {
            byte[] payload = unwrap(event.getData());
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                int version = input.readUnsignedByte();
                int opcode = input.readUnsignedByte();
                if (version != PROTOCOL_VERSION) {
                    logger.debug("Message NexNPC rejeté : version {} inconnue", version);
                    return;
                }
                if (opcode == LIST_REQUEST) {
                    handleListRequest(player, backend, input);
                } else if (opcode == TRANSFER_REQUEST) {
                    handleTransferRequest(player, backend, input);
                } else {
                    logger.debug("Message NexNPC rejeté : opcode backend {} non autorisé", opcode);
                }
            }
        } catch (IOException | RuntimeException exception) {
            logger.debug("Message NexNPC rejeté : {}", exception.getMessage());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastListRequests.remove(playerId);
        lastTransfers.remove(playerId);
    }

    private void handleListRequest(Player player, ServerConnection backend, DataInputStream input) throws IOException {
        byte[] nonce = input.readNBytes(16);
        if (nonce.length != 16 || input.available() != 0 || !allowRequest(lastListRequests, player.getUniqueId(), 500L)) {
            return;
        }

        Collection<RegisteredServer> servers = proxy.getAllServers();
        List<String> names = servers.stream()
                .map(server -> server.getServerInfo().getName())
                .filter(name -> SERVER_NAME.matcher(name).matches())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(120)
                .toList();
        byte[] response = listResponse(nonce, names);
        if (!backend.sendPluginMessage(CHANNEL, frame(response))) {
            logger.warn("La liste Velocity n'a pas pu être renvoyée au backend pour {}", player.getUsername());
        }
    }

    private void handleTransferRequest(Player player, ServerConnection backend, DataInputStream input) throws IOException {
        long requestId = input.readLong();
        String requestedName = input.readUTF();
        if (input.available() != 0 || !SERVER_NAME.matcher(requestedName).matches()) {
            reply(backend, requestId, STATUS_FAILURE, "Nom de serveur NPC invalide.");
            return;
        }
        if (!allowRequest(lastTransfers, player.getUniqueId(), 1000L)) {
            reply(backend, requestId, STATUS_FAILURE, "Patientez avant de demander un autre transfert.");
            return;
        }

        Optional<RegisteredServer> destination = proxy.getServer(requestedName);
        if (destination.isEmpty()) {
            logger.warn("Destination NPC inexistante : {} (joueur {})", requestedName, player.getUsername());
            reply(backend, requestId, STATUS_FAILURE, "Le serveur « " + requestedName + " » n'existe pas dans Velocity.");
            return;
        }
        if (destination.get().equals(backend.getServer())) {
            reply(backend, requestId, STATUS_SUCCESS, "Vous êtes déjà connecté au serveur « " + requestedName + " ».");
            return;
        }

        logger.info("Transfert NPC validé : {} de {} vers {}", player.getUsername(), backend.getServer().getServerInfo().getName(), requestedName);
        reply(backend, requestId, STATUS_PENDING, "Connexion au serveur « " + requestedName + " » en cours…");

        player.createConnectionRequest(destination.get()).connect()
                .orTimeout(10, TimeUnit.SECONDS)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        String message = "Connexion au serveur « " + requestedName + " » impossible ou expirée.";
                        logger.warn("Échec du transfert de {} vers {} : {}", player.getUsername(), requestedName, failure.toString());
                        replyIfStillConnected(player, backend, requestId, STATUS_FAILURE, message);
                        return;
                    }
                    if (result == null) {
                        logger.warn("Velocity n'a renvoyé aucun résultat pour le transfert de {} vers {}", player.getUsername(), requestedName);
                        replyIfStillConnected(player, backend, requestId, STATUS_FAILURE,
                                "Velocity n'a pas confirmé la connexion à « " + requestedName + " ».");
                        return;
                    }

                    ConnectionRequestBuilder.Status status = result.getStatus();
                    if (result.isSuccessful()) {
                        logger.info("Transfert NPC de {} vers {} : {}", player.getUsername(), requestedName, status);
                        replyIfStillConnected(player, backend, requestId, STATUS_SUCCESS,
                                "Connexion au serveur « " + requestedName + " » effectuée.");
                    } else {
                        String detail = result.getReasonComponent().map(PLAIN_TEXT::serialize).orElse(status.name());
                        logger.warn("Transfert NPC refusé pour {} vers {} : {} ({})", player.getUsername(), requestedName, status, detail);
                        replyIfStillConnected(player, backend, requestId, STATUS_FAILURE,
                                "Connexion au serveur « " + requestedName + " » refusée : " + detail);
                    }
                });
    }

    private void replyIfStillConnected(Player player, ServerConnection backend, long requestId, int status, String message) {
        if (status == STATUS_FAILURE && !isCurrentConnection(player, backend)) {
            logger.debug("Impossible de notifier {} : le joueur a quitté le backend source", player.getUsername());
            return;
        }
        reply(backend, requestId, status, message);
    }

    private void reply(ServerConnection backend, long requestId, int status, String message) {
        try {
            if (!backend.sendPluginMessage(CHANNEL, frame(transferResponse(requestId, status, message)))) {
                logger.debug("Réponse de transfert NexNPC {} non envoyée au backend", requestId);
            }
        } catch (IOException exception) {
            logger.error("Impossible d'encoder la réponse de transfert NexNPC {}", requestId, exception);
        }
    }

    private static boolean isCurrentConnection(Player player, ServerConnection source) {
        return player.getCurrentServer()
                .filter(current -> current.getServer().equals(source.getServer()))
                .isPresent();
    }

    private static boolean allowRequest(Map<UUID, Long> lastRequests, UUID playerId, long cooldownMillis) {
        long now = System.nanoTime();
        long cooldown = TimeUnit.MILLISECONDS.toNanos(cooldownMillis);
        AtomicBoolean allowed = new AtomicBoolean();
        lastRequests.compute(playerId, (id, previous) -> {
            if (previous == null || now - previous >= cooldown) {
                allowed.set(true);
                return now;
            }
            return previous;
        });
        return allowed.get();
    }

    private static byte[] unwrap(byte[] frame) throws IOException {
        if (frame.length < 2 || frame.length > MAX_PAYLOAD_BYTES + 3) {
            throw new IOException("Taille du payload incorrecte");
        }
        int value = 0;
        int shift = 0;
        int index = 0;
        while (true) {
            if (index >= frame.length || index >= 3) {
                throw new IOException("Longueur VarInt invalide");
            }
            int next = frame[index++] & 0xFF;
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (value < 2 || value > MAX_PAYLOAD_BYTES || index + value != frame.length) {
            throw new IOException("Longueur du payload incohérente");
        }
        return java.util.Arrays.copyOfRange(frame, index, frame.length);
    }

    private static byte[] frame(byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 5);
        int length = payload.length;
        while ((length & ~0x7F) != 0) {
            output.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        output.write(length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    private static byte[] listResponse(byte[] nonce, List<String> serverNames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(PROTOCOL_VERSION);
            output.writeByte(LIST_RESPONSE);
            output.write(nonce);
            output.writeShort(serverNames.size());
            for (String serverName : serverNames) {
                output.writeUTF(serverName);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] transferResponse(long requestId, int status, String message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(PROTOCOL_VERSION);
            output.writeByte(TRANSFER_RESPONSE);
            output.writeLong(requestId);
            output.writeByte(status);
            output.writeUTF(message.length() > 256 ? message.substring(0, 256) : message);
        }
        return bytes.toByteArray();
    }
}
