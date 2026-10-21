package fr.nexnpc.fabric.network;

import fr.nexnpc.fabric.NexNpcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ProxyPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<ProxyPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NexNpcMod.MOD_ID, "proxy")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ProxyPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeByteArray(payload.data()),
            buffer -> new ProxyPayload(buffer.readByteArray(8192))
    );

    public ProxyPayload {
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
