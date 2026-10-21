package fr.nexnpc.fabric.network;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.nexnpc.fabric.command.NpcAdminState;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

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
    private static final long RESPONSE_TIMEOUT_MILLIS = 1200;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, PendingList> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, CachedList> CACHE = new ConcurrentHashMap<>();

    private NpcNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ProxyPayload.TYPE, ProxyPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ProxyPayload.TYPE, ProxyPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ProxyPayload.TYPE, (payload, context) -> {
            byte[] bytes = payload.data();
            try {
                NpcProxyProtocol.ListResponse response = NpcProxyProtocol.readListResponse(bytes);
                UUID playerId = context.player().getUUID();
                PendingList pending = PENDING.get(playerId);
                if (pending == null || !MessageDigest.isEqual(pending.nonce, response.nonce())) {
                    return;
                }
                if (PENDING.remove(playerId, pending)) {
                    CACHE.put(playerId, new CachedList(response.serverNames(), System.nanoTime() + CACHE_NANOS));
                    pending.result.complete(response.serverNames());
                }
            } catch (Exception ignored) {
                // Les payloads clients et les messages mal formés ne participent jamais à une action NPC.
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.player.getUUID();
            CACHE.remove(playerId);
            NpcAdminState.clear(handler.player);
            PendingList pending = PENDING.remove(playerId);
            if (pending != null) {
                pending.result.complete(List.of());
            }
        });
    }

    public static CompletableFuture<Suggestions> suggestServers(CommandSourceStack source, SuggestionsBuilder builder) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ignored) {
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

        PendingList current = PENDING.get(player.getUUID());
        if (current != null) {
            return current.result.thenApply(names -> buildSuggestions(builder, names));
        }

        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        PendingList requested = new PendingList(nonce, new CompletableFuture<>());
        PendingList existing = PENDING.putIfAbsent(player.getUUID(), requested);
        if (existing != null) {
            return existing.result.thenApply(names -> buildSuggestions(builder, names));
        }

        ((ServerLevel) player.level()).getServer().execute(() -> {
            if (player.isRemoved() || !PENDING.containsKey(player.getUUID())) {
                return;
            }
            ServerPlayNetworking.send(player, new ProxyPayload(NpcProxyProtocol.listRequest(nonce)));
        });
        CompletableFuture.delayedExecutor(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).execute(() -> {
            if (PENDING.remove(player.getUUID(), requested)) {
                requested.result.complete(List.of());
            }
        });
        return requested.result.thenApply(names -> buildSuggestions(builder, names));
    }

    public static void requestTransfer(ServerPlayer player, String serverName) {
        if (!SERVER_NAME.matcher(serverName).matches()) {
            return;
        }
        ((ServerLevel) player.level()).getServer().execute(() -> ServerPlayNetworking.send(
                player,
                new ProxyPayload(NpcProxyProtocol.transferRequest(serverName))
        ));
    }

    private static Suggestions buildSuggestions(SuggestionsBuilder builder, List<String> names) {
        for (String name : names) {
            if (SERVER_NAME.matcher(name).matches()) {
                builder.suggest(name);
            }
        }
        return builder.build();
    }

    private record CachedList(List<String> serverNames, long expiresAtNanos) {
    }

    private record PendingList(byte[] nonce, CompletableFuture<List<String>> result) {
        private PendingList {
            nonce = Arrays.copyOf(nonce, nonce.length);
        }
    }
}
