package fr.nexnpc.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
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
    private static final int PROTOCOL_VERSION = 1;
    private static final int LIST_REQUEST = 1;
    private static final int TRANSFER_REQUEST = 2;
    private static final int LIST_RESPONSE = 3;
    private static final int MAX_PAYLOAD_BYTES = 8192;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

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
        logger.info("Canal nexnpc:proxy enregistré");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        // Intercepter les messages de cette voie, même ceux émis par un joueur, avant toute analyse.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }

        Player player = backend.getPlayer();
        if (!isCurrentConnection(player, backend)) {
            return;
        }

        try {
            byte[] payload = unwrap(event.getData());
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                int version = input.readUnsignedByte();
                int opcode = input.readUnsignedByte();
                if (version != PROTOCOL_VERSION) {
                    return;
                }
                if (opcode == LIST_REQUEST) {
                    handleListRequest(player, backend, input);
                } else if (opcode == TRANSFER_REQUEST) {
                    handleTransferRequest(player, backend, input);
                }
            }
        } catch (IOException | RuntimeException exception) {
            logger.debug("Message nexnpc:proxy rejeté : {}", exception.getMessage());
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
                .limit(128)
                .toList();
        byte[] response = listResponse(nonce, names);
        if (!backend.sendPluginMessage(CHANNEL, frame(response))) {
            logger.debug("Liste des serveurs impossible à renvoyer à {}", player.getUsername());
        }
    }

    private void handleTransferRequest(Player player, ServerConnection backend, DataInputStream input) throws IOException {
        String requestedName = input.readUTF();
        if (input.available() != 0 || !SERVER_NAME.matcher(requestedName).matches()) {
            return;
        }
        if (!allowRequest(lastTransfers, player.getUniqueId(), 1000L)) {
            return;
        }

        Optional<RegisteredServer> destination = proxy.getServer(requestedName);
        if (destination.isEmpty()) {
            logger.debug("Destination NPC inexistante : {}", requestedName);
            return;
        }
        if (destination.get().equals(backend.getServer())) {
            return;
        }

        player.createConnectionRequest(destination.get()).connect().whenComplete((result, failure) -> {
            if (failure != null) {
                logger.warn("Échec du transfert de {} vers {}", player.getUsername(), requestedName, failure);
            }
        });
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
}
