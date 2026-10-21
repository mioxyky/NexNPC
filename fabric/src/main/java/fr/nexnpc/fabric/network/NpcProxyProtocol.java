package fr.nexnpc.fabric.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class NpcProxyProtocol {
    public static final int VERSION = 1;
    public static final int LIST_REQUEST = 1;
    public static final int TRANSFER_REQUEST = 2;
    public static final int LIST_RESPONSE = 3;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private NpcProxyProtocol() {
    }

    public static byte[] listRequest(byte[] nonce) {
        return encode(out -> {
            out.writeByte(VERSION);
            out.writeByte(LIST_REQUEST);
            out.write(nonce);
        });
    }

    public static byte[] transferRequest(String serverName) {
        return encode(out -> {
            out.writeByte(VERSION);
            out.writeByte(TRANSFER_REQUEST);
            out.writeUTF(serverName);
        });
    }

    public static ListResponse readListResponse(byte[] bytes) throws IOException {
        if (bytes.length > 8192) {
            throw new IOException("Réponse de liste trop longue");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != VERSION || in.readUnsignedByte() != LIST_RESPONSE) {
                throw new IOException("Réponse NexNPC inconnue");
            }
            byte[] nonce = in.readNBytes(16);
            if (nonce.length != 16) {
                throw new IOException("Nonce incomplet");
            }
            int count = in.readUnsignedShort();
            if (count > 128) {
                throw new IOException("Trop de serveurs");
            }
            Set<String> names = new LinkedHashSet<>();
            for (int index = 0; index < count; index++) {
                String name = in.readUTF();
                if (!SERVER_NAME.matcher(name).matches()) {
                    throw new IOException("Nom de serveur invalide");
                }
                names.add(name);
            }
            if (in.available() != 0) {
                throw new IOException("Données superflues dans la réponse");
            }
            return new ListResponse(nonce, List.copyOf(names));
        }
    }

    public static byte[] listResponse(byte[] nonce, List<String> serverNames) {
        List<String> safeNames = new ArrayList<>();
        for (String name : serverNames) {
            if (SERVER_NAME.matcher(name).matches() && !safeNames.contains(name)) {
                safeNames.add(name);
            }
            if (safeNames.size() == 128) {
                break;
            }
        }
        return encode(out -> {
            out.writeByte(VERSION);
            out.writeByte(LIST_RESPONSE);
            out.write(nonce);
            out.writeShort(safeNames.size());
            for (String name : safeNames) {
                out.writeUTF(name);
            }
        });
    }

    private static byte[] encode(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writer.write(out);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible d'encoder le message NexNPC", exception);
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    public record ListResponse(byte[] nonce, List<String> serverNames) {
        public ListResponse {
            nonce = nonce.clone();
            serverNames = List.copyOf(serverNames);
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
