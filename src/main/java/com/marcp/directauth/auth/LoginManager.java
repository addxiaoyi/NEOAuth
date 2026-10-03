package com.marcp.directauth.auth;

import net.minecraft.server.level.ServerPlayer;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.marcp.directauth.data.UserData; // Asegúrate de importar esto

public class LoginManager {
    // Jugadores actualmente autenticados (UUID offline -> true)
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AuthenticationMethod> authenticationMethods = new ConcurrentHashMap<>();
    
    // Cooldown de intentos fallidos (UUID -> timestamp del último intento)
    private final Map<UUID, Long> loginAttempts = new ConcurrentHashMap<>();
    
    // Contador de intentos fallidos (UUID -> contador)
    private final Map<UUID, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, IpAttempt> ipAttempts = new ConcurrentHashMap<>();

    // Mapa para guardar el momento exacto de la conexión
    private final Map<UUID, Long> connectionTimes = new ConcurrentHashMap<>();

    private static final long PREMIUM_FALLBACK_MARKER_TTL_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long AUTOMATIC_LOGIN_MARKER_TTL_MS = TimeUnit.MINUTES.toMillis(2);
    private final Map<UUID, Long> premiumPasswordFallbacks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> automaticPremiumLogins = new ConcurrentHashMap<>();
    private final Map<UUID, UserData> pendingTotp = new ConcurrentHashMap<>();

    public long getConnectionTime(ServerPlayer player) {
        return connectionTimes.getOrDefault(player.getUUID(), System.currentTimeMillis());
    }

    // NUEVO: Caché temporal para pre-carga (Usuario -> Datos)
    private final Map<String, UserData> preLoginCache = new ConcurrentHashMap<>();

    // NUEVO: Mapa para sesiones en periodo de gracia
    private final Map<UUID, GraceSession> graceSessions = new ConcurrentHashMap<>();

    // 1. Definimos el Scheduler (1 hilo es suficiente para mantenimiento)
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Referencia a la tarea de limpieza recurrente, para poder reprogramarla en un reload
    private ScheduledFuture<?> cleanupTask;

    public LoginManager() {
        // Constructor vacío, la inicialización se hace en init()
    }

