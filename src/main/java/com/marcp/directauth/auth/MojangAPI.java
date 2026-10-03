package com.marcp.directauth.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

public final class MojangAPI {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final String API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private MojangAPI() {}

    /**
     * Looks up a username through Mojang's public profile endpoint.
     * A null result means the name is invalid, unknown, or the service was unavailable.
     */
    public static CompletableFuture<String> getOnlineUUID(String username) {
        if (!isValidUsername(username)) return CompletableFuture.completedFuture(null);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + username))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    try (InputStream body = response.body()) {
                        if (response.statusCode() != 200) return null;
                        String payload = readLimited(body);
                        JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                        return json.has("id") ? formatUUID(json.get("id").getAsString()) : null;
                    } catch (Exception exception) {
                        LOGGER.warn("NEOauth: Mojang profile response could not be parsed for {}", username, exception);
                        return null;
                    }
                })
                .exceptionally(exception -> {
                    LOGGER.warn("NEOauth: Mojang profile lookup failed for {}", username, exception);
                    return null;
                });
    }

    /** Fetches a signed profile directly, bypassing third-party Authlib host rewrites. */
    public static GameProfile fetchSignedProfile(UUID uuid, String fallbackName) {
        URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                + uuid.toString().replace("-", "") + "?unsigned=false");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) return null;
            String payload;
            try (InputStream body = response.body()) {
                payload = readLimited(body);
            }
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String name = json.has("name") ? json.get("name").getAsString() : fallbackName;
            GameProfile profile = new GameProfile(uuid, name);
            if (!json.has("properties")) return profile;
            for (var property : json.getAsJsonArray("properties")) {
                JsonObject item = property.getAsJsonObject();
                if (!item.has("name") || !item.has("value")) continue;
                String signature = item.has("signature") ? item.get("signature").getAsString() : null;
                profile.getProperties().put(item.get("name").getAsString(),
                        signature == null
                                ? new Property(item.get("name").getAsString(), item.get("value").getAsString())
                                : new Property(item.get("name").getAsString(), item.get("value").getAsString(), signature));
            }
            return profile;
        } catch (Exception exception) {
            LOGGER.warn("NEOauth: direct signed profile lookup failed for {}", uuid, exception);
            return null;
        }
    }

    private static boolean isValidUsername(String username) {
        return username != null && username.matches("[A-Za-z0-9_]{3,16}");
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("Mojang response exceeded the size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Formats a UUID without dashes into the canonical representation. */
    public static String formatUUID(String uuidWithoutDashes) {
        if (uuidWithoutDashes == null || !uuidWithoutDashes.matches("[0-9a-fA-F]{32}")) return null;
        return uuidWithoutDashes.substring(0, 8) + "-"
                + uuidWithoutDashes.substring(8, 12) + "-"
                + uuidWithoutDashes.substring(12, 16) + "-"
                + uuidWithoutDashes.substring(16, 20) + "-"
                + uuidWithoutDashes.substring(20);
    }
}
