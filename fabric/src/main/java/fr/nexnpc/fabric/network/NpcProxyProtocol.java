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
    public static final int VERSION = 2;
    public static final int LIST_REQUEST = 1;
    public static final int TRANSFER_REQUEST = 2;
    public static final int LIST_RESPONSE = 3;
    public static final int TRANSFER_RESPONSE = 4;
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILURE = 2;
    public static final int MAX_SERVERS = 120;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private NpcProxyProtocol() {
    }

    public static byte[] listRequest(byte[] nonce) {
        if (nonce.length != 16) {
            throw new IllegalArgumentException("Le nonce de liste doit contenir 16 octets");
        }
        return encode(out -> {
            out.writeByte(VERSION);
            out.writeByte(LIST_REQUEST);
            out.write(nonce);
        });
    }

    public static byte[] transferRequest(long requestId, String serverName) {
        return encode(out -> {
            out.writeByte(VERSION);
            out.writeByte(TRANSFER_REQUEST);
            out.writeLong(requestId);
            out.writeUTF(serverName);
        });
    }

    public static byte[] transferResponse(long requestId, int status, String message) {
        return encode(out -> {
            out.writeByte(VERSION);
            out.writeByte(TRANSFER_RESPONSE);
            out.writeLong(requestId);
            out.writeByte(status);
            out.writeUTF(message.length() > 256 ? message.substring(0, 256) : message);
        });
    }

    public static ListResponse readListResponse(byte[] bytes) throws IOException {
        if (bytes.length > 8192) {
            throw new IOException("Réponse de liste trop longue");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            requireHeader(in, LIST_RESPONSE);
            byte[] nonce = in.readNBytes(16);
            if (nonce.length != 16) {
                throw new IOException("Nonce incomplet");
            }
            int count = in.readUnsignedShort();
            if (count > MAX_SERVERS) {
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
            requireEnd(in);
            return new ListResponse(nonce, List.copyOf(names));
        }
    }

    public static TransferResponse readTransferResponse(byte[] bytes) throws IOException {
        if (bytes.length > 8192) {
            throw new IOException("Réponse de transfert trop longue");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            requireHeader(in, TRANSFER_RESPONSE);
            long requestId = in.readLong();
            int status = in.readUnsignedByte();
            if (status < STATUS_PENDING || status > STATUS_FAILURE) {
                throw new IOException("État de transfert inconnu");
            }
            String message = in.readUTF();
            if (message.length() > 256) {
                throw new IOException("Message de transfert trop long");
            }
            requireEnd(in);
            return new TransferResponse(requestId, status, message);
        }
    }

    public static byte[] listResponse(byte[] nonce, List<String> serverNames) {
        if (nonce.length != 16) {
            throw new IllegalArgumentException("Le nonce de liste doit contenir 16 octets");
        }
        List<String> safeNames = new ArrayList<>();
        for (String name : serverNames) {
            if (SERVER_NAME.matcher(name).matches() && !safeNames.contains(name)) {
                safeNames.add(name);
            }
            if (safeNames.size() == MAX_SERVERS) {
                break;
            }
        }
        return encode(out -> {
            out.writeByte(VERSION);
            out.writeByte(LIST_RESPONSE);
            out.write(nonce);
            out.writeShort(safeNames.size());
            for (String serverName : safeNames) {
                out.writeUTF(serverName);
            }
        });
    }

    private static void requireHeader(DataInputStream in, int expectedOpcode) throws IOException {
        if (in.readUnsignedByte() != VERSION || in.readUnsignedByte() != expectedOpcode) {
            throw new IOException("Version ou type de message NexNPC inconnu");
        }
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.available() != 0) {
            throw new IOException("Données superflues dans le message NexNPC");
        }
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

    public record TransferResponse(long requestId, int status, String message) {
    }
}
