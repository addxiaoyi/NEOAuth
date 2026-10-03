package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
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
        // Stop scheduled database work before closing the shared SQLite connection.
        if (DirectAuth.getLoginManager() != null) {
            DirectAuth.getLoginManager().shutdown();
        }
        if (DirectAuth.getDatabase() != null) {
            DirectAuth.getDatabase().close();
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
                restorePersistentOrProcess(player, cachedData);
            } else {
                // 2. Si no dio tiempo a cargar (raro), hacemos el fallback asíncrono
                DirectAuth.getDatabase().getUserAsync(username).thenAcceptAsync(userData -> {
                    restorePersistentOrProcess(player, userData);
                }, player.getServer());
            }
            // Vanilla's offline login can publish the tab-list profile before the
            // premium property is finalized. Refresh it once the login event runs.
            player.getServer().execute(() -> player.getServer().execute(() -> refreshPremiumProfile(player)));
        }
    }

    private void refreshPremiumProfile(ServerPlayer player) {
        if (!player.connection.isAcceptingMessages()
                || !DirectAuth.getLoginManager().isAuthenticated(player)
                || player.getGameProfile().getProperties().get("textures").isEmpty()) {
            return;
        }

        var remove = new ClientboundPlayerInfoRemovePacket(java.util.List.of(player.getUUID()));
        var update = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(java.util.List.of(player));
        for (ServerPlayer recipient : player.getServer().getPlayerList().getPlayers()) {
            recipient.connection.send(remove);
            recipient.connection.send(update);
        }
        DirectAuth.LOGGER.info("Refreshed signed skin profile for {} ({})",
                player.getGameProfile().getName(), player.getUUID());
    }

    private void restorePersistentOrProcess(ServerPlayer player, UserData userData) {
        DirectAuth.getLoginManager().tryRestorePersistentSession(player, userData).thenAcceptAsync(restored -> {
            if (!player.connection.isAcceptingMessages()) return;
            if (restored) {
                DirectAuth.getLoginManager().setAuthenticated(player, true);
                DirectAuth.getLoginManager().markAuthenticationMethod(player,
                        LoginManager.AuthenticationMethod.SESSION);
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgSessionRestored));
                return;
            }
            processLogin(player, userData);
        }, player.getServer()).exceptionally(error -> {
            player.getServer().execute(() -> processLogin(player, userData));
            return null;
        });
    }

    private boolean requiresTotp(UserData userData) {
        return DirectAuth.getConfig().totpEnabled && userData != null && userData.isTotpEnabled();
    }

    // Método auxiliar para no duplicar código
    private void processLogin(ServerPlayer player, UserData userData) {
        boolean automaticPremiumLogin = DirectAuth.getLoginManager()
                .consumeAutomaticPremiumLogin(player.getUUID());
        boolean awaitingTotp = false;
        boolean isAuthenticated = false;

        if (automaticPremiumLogin) {
            boolean needsTotp = requiresTotp(userData);
            awaitingTotp = needsTotp;
            if (needsTotp) {
                DirectAuth.getLoginManager().beginTotp(player, userData,
                        LoginManager.AuthenticationMethod.PREMIUM);
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoLogin));
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpLoginRequired));
            } else {
                DirectAuth.getLoginManager().setAuthenticated(player, true);
                DirectAuth.getLoginManager().markAuthenticationMethod(player,
                        LoginManager.AuthenticationMethod.PREMIUM);
                isAuthenticated = true;
                boolean passwordNeedsSetup = userData != null
                        && LoginManager.passwordNeedsSetup(userData.getPasswordHash());
                player.sendSystemMessage(Component.literal(passwordNeedsSetup
                        ? DirectAuth.getConfig().getLang().msgPremiumAccountCreated
                        : DirectAuth.getConfig().getLang().msgAutoLogin));
                if (passwordNeedsSetup) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoPasswordSetup));
                }
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
                boolean needsTotp = requiresTotp(userData);
                if (needsTotp) {
                    awaitingTotp = true;
                    DirectAuth.getLoginManager().beginTotp(player, userData,
                        LoginManager.AuthenticationMethod.PREMIUM);
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoLogin));
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpLoginRequired));
                } else {
                    DirectAuth.getLoginManager().setAuthenticated(player, true);
                    DirectAuth.getLoginManager().markAuthenticationMethod(player,
                            LoginManager.AuthenticationMethod.PREMIUM);
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoLogin));
                    isAuthenticated = true;
                }
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
            if (!awaitingTotp && !requiresTotp(userData) && !premiumPasswordFallback
                    && DirectAuth.getLoginManager().tryRestoreSession(player)) {
                DirectAuth.getLoginManager().markAuthenticationMethod(player,
                        com.marcp.directauth.auth.LoginManager.AuthenticationMethod.SESSION);
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
