package fr.nexnpc.fabric.network;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.nexnpc.fabric.NexNpcMod;
import fr.nexnpc.fabric.command.NpcAdminState;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class NpcNetworking {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long LIST_TIMEOUT_MILLIS = 2500;
    private static final long TRANSFER_TIMEOUT_MILLIS = 6000;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, PendingList> PENDING_LISTS = new ConcurrentHashMap<>();
    private static final Map<UUID, CachedList> CACHE = new ConcurrentHashMap<>();
    private static final Map<TransferKey, PendingTransfer> PENDING_TRANSFERS = new ConcurrentHashMap<>();

    private NpcNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ProxyPayload.TYPE, ProxyPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ProxyPayload.TYPE, ProxyPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ProxyPayload.TYPE, (payload, context) ->
                handleProxyPayload(context.player(), payload.data()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.player.getUUID();
            CACHE.remove(playerId);
            NpcAdminState.clear(handler.player);
            PendingList list = PENDING_LISTS.remove(playerId);
            if (list != null) {
                list.result.complete(List.of());
            }
            PENDING_TRANSFERS.keySet().removeIf(key -> key.playerId.equals(playerId));
        });
    }

    private static void handleProxyPayload(ServerPlayer player, byte[] bytes) {
        if (bytes.length < 2) {
            return;
        }
        try {
            int opcode = bytes[1] & 0xFF;
            if (opcode == NpcProxyProtocol.LIST_RESPONSE) {
                acceptListResponse(player, NpcProxyProtocol.readListResponse(bytes));
            } else if (opcode == NpcProxyProtocol.TRANSFER_RESPONSE) {
                acceptTransferResponse(player, NpcProxyProtocol.readTransferResponse(bytes));
            }
            // Requests received from a player are deliberately ignored. Only Velocity can
            // answer these correlations, and the proxy independently rejects player sources.
        } catch (Exception exception) {
            NexNpcMod.LOGGER.debug("Réponse du proxy NexNPC ignorée pour {} : {}", player.getPlainTextName(), exception.getMessage());
        }
    }

    private static void acceptListResponse(ServerPlayer player, NpcProxyProtocol.ListResponse response) {
        UUID playerId = player.getUUID();
        PendingList pending = PENDING_LISTS.get(playerId);
        if (pending == null || !MessageDigest.isEqual(pending.nonce, response.nonce())) {
            return;
        }
        if (PENDING_LISTS.remove(playerId, pending)) {
            CACHE.put(playerId, new CachedList(response.serverNames(), System.nanoTime() + CACHE_NANOS));
            pending.result.complete(response.serverNames());
        }
    }

    private static void acceptTransferResponse(ServerPlayer player, NpcProxyProtocol.TransferResponse response) {
        TransferKey key = new TransferKey(player.getUUID(), response.requestId());
        PendingTransfer pending = PENDING_TRANSFERS.get(key);
        if (pending == null) {
            return;
        }
        if (response.status() == NpcProxyProtocol.STATUS_PENDING) {
            if (pending.pendingNoticeShown.compareAndSet(false, true)) {
                player.sendSystemMessage(Component.literal(response.message()));
            }
            return;
        }
        if (!PENDING_TRANSFERS.remove(key, pending)) {
            return;
        }
        player.sendSystemMessage(Component.literal(response.message()));
        if (response.status() == NpcProxyProtocol.STATUS_FAILURE) {
            NexNpcMod.LOGGER.warn("Échec de transfert NPC pour {} vers {} : {}", player.getPlainTextName(), pending.serverName, response.message());
        } else {
            NexNpcMod.LOGGER.info("Transfert NPC confirmé pour {} vers {}", player.getPlainTextName(), pending.serverName);
        }
    }

    public static CompletableFuture<Suggestions> suggestServers(CommandSourceStack source, SuggestionsBuilder builder) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ignored) {
            return CompletableFuture.completedFuture(builder.build());
        }
        if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return CompletableFuture.completedFuture(builder.build());
        }

        long now = System.nanoTime();
        CachedList cached = CACHE.get(player.getUUID());
        if (cached != null && cached.expiresAtNanos > now) {
            return CompletableFuture.completedFuture(buildSuggestions(builder, cached.serverNames));
        }
        if (cached != null) {
            CACHE.remove(player.getUUID(), cached);
        }

        PendingList current = PENDING_LISTS.get(player.getUUID());
        if (current != null) {
            return current.result.thenApply(names -> buildSuggestions(builder, names));
        }

        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        PendingList requested = new PendingList(nonce, new CompletableFuture<>());
        PendingList existing = PENDING_LISTS.putIfAbsent(player.getUUID(), requested);
        if (existing != null) {
            return existing.result.thenApply(names -> buildSuggestions(builder, names));
        }

        server(player).execute(() -> {
            if (player.isRemoved() || !PENDING_LISTS.containsKey(player.getUUID())) {
                return;
            }
            try {
                ServerPlayNetworking.send(player, new ProxyPayload(NpcProxyProtocol.listRequest(nonce)));
            } catch (RuntimeException exception) {
                NexNpcMod.LOGGER.warn("Impossible d'interroger la liste Velocity pour {}", player.getPlainTextName(), exception);
                if (PENDING_LISTS.remove(player.getUUID(), requested)) {
                    requested.result.complete(List.of());
                }
            }
        });
        CompletableFuture.delayedExecutor(LIST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).execute(() -> {
            if (PENDING_LISTS.remove(player.getUUID(), requested)) {
                requested.result.complete(List.of());
            }
        });
        return requested.result.thenApply(names -> buildSuggestions(builder, names));
    }

    public static void requestTransfer(ServerPlayer player, String serverName) {
        if (!SERVER_NAME.matcher(serverName).matches()) {
            player.sendSystemMessage(Component.literal("Le NPC contient un nom de serveur invalide."));
            return;
        }
        long requestId = RANDOM.nextLong();
        TransferKey key = new TransferKey(player.getUUID(), requestId);
        PendingTransfer pending = new PendingTransfer(serverName);
        PENDING_TRANSFERS.put(key, pending);

        server(player).execute(() -> {
            if (player.isRemoved()) {
                PENDING_TRANSFERS.remove(key, pending);
                return;
            }
            try {
                ServerPlayNetworking.send(player, new ProxyPayload(NpcProxyProtocol.transferRequest(requestId, serverName)));
                NexNpcMod.LOGGER.info("Demande de transfert NPC : {} vers {}", player.getPlainTextName(), serverName);
            } catch (RuntimeException exception) {
                PENDING_TRANSFERS.remove(key, pending);
                player.sendSystemMessage(Component.literal("Impossible de contacter le proxy Velocity."));
                NexNpcMod.LOGGER.warn("Envoi de transfert NPC impossible pour {}", player.getPlainTextName(), exception);
            }
        });
        CompletableFuture.delayedExecutor(TRANSFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).execute(() -> {
            if (PENDING_TRANSFERS.remove(key, pending) && !player.isRemoved()) {
                server(player).execute(() -> player.sendSystemMessage(Component.literal(
                        "Le proxy Velocity n'a pas répondu. Vérifiez que NexNPC Velocity Integration est installé.")));
            }
        });
    }

    private static Suggestions buildSuggestions(SuggestionsBuilder builder, List<String> names) {
        for (String name : names) {
            if (SERVER_NAME.matcher(name).matches()) {
                builder.suggest(name);
            }
        }
        return builder.build();
    }

    private static net.minecraft.server.MinecraftServer server(ServerPlayer player) {
        return ((ServerLevel) player.level()).getServer();
    }

    private record CachedList(List<String> serverNames, long expiresAtNanos) {
    }

    private record PendingList(byte[] nonce, CompletableFuture<List<String>> result) {
        private PendingList {
            nonce = Arrays.copyOf(nonce, nonce.length);
        }
    }

    private record TransferKey(UUID playerId, long requestId) {
    }

    private static final class PendingTransfer {
        private final String serverName;
        private final java.util.concurrent.atomic.AtomicBoolean pendingNoticeShown = new java.util.concurrent.atomic.AtomicBoolean();

        private PendingTransfer(String serverName) {
            this.serverName = serverName;
        }
    }
}
