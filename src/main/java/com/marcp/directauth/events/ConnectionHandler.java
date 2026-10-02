package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public class ConnectionHandler {
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Inicializar la base de datos usando la ruta del nivel principal
        Path worldRoot = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        DirectAuth.initDatabase(worldRoot);
        Path configPath = event.getServer().getFile("config/Neoauth/Neoauth.toml");
        Path legacyConfig = worldRoot.resolve("serverconfig").resolve("DirectAuth-config.json");
        Path legacyLanguages = worldRoot.resolve("serverconfig");
        DirectAuth.initConfig(configPath, legacyConfig, legacyLanguages);
        if (java.nio.file.Files.exists(configPath)) {
            try {
                java.nio.file.Files.deleteIfExists(legacyConfig);
                java.nio.file.Files.deleteIfExists(legacyLanguages.resolve("DirectAuth-lang-en.json"));
                java.nio.file.Files.deleteIfExists(legacyLanguages.resolve("DirectAuth-lang-zh.json"));
                java.nio.file.Files.deleteIfExists(legacyLanguages.resolve("DirectAuth-lang-es.json"));
            } catch (java.io.IOException exception) {
                DirectAuth.LOGGER.warn("NEOauth: Could not remove legacy JSON configuration files", exception);
            }
        }
        
        // Inicializar el Scheduler del LoginManager con la configuración cargada
        DirectAuth.getLoginManager().init(DirectAuth.getConfig().sessionCleanupInterval);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (DirectAuth.getDatabase() != null) {
            DirectAuth.getDatabase().close();
        }
        
        // 2. NUEVO: Detener el Scheduler de sesiones
        if (DirectAuth.getLoginManager() != null) {
            DirectAuth.getLoginManager().shutdown();
        }
    }
    
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Registrar tiempo de entrada
            DirectAuth.getLoginManager().recordJoin(player);
            String username = player.getGameProfile().getName();

            // 1. Intentamos obtener datos PRE-CARGADOS (Instanteo)
            UserData cachedData = DirectAuth.getLoginManager().getAndRemovePreLoadedData(username);

            if (cachedData != null || DirectAuth.getLoginManager().isPreLoaded(username)) { 
                // Si los datos ya estaban en caché (o el marcador de "no existe")
                processLogin(player, cachedData);
            } else {
                // 2. Si no dio tiempo a cargar (raro), hacemos el fallback asíncrono
                DirectAuth.getDatabase().getUserAsync(username).thenAcceptAsync(userData -> {
                    processLogin(player, userData);
                }, player.getServer());
            }
        }
    }

    // Método auxiliar para no duplicar código
    private void processLogin(ServerPlayer player, UserData userData) {
        boolean automaticPremiumLogin = DirectAuth.getLoginManager()
                .consumeAutomaticPremiumLogin(player.getUUID());
        boolean isAuthenticated = automaticPremiumLogin;

        if (automaticPremiumLogin) {
            DirectAuth.getLoginManager().setAuthenticated(player, true);
            boolean passwordNeedsSetup = userData != null
                    && LoginManager.passwordNeedsSetup(userData.getPasswordHash());
            player.sendSystemMessage(Component.literal(passwordNeedsSetup
                    ? DirectAuth.getConfig().getLang().msgPremiumAccountCreated
                    : DirectAuth.getConfig().getLang().msgAutoLogin));
            if (passwordNeedsSetup) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoPasswordSetup));
            }
        }
        boolean premiumPasswordFallback = DirectAuth.getLoginManager()
                .consumePremiumPasswordFallback(player.getUUID());

        // Caso Premium
        if (!automaticPremiumLogin && userData != null && userData.isPremium()) {
            String expectedUUID = userData.getOnlineUUID();
            String actualUUID = player.getStringUUID();
            
            if (expectedUUID != null && expectedUUID.equalsIgnoreCase(actualUUID)
                    && !premiumPasswordFallback) {
                DirectAuth.getLoginManager().setAuthenticated(player, true);
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoLogin));
                isAuthenticated = true;
            } else if (expectedUUID != null && expectedUUID.equalsIgnoreCase(actualUUID)) {
                DirectAuth.getLoginManager().invalidateSession(player);
                DirectAuth.LOGGER.info("Known premium player {} is using password login with UUID {}",
                        player.getGameProfile().getName(), actualUUID);
            } else {
                player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgPremiumError));
                return;
            }
        }

        if (!isAuthenticated) {
            // INTENTO DE RESTAURACIÓN DE SESIÓN
            if (!premiumPasswordFallback && DirectAuth.getLoginManager().tryRestoreSession(player)) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgSessionRestored));
                isAuthenticated = true;
            }
        }
        
        // Si NO está autenticado (usuario nuevo o login pendiente), ocultar coordenadas
        if (!isAuthenticated) {
            // [CAMBIO CLAVE] Solo guardar posición si está vivo y con salud
            if (!player.isDeadOrDying() && player.getHealth() > 0) {
                DirectAuth.getPositionManager().savePosition(player);
            }

            // Teletransportar al Spawn del Overworld
            ServerLevel overworld = player.getServer().overworld();
            BlockPos spawnPos = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);

            // El mixin de tickEffects congela las duraciones en servidor. Limpiamos
            // el HUD del cliente para que no muestre cuentas atrás fantasma; el
            // estado real persiste y se resincronizará al autenticar.
            PlayerRestrictionHandler.hideEffectsFromClient(player);
            
            if (premiumPasswordFallback) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumFallbackLogin));
            } else if (userData == null) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgWelcome));
            } else {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgLoginRequest));
                if (!userData.isPremium()) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumHint));
                }
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // SI estaba autenticado, guardamos la sesión temporal
            if (DirectAuth.getLoginManager().isAuthenticated(player)) {
                DirectAuth.getLoginManager().pauseSession(player);
            } else {
                // Si no estaba logueado, limpieza normal
                DirectAuth.getLoginManager().removePlayer(player);
            }

            PlayerRestrictionHandler.removeAnchor(player);
            // Nota: No borramos la posición guardada aquí. Se mantiene hasta que se autentique correctamente.
        }
    }
}