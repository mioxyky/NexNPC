package fr.nexnpc.fabric.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import fr.nexnpc.fabric.NexNpcMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class SkinTextureCache {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern TEXTURE_HASH = Pattern.compile("[0-9a-fA-F]{32,64}");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Identifier FALLBACK = Identifier.fromNamespaceAndPath(
            NexNpcMod.MOD_ID, "textures/entity/default.png"
    );
    private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private SkinTextureCache() {
    }

    static Identifier getTexture(String playerName) {
        if (playerName == null || !USERNAME.matcher(playerName).matches()) {
            return FALLBACK;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        Identifier texture = TEXTURES.get(key);
        if (texture != null) {
            return texture;
        }
        long now = System.currentTimeMillis();
        if (now >= RETRY_AFTER.getOrDefault(key, 0L) && LOADING.add(key)) {
            CompletableFuture.runAsync(() -> loadSkin(playerName, key));
        }
        return FALLBACK;
    }

    private static void loadSkin(String playerName, String key) {
        NativeImage image = null;
        try {
            byte[] png = downloadSkin(playerName);
            image = NativeImage.read(new ByteArrayInputStream(png));
            NativeImage skinImage = image;
            image = null;
            Identifier id = Identifier.fromNamespaceAndPath(NexNpcMod.MOD_ID, "skins/" + key);
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                client.getTextureManager().register(id, new DynamicTexture(() -> "nexnpc_skin_" + key, skinImage));
                TEXTURES.put(key, id);
            });
        } catch (Exception exception) {
            RETRY_AFTER.put(key, System.currentTimeMillis() + Duration.ofMinutes(1).toMillis());
        } finally {
            if (image != null) {
                image.close();
            }
            LOADING.remove(key);
        }
    }

    private static byte[] downloadSkin(String playerName) throws Exception {
        String encodedName = playerName;
        HttpResponse<String> profileResponse = HTTP.send(
                HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + encodedName))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (profileResponse.statusCode() != 200) {
            throw new IllegalStateException("Profil Minecraft introuvable");
        }
        String uuid = JsonParser.parseString(profileResponse.body()).getAsJsonObject().get("id").getAsString();
        if (!uuid.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalStateException("UUID Minecraft invalide");
        }

        HttpResponse<String> skinResponse = HTTP.send(
                HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (skinResponse.statusCode() != 200) {
            throw new IllegalStateException("Texture Minecraft indisponible");
        }
        JsonObject profile = JsonParser.parseString(skinResponse.body()).getAsJsonObject();
        JsonArray properties = profile.getAsJsonArray("properties");
        String encodedTextures = properties.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(property -> property.has("name") && "textures".equals(property.get("name").getAsString()))
                .map(property -> property.get("value").getAsString())
                .findFirst().orElseThrow(() -> new IllegalStateException("Texture de skin absente"));
        JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(encodedTextures)))
                .getAsJsonObject().getAsJsonObject("textures");
        String skinUrl = textures.getAsJsonObject("SKIN").get("url").getAsString();
        URI skinUri = URI.create(skinUrl);
        String prefix = "https://textures.minecraft.net/texture/";
        if (!skinUrl.startsWith(prefix) || !TEXTURE_HASH.matcher(skinUri.getPath().substring("/texture/".length())).matches()) {
            throw new IllegalStateException("URL de texture inattendue");
        }
        HttpResponse<byte[]> imageResponse = HTTP.send(
                HttpRequest.newBuilder(skinUri).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        byte[] body = imageResponse.body();
        if (imageResponse.statusCode() != 200 || body.length == 0 || body.length > 1024 * 1024) {
            throw new IllegalStateException("Fichier de skin invalide");
        }
        return body;
    }
}
