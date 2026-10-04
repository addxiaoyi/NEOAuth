package com.marcp.directauth.auth;

import com.marcp.directauth.DirectAuth;
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
import java.util.ArrayList;
import java.util.List;

public final class MojangAPI {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private MojangAPI() {}

    /**
     * Looks up a username through Mojang's public profile endpoint.
     * A null result means the name is invalid, unknown, or the service was unavailable.
     */
    public static CompletableFuture<String> getOnlineUUID(String username) {
        if (!isValidUsername(username)) return CompletableFuture.completedFuture(null);

        List<String> endpoints = endpointList(DirectAuth.getConfig().skinNameEndpoint,
                DirectAuth.getConfig().skinNameFallbackEndpoints);
        return lookupName(endpoints, 0, username);
    }

    /** Fetches a signed profile directly, bypassing third-party Authlib host rewrites. */
    public static GameProfile fetchSignedProfile(UUID uuid, String fallbackName) {
        List<String> endpoints = endpointList(DirectAuth.getConfig().skinProfileEndpoint,
                DirectAuth.getConfig().skinFallbackEndpoints);
        return fetchSignedProfile(endpoints, 0, uuid, fallbackName);
    }

    private static CompletableFuture<String> lookupName(List<String> endpoints, int index, String username) {
        if (index >= endpoints.size()) return CompletableFuture.completedFuture(null);
        URI uri = configuredUri(endpoints.get(index), "name", username);
        if (uri == null) return lookupName(endpoints, index + 1, username);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenCompose(response -> {
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) return lookupName(endpoints, index + 1, username);
                String payload = readLimited(body);
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                String uuid = json.has("id") ? formatUUID(json.get("id").getAsString()) : null;
                return uuid != null ? CompletableFuture.completedFuture(uuid)
                        : lookupName(endpoints, index + 1, username);
            } catch (Exception exception) {
                LOGGER.warn("NEOauth: skin name endpoint failed: {}", uri.getHost());
                return lookupName(endpoints, index + 1, username);
            }
        }).exceptionallyCompose(error -> lookupName(endpoints, index + 1, username));
    }

    private static GameProfile fetchSignedProfile(List<String> endpoints, int index, UUID uuid, String fallbackName) {
        if (index >= endpoints.size()) return null;
        URI uri = configuredUri(endpoints.get(index), "uuid", uuid.toString().replace("-", ""));
        if (uri == null) return fetchSignedProfile(endpoints, index + 1, uuid, fallbackName);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) return fetchSignedProfile(endpoints, index + 1, uuid, fallbackName);
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
            LOGGER.warn("NEOauth: skin profile endpoint failed: {}", uri.getHost());
            return fetchSignedProfile(endpoints, index + 1, uuid, fallbackName);
        }
    }

    private static List<String> endpointList(String primary, String fallbacks) {
        List<String> endpoints = new ArrayList<>();
        if (primary != null && !primary.isBlank()) endpoints.add(primary.trim());
        if (fallbacks != null && !fallbacks.isBlank()) {
            for (String value : fallbacks.split(",")) {
                if (!value.isBlank() && !endpoints.contains(value.trim())) endpoints.add(value.trim());
            }
        }
        return endpoints;
    }

    private static URI configuredUri(String template, String placeholder, String value) {
        if (template == null || template.isBlank()) return null;
        String encoded = java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String raw = template.replace("{" + placeholder + "}", encoded);
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !isAllowedHost(uri.getHost())) return null;
            return uri;
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("NEOauth: invalid skin endpoint template", exception);
            return null;
        }
    }

    private static boolean isAllowedHost(String host) {
        if (host == null || DirectAuth.getConfig() == null) return false;
        for (String allowed : DirectAuth.getConfig().skinAllowedHosts.split(",")) {
            if (host.equalsIgnoreCase(allowed.trim())) return true;
        }
        return false;
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
