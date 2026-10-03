package com.marcp.directauth.data;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Map;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.marcp.directauth.data.UserData; // Explicit import for UserData

public class DatabaseManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final String connectionString;
    private Connection connection;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public DatabaseManager(Path worldPath) {
        // Guardamos en world/serverconfig/directauth.db
        Path dbPath = worldPath.resolve("serverconfig").resolve("directauth.db");
        this.connectionString = "jdbc:sqlite:" + dbPath.toString();
        
        initDatabase();
        
        // MIGRACIÓN AUTOMÁTICA: Si existe el JSON viejo, lo importamos
        Path oldJsonPath = worldPath.resolve("serverconfig").resolve("DirectAuth_users.json");
        if (Files.exists(oldJsonPath)) {
            migrateFromJson(oldJsonPath);
        }
    }

    private void initDatabase() {
        try {
            // 1. Forzamos la carga del driver (Crucial en entornos moddeados)
            Class.forName("org.sqlite.JDBC");
            
            // 2. Establecemos conexión
            connection = DriverManager.getConnection(connectionString);
            
            try (Statement stmt = connection.createStatement()) {
                // Creamos la tabla si no existe
                stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                        "username TEXT PRIMARY KEY, " +
                        "passwordHash TEXT NOT NULL, " +
                        "isPremium INTEGER DEFAULT 0, " +
                        "onlineUUID TEXT, " +
                        "registrationIp TEXT, " +
                        "texturesValue TEXT, " +
                        "texturesSignature TEXT, " +
                        "totpSecret TEXT, " +
                        "totpEnabled INTEGER DEFAULT 0" +
                        ");");
                
                // MIGRACIÓN PARA SERVIDORES ANTIGUOS
                // Intentamos añadir la columna 'registrationIp' a la tabla existente.
                // Si la columna ya existe, SQLite lanzará un error que ignoraremos de forma segura.
                addColumnIfMissing(stmt, "registrationIp", "TEXT");
                addColumnIfMissing(stmt, "texturesValue", "TEXT");
                addColumnIfMissing(stmt, "texturesSignature", "TEXT");
                addColumnIfMissing(stmt, "totpSecret", "TEXT");
                addColumnIfMissing(stmt, "totpEnabled", "INTEGER DEFAULT 0");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_online_uuid ON users(onlineUUID);");
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("CRITICAL: SQLite driver not found. Make sure the library is bundled with the mod.", e);
        } catch (SQLException e) {
            throw new RuntimeException("CRITICAL: Failed to connect to the SQLite database.", e);
        }
    }

    private void addColumnIfMissing(Statement statement, String name, String type) throws SQLException {
        try {
            statement.execute("ALTER TABLE users ADD COLUMN " + name + " " + type + ";");
            LOGGER.info("NEOauth: Database column added: {}", name);
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase().contains("duplicate column")) throw exception;
        }
    }

    public void close() {
        dbExecutor.shutdown();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOGGER.error("NEOauth database operation failed", e);
        }
    }

    public CompletableFuture<Integer> countAccountsByIPAsync(String ip) {
        return CompletableFuture.supplyAsync(() -> countAccountsByIP(ip), dbExecutor);
    }

    public int countAccountsByIP(String ip) {
        if (ip == null) return 0;
        String sql = "SELECT COUNT(*) FROM users WHERE registrationIp = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, ip);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.error("NEOauth database operation failed", e);
        }
        return 0;
    }

    // --- MÉTODOS CRUD ---

    public CompletableFuture<UserData> getUserAsync(String username) {
        return CompletableFuture.supplyAsync(() -> {
            return getUser(username);
        }, dbExecutor);
    }

    public CompletableFuture<Boolean> updateUserAsync(String username, UserData data) {
        return CompletableFuture.supplyAsync(() -> {
            updateUser(username, data);
            return true;
        }, dbExecutor);
    }

    public CompletableFuture<Boolean> createUserIfAbsentAsync(String username, String passwordHash, String ip) {
        return CompletableFuture.supplyAsync(() -> createUserIfAbsent(username, passwordHash, ip), dbExecutor);
    }

    public UserData getUser(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                UserData data = new UserData(rs.getString("username"), rs.getString("passwordHash"));
                data.setPremium(rs.getInt("isPremium") == 1);
                data.setOnlineUUID(rs.getString("onlineUUID"));
                data.setTextures(rs.getString("texturesValue"), rs.getString("texturesSignature"));
                data.setTotp(rs.getString("totpSecret"), rs.getInt("totpEnabled") == 1);
                return data;
            }
        } catch (SQLException e) {
            LOGGER.error("NEOauth database operation failed", e);
        }
        return null;
    }

    public boolean userExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.error("NEOauth database operation failed", e);
        }
        return false;
    }

    public CompletableFuture<UserData> upsertPremiumIdentityAsync(
            String username, String passwordHash, String ip, String onlineUuid,
            String texturesValue, String texturesSignature) {
        return CompletableFuture.supplyAsync(() -> upsertPremiumIdentity(
                username, passwordHash, ip, onlineUuid, texturesValue, texturesSignature), dbExecutor);
    }

    private UserData upsertPremiumIdentity(String username, String passwordHash, String ip, String onlineUuid,
                                           String texturesValue, String texturesSignature) {
        String normalizedName = username.toLowerCase();
        try {
            connection.setAutoCommit(false);
            UserData byUuid = getUserByOnlineUuid(onlineUuid);
            UserData byName = getUser(normalizedName);

            if (byUuid != null && byName != null
                    && !byUuid.getUsername().equals(byName.getUsername())) {
                throw new IllegalStateException("Premium UUID collision for " + onlineUuid);
            }
            if (byUuid == null && byName != null && byName.isPremium()
                    && byName.getOnlineUUID() != null
                    && !onlineUuid.equalsIgnoreCase(byName.getOnlineUUID())) {
                throw new IllegalStateException("Username is already bound to another premium UUID: " + username);
            }

            if (byUuid != null && !byUuid.getUsername().equals(normalizedName)) {
                try (PreparedStatement rename = connection.prepareStatement(
                        "UPDATE users SET username = ? WHERE username = ? AND onlineUUID = ?")) {
                    rename.setString(1, normalizedName);
                    rename.setString(2, byUuid.getUsername());
                    rename.setString(3, onlineUuid);
                    rename.executeUpdate();
                }
            }

            UserData current = byUuid != null ? byUuid : byName;
            if (current == null) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO users(username, passwordHash, isPremium, onlineUUID, registrationIp, texturesValue, texturesSignature) VALUES(?,?,1,?,?,?,?)")) {
                    insert.setString(1, normalizedName);
                    insert.setString(2, passwordHash);
                    insert.setString(3, onlineUuid);
                    insert.setString(4, ip);
                    insert.setString(5, texturesValue);
                    insert.setString(6, texturesSignature);
                    insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET isPremium = 1, onlineUUID = ?, texturesValue = COALESCE(?, texturesValue), texturesSignature = COALESCE(?, texturesSignature) WHERE username = ?")) {
                    update.setString(1, onlineUuid);
                    update.setString(2, texturesValue);
                    update.setString(3, texturesSignature);
                    update.setString(4, normalizedName);
                    update.executeUpdate();
                }
            }

            connection.commit();
            return getUser(normalizedName);
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollbackError) { exception.addSuppressed(rollbackError); }
            throw new IllegalStateException("Failed to upsert premium identity " + username, exception);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException exception) { LOGGER.error("Failed to restore SQLite autocommit", exception); }
        }
    }

    private UserData getUserByOnlineUuid(String onlineUuid) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM users WHERE onlineUUID = ? LIMIT 1")) {
            statement.setString(1, onlineUuid);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                UserData data = new UserData(rs.getString("username"), rs.getString("passwordHash"));
                data.setPremium(rs.getInt("isPremium") == 1);
                data.setOnlineUUID(rs.getString("onlineUUID"));
                data.setTextures(rs.getString("texturesValue"), rs.getString("texturesSignature"));
                data.setTotp(rs.getString("totpSecret"), rs.getInt("totpEnabled") == 1);
                return data;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to find premium identity " + onlineUuid, exception);
        }
    }

    public CompletableFuture<Boolean> createPremiumUserIfAbsentAsync(
            String username, String passwordHash, String ip, String onlineUuid,
            String texturesValue, String texturesSignature) {
        return CompletableFuture.supplyAsync(
                () -> createPremiumUserIfAbsent(username, passwordHash, ip, onlineUuid, texturesValue, texturesSignature), dbExecutor);
    }

    private boolean createPremiumUserIfAbsent(String username, String passwordHash, String ip, String onlineUuid,
                                              String texturesValue, String texturesSignature) {
        String sql = "INSERT INTO users(username, passwordHash, isPremium, onlineUUID, registrationIp, texturesValue, texturesSignature) "
                + "VALUES(?,?,1,?,?,?,?) ON CONFLICT(username) DO NOTHING";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            pstmt.setString(2, passwordHash);
            pstmt.setString(3, onlineUuid);
            pstmt.setString(4, ip);
            pstmt.setString(5, texturesValue);
            pstmt.setString(6, texturesSignature);
            return pstmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create premium account " + username, e);
        }
    }

    private boolean createUserIfAbsent(String username, String passwordHash, String ip) {
        String sql = "INSERT INTO users(username, passwordHash, isPremium, onlineUUID, registrationIp) "
                + "VALUES(?,?,0,NULL,?) ON CONFLICT(username) DO NOTHING";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            pstmt.setString(2, passwordHash);
            pstmt.setString(3, ip);
            return pstmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create account " + username, e);
        }
    }

    public void createUser(String username, String passwordHash, String ip) {
        String sql = "INSERT INTO users(username, passwordHash, isPremium, onlineUUID, registrationIp) VALUES(?,?,0,NULL,?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            pstmt.setString(2, passwordHash);
            pstmt.setString(3, ip);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("NEOauth database operation failed", e);
        }
    }

    public void updateUser(String username, UserData data) {
        String sql = "UPDATE users SET passwordHash = ?, isPremium = ?, onlineUUID = ?, texturesValue = ?, texturesSignature = ?, totpSecret = ?, totpEnabled = ? WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, data.getPasswordHash());
            pstmt.setInt(2, data.isPremium() ? 1 : 0);
            pstmt.setString(3, data.getOnlineUUID());
            pstmt.setString(4, data.getTexturesValue());
            pstmt.setString(5, data.getTexturesSignature());
            pstmt.setString(6, data.getTotpSecret());
            pstmt.setInt(7, data.isTotpEnabled() ? 1 : 0);
            pstmt.setString(8, username.toLowerCase());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("NEOauth database operation failed", e);
        }
    }

    public CompletableFuture<Boolean> deleteUserAsync(String username) {
        return CompletableFuture.supplyAsync(() -> deleteUserReliable(username), dbExecutor);
    }

    private boolean deleteUserReliable(String username) {
        String sql = "DELETE FROM users WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete account " + username, e);
        }
    }

    public void deleteUser(String username) {
        deleteUserReliable(username);
    }

    // --- MIGRACIÓN (Solo se ejecuta una vez) ---
    private void migrateFromJson(Path jsonPath) {
        System.out.println("DirectAuth: Migrating JSON database to SQLite...");
        try {
            Gson gson = new Gson();
            String jsonContent = Files.readString(jsonPath);
            Map<String, UserData> legacyUsers = gson.fromJson(jsonContent, new TypeToken<Map<String, UserData>>(){}.getType());
            
            if (legacyUsers != null) {
                // Usamos una transacción para que sea rápido
                connection.setAutoCommit(false);
                String sql = "INSERT OR IGNORE INTO users(username, passwordHash, isPremium, onlineUUID) VALUES(?,?,?,?)";
                
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    for (UserData user : legacyUsers.values()) {
                        pstmt.setString(1, user.getUsername().toLowerCase());
                        pstmt.setString(2, user.getPasswordHash());
                        pstmt.setInt(3, user.isPremium() ? 1 : 0);
                        pstmt.setString(4, user.getOnlineUUID());
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    LOGGER.error("NEOauth database operation failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            
            // Renombrar el JSON para no volver a importarlo
            Files.move(jsonPath, jsonPath.resolveSibling("DirectAuth_users.json.MIGRATED"));
            LOGGER.info("NEOauth: Legacy JSON migration completed");
            
        } catch (IOException | SQLException e) {
            LOGGER.error("NEOauth: Legacy JSON migration failed", e);
        }
    }
}