package com.marcp.directauth.data;

import com.marcp.directauth.DirectAuth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MigrationManager {
    private static final Map<String, Object> MIGRATION_LOCKS = new ConcurrentHashMap<>();

    public static boolean migratePlayerData(ServerPlayer player, String targetUuid) {
        return migratePlayerData(player.getServer(), player.getStringUUID(), targetUuid);
    }

    public static boolean migratePlayerData(MinecraftServer server, String sourceUuid, String targetUuid) {
        if (sourceUuid.equalsIgnoreCase(targetUuid)) return true;

        String lockKey = targetUuid.toLowerCase();
        Object lock = MIGRATION_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            try {
                migrateLocked(server, sourceUuid, targetUuid);
                return true;
            } catch (Exception exception) {
                DirectAuth.LOGGER.error("NEOauth migration failed {} -> {}", sourceUuid, targetUuid, exception);
                return false;
            } finally {
                MIGRATION_LOCKS.remove(lockKey, lock);
            }
        }
    }

    private static void migrateLocked(MinecraftServer server, String sourceUuid, String targetUuid) throws IOException {
        String sourceCompact = sourceUuid.replace("-", "");
        String targetCompact = targetUuid.replace("-", "");
        File worldDir = server.getWorldPath(LevelResource.ROOT).toFile();
        Map<String, MigrationMode> migrationMap = DirectAuth.getConfig().migrationMap;

        for (Map.Entry<String, MigrationMode> entry : migrationMap.entrySet()) {
            File folder = new File(worldDir, entry.getKey());
            if (!folder.isDirectory()) continue;

            if (entry.getValue() == MigrationMode.DIRECTORY) {
                migrateDirectory(folder, sourceUuid, targetUuid);
                continue;
            }

            File[] sourceFiles = folder.listFiles((dir, name) -> name.startsWith(sourceUuid));
            if (sourceFiles == null) continue;
            for (File sourceFile : sourceFiles) {
                File targetFile = new File(folder, sourceFile.getName().replace(sourceUuid, targetUuid));
                moveOrBackup(sourceFile, targetFile);
                if (entry.getValue() == MigrationMode.TEXT_REPLACE) {
                    replaceUuidText(targetFile, sourceUuid, targetUuid, sourceCompact, targetCompact);
                }
            }
        }
    }

    private static void migrateDirectory(File folder, String sourceUuid, String targetUuid) throws IOException {
        File source = new File(folder, sourceUuid);
        if (!source.isDirectory()) return;

        File target = new File(folder, targetUuid);
        if (target.exists()) backup(target);
        if (!source.renameTo(target)) {
            throw new IOException("Failed to move directory " + source);
        }
        DirectAuth.LOGGER.info("NEOauth migration directory {} -> {}", source.getName(), target.getName());
    }

    private static void moveOrBackup(File source, File target) throws IOException {
        if (target.exists()) backup(target);
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        DirectAuth.LOGGER.info("NEOauth migration file {}", target.getName());
    }

    private static void backup(File target) throws IOException {
        File backup = new File(target.getParentFile(), target.getName() + ".bak_" + System.currentTimeMillis());
        Files.move(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void replaceUuidText(File file, String sourceUuid, String targetUuid,
                                        String sourceCompact, String targetCompact) throws IOException {
        String original = Files.readString(file.toPath());
        String replaced = original.replace(sourceUuid, targetUuid).replace(sourceCompact, targetCompact);
        if (!original.equals(replaced)) {
            Files.writeString(file.toPath(), replaced);
            DirectAuth.LOGGER.info("NEOauth migration text updated {}", file.getName());
        }
    }
}