    /**
     * Inicializa (o reprograma) la tarea de limpieza con el intervalo configurado.
     * Es idempotente: cancela la tarea anterior antes de programar la nueva, así que
     * puede llamarse de forma segura tanto al arrancar como en cada /directauth reload.
     * Debe llamarse DESPUÉS de cargar la configuración.
     */
    public void init(int cleanupIntervalMinutes) {
        if (cleanupIntervalMinutes <= 0) cleanupIntervalMinutes = 10; // Fallback seguro

        // Cancelar la tarea previa para no apilar limpiezas recurrentes en cada reload
        if (cleanupTask != null) cleanupTask.cancel(false);

        // Ejecutar cada X minutos, con un delay inicial igual al intervalo
        cleanupTask = scheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, cleanupIntervalMinutes, cleanupIntervalMinutes, TimeUnit.MINUTES);
    }

    // Configuración de PBKDF2
    private static final int ITERATIONS = 100000;
    private static final String AUTO_PASSWORD_PREFIX = "AUTO_GENERATED:";
    private static final int ARGON2_MEMORY_KIB = 19_456;
    private static final int ARGON2_ITERATIONS = 2;
    private static final int ARGON2_PARALLELISM = 1;
    private static final int ARGON2_HASH_BYTES = 32;
    private static final String ARGON2_PREFIX = "argon2id$";
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Tarea de mantenimiento: Elimina sesiones caducadas de la memoria.
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        
        // graceSessions es un ConcurrentHashMap, así que podemos iterar y borrar con seguridad
        // usando removeIf (disponible desde Java 8)
        graceSessions.entrySet().removeIf(entry -> {
            long expirationTime = entry.getValue().expirationTime();
            // Si el tiempo actual es mayor al de expiración, borramos
            return now > expirationTime;
        });
        premiumPasswordFallbacks.entrySet().removeIf(entry -> now > entry.getValue());
        automaticPremiumLogins.entrySet().removeIf(entry -> now > entry.getValue());
        pendingTotp.entrySet().removeIf(entry -> !entry.getValue().isTotpEnabled());
        ipAttempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        
        // Opcional: Log de depuración si quieres ver cuándo ocurre (quita esto en producción para evitar spam)
        // com.marcp.directauth.DirectAuth.LOGGER.debug("Limpieza de sesiones completada.");
    }

    /**
     * Importante: Método para detener el hilo cuando el servidor se apaga.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    public boolean isAuthenticated(ServerPlayer player) {
        return authenticatedPlayers.contains(player.getUUID());
    }

    public void markPremiumPasswordFallback(UUID premiumUuid) {
        premiumPasswordFallbacks.put(premiumUuid, System.currentTimeMillis() + PREMIUM_FALLBACK_MARKER_TTL_MS);
    }

    public void clearPremiumPasswordFallback(UUID premiumUuid) {
        premiumPasswordFallbacks.remove(premiumUuid);
    }

    public boolean consumePremiumPasswordFallback(UUID premiumUuid) {
        Long expiresAt = premiumPasswordFallbacks.remove(premiumUuid);
        return expiresAt != null && expiresAt >= System.currentTimeMillis();
    }


    public void markAutomaticPremiumLogin(UUID premiumUuid) {
        automaticPremiumLogins.put(premiumUuid, System.currentTimeMillis() + AUTOMATIC_LOGIN_MARKER_TTL_MS);
    }

    public boolean consumeAutomaticPremiumLogin(UUID premiumUuid) {
        Long expiresAt = automaticPremiumLogins.remove(premiumUuid);
        return expiresAt != null && expiresAt >= System.currentTimeMillis();
    }
    
    public void beginTotp(ServerPlayer player, UserData userData) {
        pendingTotp.put(player.getUUID(), userData);
    }

    public UserData consumeTotp(ServerPlayer player) {
        return pendingTotp.remove(player.getUUID());
    }

    public boolean isAwaitingTotp(ServerPlayer player) {
        return pendingTotp.containsKey(player.getUUID());
    }

    public void setAuthenticated(ServerPlayer player, boolean authenticated) {
        if (authenticated) {
            authenticatedPlayers.add(player.getUUID());
            authenticationMethods.putIfAbsent(player.getUUID(), AuthenticationMethod.PASSWORD);
            failedAttempts.remove(player.getUUID());
            loginAttempts.remove(player.getUUID());
        } else {
            authenticatedPlayers.remove(player.getUUID());
            authenticationMethods.remove(player.getUUID());
        }
    }

    public void markAuthenticationMethod(ServerPlayer player, AuthenticationMethod method) {
        authenticationMethods.put(player.getUUID(), method);
    }

    public AuthenticationMethod getAuthenticationMethod(ServerPlayer player) {
        return authenticationMethods.getOrDefault(player.getUUID(), AuthenticationMethod.NONE);
    }
    
    public void removePlayer(ServerPlayer player) {
        authenticatedPlayers.remove(player.getUUID());
        authenticationMethods.remove(player.getUUID());
        loginAttempts.remove(player.getUUID());
        failedAttempts.remove(player.getUUID());
        connectionTimes.remove(player.getUUID());
        premiumPasswordFallbacks.remove(player.getUUID());
        automaticPremiumLogins.remove(player.getUUID());
        pendingTotp.remove(player.getUUID());
        preLoginCache.remove(player.getGameProfile().getName().toLowerCase()); // Limpiar también la caché al desconectar
    }

    /**
     * Se llama cuando el jugador se desconecta.
     * Guarda la IP y la hora de expiración en RAM.
     */
    public void pauseSession(ServerPlayer player) {
        // 1. Quitamos del set de autenticados activos
        setAuthenticated(player, false); 
        
        // 2. Calculamos expiración
        long durationSeconds = com.marcp.directauth.DirectAuth.getConfig().sessionGracePeriod;
        if (durationSeconds <= 0) return; // Si está desactivado (0), no guardamos nada

        long expiryTime = System.currentTimeMillis() + (durationSeconds * 1000);
        // 3. Guardamos en el "Limbo" sin vincular la sesión a la IP.
        graceSessions.put(player.getUUID(), new GraceSession(expiryTime));
    }

    /**
     * Se llama cuando el jugador entra.
     * Intenta recuperar la sesión mientras no haya expirado.
     */
    public boolean tryRestoreSession(ServerPlayer player) {
        UUID uuid = player.getUUID();
        GraceSession session = graceSessions.get(uuid);

        // Si no hay sesión guardada, nada que hacer
        if (session == null) return false;

        // Limpieza: Ya la hemos recuperado o vamos a descartarla, así que la borramos del mapa
        graceSessions.remove(uuid);

        // 1. Chequeo de Tiempo
        if (System.currentTimeMillis() > session.expirationTime) {
            return false; // Caducó
        }

        // 2. Restaurar sin exigir la misma IP.
        setAuthenticated(player, true);
        return true;
    }

    public void invalidateSession(ServerPlayer player) {
        graceSessions.remove(player.getUUID());
    }

    // Clase interna simple para guardar los datos
    private record GraceSession(long expirationTime) {}

    public void recordJoin(ServerPlayer player) {
        connectionTimes.put(player.getUUID(), System.currentTimeMillis());
    }

    public int getRemainingLoginSeconds(ServerPlayer player) {
        if (isAuthenticated(player)) return 0;
        Long joinTime = connectionTimes.get(player.getUUID());
        if (joinTime == null) return Math.max(0, com.marcp.directauth.DirectAuth.getConfig().loginTimeout);
        long elapsed = (System.currentTimeMillis() - joinTime) / 1000;
        return Math.max(0, com.marcp.directauth.DirectAuth.getConfig().loginTimeout - (int) elapsed);
    }

    public boolean hasTimedOut(ServerPlayer player) {
        if (isAuthenticated(player)) return false; // Si ya está dentro, no hay timeout

        Long joinTime = connectionTimes.get(player.getUUID());
        if (joinTime == null) return false; // Por seguridad

        long elapsedSeconds = (System.currentTimeMillis() - joinTime) / 1000;
        return elapsedSeconds >= com.marcp.directauth.DirectAuth.getConfig().loginTimeout;
    }
    
    public boolean canAttemptLogin(ServerPlayer player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        Long lastAttempt = loginAttempts.get(uuid);
        if (lastAttempt != null
                && now - lastAttempt < com.marcp.directauth.DirectAuth.getConfig().loginCooldownMs) {
            return false;
        }

        IpAttempt ipAttempt = ipAttempts.get(normalizeIp(player.getIpAddress()));
        return ipAttempt == null || !ipAttempt.isBlocked(now);
    }
    
    public void recordLoginAttempt(ServerPlayer player, boolean success) {
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        String ip = normalizeIp(player.getIpAddress());
        loginAttempts.put(uuid, now);

        if (success) {
            failedAttempts.remove(uuid);
            ipAttempts.remove(ip);
            return;
        }

        failedAttempts.merge(uuid, 1, Integer::sum);
        IpAttempt attempt = ipAttempts.computeIfAbsent(ip, ignored -> new IpAttempt());
        attempt.recordFailure(now, Math.max(1, com.marcp.directauth.DirectAuth.getConfig().maxLoginAttempts));
    }

    private static String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }
    
    public int getFailedAttempts(ServerPlayer player) {
        return failedAttempts.getOrDefault(player.getUUID(), 0);
    }
    
    public boolean hasExceededMaxAttempts(ServerPlayer player) {
        return getFailedAttempts(player) >= com.marcp.directauth.DirectAuth.getConfig().maxLoginAttempts;
    }

    // MÉTODOS PARA LA PRE-CARGA
    public void addPreLoadedData(String username, UserData data) {
        // Guardamos el dato (incluso si es null, para saber que ya buscamos y no existe)
        if (username != null) {
            preLoginCache.put(username.toLowerCase(), data != null ? data : new UserData("NULL_MARKER", ""));
        }
    }

    public UserData getAndRemovePreLoadedData(String username) {
        if (username == null) return null;
        UserData data = preLoginCache.remove(username.toLowerCase());
        
        // Si es el marcador de "no existe", devolvemos null real
        if (data != null && "null_marker".equals(data.getUsername())) {
            return null;
        }
        return data;
    }

    public boolean isPreLoaded(String username) {
        if (username == null) return false;
        return preLoginCache.containsKey(username.toLowerCase());
    }

    public void clearPreLoadedData(String username) {
        if (username != null) {
            preLoginCache.remove(username.toLowerCase());
        }
    }
    
    public enum AuthenticationMethod {
        NONE,
        PASSWORD,
        PREMIUM,
        SESSION,
        PREMIUM_OUTAGE_FALLBACK,
        TOTP_PENDING
    }

    private static final class IpAttempt {
        private int failures;
        private long blockedUntil;

        void recordFailure(long now, int maxAttempts) {
            failures++;
            if (failures >= maxAttempts * 2) {
                blockedUntil = now + TimeUnit.HOURS.toMillis(1);
            } else if (failures >= maxAttempts) {
                blockedUntil = now + TimeUnit.MINUTES.toMillis(5);
            } else if (failures >= Math.max(3, maxAttempts / 2)) {
                blockedUntil = Math.max(blockedUntil, now + TimeUnit.SECONDS.toMillis(30));
            }
        }

        boolean isBlocked(long now) {
            return blockedUntil > now;
        }

        boolean isExpired(long now) {
            return failures == 0 || (blockedUntil <= now && now - blockedUntil > TimeUnit.HOURS.toMillis(1));
        }
    }

    // --- Hashing con PBKDF2 (Nativo Java) ---
    
    public static String generateUnconfiguredPasswordHash() {
        return AUTO_PASSWORD_PREFIX + UUID.randomUUID();
    }

    public static boolean passwordNeedsSetup(String storedHash) {
        return storedHash != null && storedHash.startsWith(AUTO_PASSWORD_PREFIX);
    }

    public static String hashPassword(String password) {
        if (password == null) throw new IllegalArgumentException("Password cannot be null");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        char[] passwordChars = password.toCharArray();
        try {
            byte[] hash = argon2id(passwordChars, salt, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS, ARGON2_PARALLELISM);
            return ARGON2_PREFIX + ARGON2_MEMORY_KIB + "$" + ARGON2_ITERATIONS + "$"
                    + ARGON2_PARALLELISM + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    public static boolean passwordNeedsRehash(String storedHash) {
        return storedHash == null || !storedHash.startsWith(ARGON2_PREFIX);
    }

    public static CompletableFuture<Boolean> checkPasswordAsync(String password, String storedHash) {
        return CompletableFuture.supplyAsync(() -> checkPassword(password, storedHash));
    }

    public static boolean checkPassword(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        if (storedHash.startsWith(ARGON2_PREFIX)) return checkArgon2(password, storedHash);
        return checkPbkdf2(password, storedHash);
    }

    private static boolean checkArgon2(String password, String storedHash) {
        String[] parts = storedHash.split("\\$", -1);
        if (parts.length != 6 || !"argon2id".equals(parts[0])) return false;
        try {
            int memory = Integer.parseInt(parts[1]);
            int iterations = Integer.parseInt(parts[2]);
            int parallelism = Integer.parseInt(parts[3]);
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            char[] chars = password.toCharArray();
            try {
                byte[] actual = argon2id(chars, salt, memory, iterations, parallelism);
                return MessageDigest.isEqual(expected, actual);
            } finally {
                Arrays.fill(chars, '\0');
            }
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean checkPbkdf2(String password, String storedHash) {
        String[] parts = storedHash.split(":", -1);
        if (parts.length != 2) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] originalHash = Base64.getDecoder().decode(parts[1]);
            char[] chars = password.toCharArray();
            try {
                return MessageDigest.isEqual(originalHash, pbkdf2(chars, salt));
            } finally {
                Arrays.fill(chars, '\0');
            }
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] argon2id(char[] password, byte[] salt, int memory, int iterations, int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] hash = new byte[ARGON2_HASH_BYTES];
        generator.generateBytes(password, hash);
        return hash;
    }

    private static byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
