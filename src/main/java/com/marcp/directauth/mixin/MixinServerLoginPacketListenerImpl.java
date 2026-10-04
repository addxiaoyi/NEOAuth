package com.marcp.directauth.mixin;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.auth.MojangAPI;
import com.marcp.directauth.data.MigrationManager;
import com.marcp.directauth.data.UserData;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginPacketListenerImpl {

    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Connection connection;
    @Shadow private byte[] challenge;
    @Shadow private ServerLoginPacketListenerImpl.State state;
    @Shadow private String requestedUsername;
    @Shadow public abstract void disconnect(Component reason);

    @Unique
    private boolean directAuth$isDataPreloaded;

    @Unique
    private volatile UUID directAuth$premiumUuid;

    @Unique
    private volatile UserData directAuth$loginData;
    @Unique
    private volatile GameProfile directAuth$cachedPremiumProfile;

    @Unique
    private volatile boolean directAuth$automaticPremiumProbe;

    @Unique
    private volatile boolean directAuth$automaticPremiumRegistrationStarted;

    @Unique
    private volatile boolean directAuth$isStartingVerifiedProfile;

    @Unique
    private volatile boolean directAuth$premiumFallbackStarted;

    @Unique
    private volatile long directAuth$premiumHandshakeStartedAt;

    @Unique
    private boolean directAuth$isStartingPremiumFallback;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void onHandleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (this.server.usesAuthentication()) return;

        String username = packet.name();
        if (DirectAuth.getDatabase() == null) return;

        if (!this.directAuth$isDataPreloaded) {
            ci.cancel();
            DirectAuth.getDatabase().getUserAsync(username).thenAcceptAsync(data -> {
                if (!this.connection.isConnected()) return;
                if (DirectAuth.getLoginManager() != null) {
                    DirectAuth.getLoginManager().addPreLoadedData(username, data);
                }
                this.directAuth$isDataPreloaded = true;
                this.handleHello(packet);
            }, this.server);
            return;
        }

        UserData data = null;
        if (DirectAuth.getLoginManager() != null) {
            data = DirectAuth.getLoginManager().getAndRemovePreLoadedData(username);
            if (data != null) DirectAuth.getLoginManager().addPreLoadedData(username, data);
        }
        if (data == null) {
            data = DirectAuth.getDatabase().getUser(username);
        }

        this.directAuth$loginData = data;
        if (data == null && (DirectAuth.getConfig() == null || !DirectAuth.getConfig().premiumAutoLogin)) return;

        UUID premiumUuid = null;
        if (data != null && data.isPremium() && data.getOnlineUUID() != null) {
            try {
                premiumUuid = UUID.fromString(data.getOnlineUUID());
                this.directAuth$cachedPremiumProfile = directAuth$profileWithCachedTextures(premiumUuid, username, data);
            } catch (IllegalArgumentException exception) {
                DirectAuth.LOGGER.error("Invalid stored premium UUID for {}", username, exception);
                return;
            }
        } else if (DirectAuth.getConfig() != null && DirectAuth.getConfig().premiumAutoLogin) {
            this.directAuth$automaticPremiumProbe = true;
        } else {
            return;
        }

        try {
            this.directAuth$premiumUuid = premiumUuid;
            this.requestedUsername = username;
            this.state = ServerLoginPacketListenerImpl.State.KEY;
            this.connection.send(new ClientboundHelloPacket(
                    "",
                    this.server.getKeyPair().getPublic().getEncoded(),
                    this.challenge,
                    true));
            this.directAuth$premiumHandshakeStartedAt = System.currentTimeMillis();
            ci.cancel();
        } catch (Exception exception) {
            DirectAuth.LOGGER.error("Premium handshake failed for {}", username, exception);
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void directAuth$clearLoginCache(DisconnectionDetails details, CallbackInfo ci) {
        if (DirectAuth.getLoginManager() != null) {
            DirectAuth.getLoginManager().clearPreLoadedData(this.requestedUsername);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void directAuth$watchPremiumVerification(CallbackInfo ci) {
        if ((this.directAuth$premiumUuid == null && !this.directAuth$automaticPremiumProbe)
                || this.directAuth$premiumFallbackStarted
                || DirectAuth.getConfig() == null
                || !DirectAuth.getConfig().premiumLoginFallbackOnFailure
                || this.directAuth$premiumHandshakeStartedAt <= 0) {
            return;
        }

        long timeoutMs = Math.max(1, DirectAuth.getConfig().premiumVerificationTimeoutSeconds) * 1000L;
        if (System.currentTimeMillis() - this.directAuth$premiumHandshakeStartedAt < timeoutMs) return;
        if (this.state != ServerLoginPacketListenerImpl.State.KEY
                && this.state != ServerLoginPacketListenerImpl.State.AUTHENTICATING) {
            return;
        }

        if (this.directAuth$startKnownPremiumFallback()) {
            DirectAuth.LOGGER.warn("Mojang verification timed out for {}; using known-premium automatic fallback", this.requestedUsername);
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    private void directAuth$allowPasswordFallback(Component reason, CallbackInfo ci) {
        UUID premiumUuid = this.directAuth$premiumUuid;
        if (!directAuth$isMojangVerificationFailure(reason)) return;

        if (premiumUuid == null && this.directAuth$automaticPremiumProbe) {
            this.directAuth$startOfflineFallback();
            ci.cancel();
            return;
        }

        if (premiumUuid == null
                || DirectAuth.getConfig() == null
                || !DirectAuth.getConfig().premiumLoginFallbackOnFailure) {
            return;
        }

        if (this.directAuth$startKnownPremiumFallback()) {
            ci.cancel();
        }
    }

    @Unique
    private boolean directAuth$startKnownPremiumFallback() {
        UUID premiumUuid = this.directAuth$premiumUuid;
        String username = this.requestedUsername;
        if (premiumUuid == null || username == null) return false;

        synchronized (this) {
            if (this.directAuth$premiumFallbackStarted) return true;
            this.directAuth$premiumFallbackStarted = true;
        }

        try {
            this.directAuth$isStartingPremiumFallback = true;
            if (DirectAuth.getLoginManager() == null) {
                throw new IllegalStateException("Login manager is not initialized");
            }
            // A previously verified premium identity is trusted during a temporary
            // Mojang outage: keep its UUID and enter without asking for a password.
            DirectAuth.getLoginManager().markAutomaticPremiumLogin(premiumUuid);
            GameProfile fallbackProfile = this.directAuth$cachedPremiumProfile != null
                    ? this.directAuth$cachedPremiumProfile
                    : new GameProfile(premiumUuid, username);
            this.directAuth$startClientVerification(fallbackProfile);
            DirectAuth.LOGGER.warn(
                    "Mojang verification failed for known premium player {}; retaining UUID {} for automatic login",
                    username, premiumUuid);
            return true;
        } catch (RuntimeException exception) {
            this.directAuth$premiumFallbackStarted = false;
            if (DirectAuth.getLoginManager() != null) {
                DirectAuth.getLoginManager().clearPremiumPasswordFallback(premiumUuid);
                DirectAuth.getLoginManager().clearAutomaticPremiumLogin(premiumUuid);
            }
            DirectAuth.LOGGER.error("Could not start password fallback for {}", username, exception);
            return false;
        } finally {
            this.directAuth$isStartingPremiumFallback = false;
        }
    }

    @Unique
    private void directAuth$startOfflineFallback() {
        if (this.directAuth$premiumFallbackStarted) return;
        String username = this.requestedUsername;
        if (username == null) return;

        this.directAuth$premiumFallbackStarted = true;
        try {
            this.directAuth$isStartingPremiumFallback = true;
            this.directAuth$startClientVerification(UUIDUtil.createOfflineProfile(username));
            DirectAuth.LOGGER.info("Premium auto-login unavailable for {}; using password login", username);
        } finally {
            this.directAuth$isStartingPremiumFallback = false;
        }
    }

    @Inject(method = "startClientVerification", at = @At("HEAD"), cancellable = true)
    private void directAuth$handleVerifiedProfile(GameProfile profile, CallbackInfo ci) {
        if (this.directAuth$premiumFallbackStarted && !this.directAuth$isStartingPremiumFallback) {
            ci.cancel();
            return;
        }

        if (!this.directAuth$isStartingVerifiedProfile
                && profile.getId() != null
                && !directAuth$hasSignedTextures(profile)) {
            // The session service can be temporarily unavailable. Reuse the last
            // signed profile before allowing an empty profile to reach the client.
            if (this.directAuth$cachedPremiumProfile != null
                    && directAuth$hasSignedTextures(this.directAuth$cachedPremiumProfile)
                    && profile.getId().equals(this.directAuth$cachedPremiumProfile.getId())) {
                ci.cancel();
                this.directAuth$isStartingVerifiedProfile = true;
                this.directAuth$startClientVerification(this.directAuth$cachedPremiumProfile);
                this.directAuth$isStartingVerifiedProfile = false;
                return;
            }
            try {
                GameProfile refreshed = MojangAPI.fetchSignedProfile(profile.getId(), profile.getName());
                if (refreshed != null && !refreshed.getProperties().isEmpty()) {
                    ci.cancel();
                    this.directAuth$isStartingVerifiedProfile = true;
                    this.directAuth$startClientVerification(refreshed);
                    this.directAuth$isStartingVerifiedProfile = false;
                    return;
                }
            } catch (RuntimeException exception) {
                DirectAuth.LOGGER.warn("Could not refresh Mojang skin properties for {}", profile.getName(), exception);
            }
        }

        if (this.directAuth$premiumUuid != null
                && !this.directAuth$premiumUuid.equals(profile.getId())
                && !this.directAuth$isStartingPremiumFallback) {
            ci.cancel();
            this.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgPremiumError));
            DirectAuth.LOGGER.warn("Premium UUID mismatch during LOGIN for {}: expected {}, received {}",
                    this.requestedUsername, this.directAuth$premiumUuid, profile.getId());
            return;
        }

        if (this.directAuth$premiumUuid != null) {
            String sourceUuid = UUIDUtil.createOfflineProfile(profile.getName()).getId().toString();
            if (!this.directAuth$isStartingVerifiedProfile
                    && !sourceUuid.equalsIgnoreCase(profile.getId().toString())) {
                ci.cancel();
                this.directAuth$isStartingVerifiedProfile = true;
                CompletableFuture.supplyAsync(
                                () -> MigrationManager.migratePlayerData(this.server, sourceUuid, profile.getId().toString()))
                        .thenAcceptAsync(migrated -> {
                            if (!migrated || !this.connection.isConnected()) {
                                if (this.connection.isConnected()) {
                                    this.disconnect(Component.literal(DirectAuth.getConfig().getLang().errStorageUnavailable));
                                }
                                this.directAuth$isStartingVerifiedProfile = false;
                                return;
                            }
                            this.directAuth$startClientVerification(profile);
                            this.directAuth$isStartingVerifiedProfile = false;
                        }, this.server).exceptionally(error -> {
                            this.server.execute(() -> {
                                if (this.connection.isConnected()) {
                                    DirectAuth.LOGGER.error("Premium data migration failed for {}", profile.getName(), error);
                                    this.disconnect(Component.literal(DirectAuth.getConfig().getLang().errStorageUnavailable));
                                }
                                this.directAuth$isStartingVerifiedProfile = false;
                            });
                            return null;
                        });
                return;
            }
            this.directAuth$cacheTextures(profile);
            if (this.directAuth$loginData != null && this.requestedUsername != null) {
                DirectAuth.getDatabase().updateUserAsync(this.requestedUsername, this.directAuth$loginData);
            }
            return;
        }

        if (this.directAuth$isStartingPremiumFallback || !this.directAuth$automaticPremiumProbe) return;
        if (this.directAuth$isStartingVerifiedProfile) return;
        if (this.directAuth$automaticPremiumRegistrationStarted) {
            ci.cancel();
            return;
        }

        String username = this.requestedUsername;
        if (username == null || !username.equalsIgnoreCase(profile.getName())) {
            ci.cancel();
            this.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgPremiumError));
            return;
        }

        if (DirectAuth.getConfig() == null || !DirectAuth.getConfig().premiumAutoRegister) {
            ci.cancel();
            this.directAuth$startOfflineFallback();
            return;
        }

        this.directAuth$automaticPremiumRegistrationStarted = true;
        this.directAuth$cacheTextures(profile);
        ci.cancel();
        this.directAuth$completeAutomaticPremiumRegistration(profile);
    }

    @Unique
    private void directAuth$cacheTextures(GameProfile profile) {
        if (this.directAuth$loginData == null) return;
        String[] textures = directAuth$texturePair(profile);
        if (textures != null) {
            this.directAuth$loginData.setTextures(textures[0], textures[1]);
        }
    }

    @Unique
    private static String[] directAuth$texturePair(GameProfile profile) {
        var properties = profile.getProperties().get("textures");
        if (properties == null || properties.isEmpty()) return null;
        Property textures = properties.iterator().next();
        if (!textures.hasSignature()) return null;
        return new String[] {textures.value(), textures.signature()};
    }

    @Unique
    private static boolean directAuth$hasSignedTextures(GameProfile profile) {
        return directAuth$texturePair(profile) != null;
    }

    @Unique
    private static GameProfile directAuth$profileWithCachedTextures(UUID uuid, String username, UserData data) {
        GameProfile profile = new GameProfile(uuid, username);
        if (data.getTexturesValue() != null && data.getTexturesSignature() != null) {
            profile.getProperties().put("textures", new Property("textures", data.getTexturesValue(), data.getTexturesSignature()));
        }
        return profile;
    }

    @Unique
    private void directAuth$completeAutomaticPremiumRegistration(GameProfile profile) {
        String username = this.requestedUsername;
        if (username == null) return;

        String sourceUuid = UUIDUtil.createOfflineProfile(username).getId().toString();
        String targetUuid = profile.getId().toString();
        boolean needsMigration = !sourceUuid.equalsIgnoreCase(targetUuid);
        CompletableFuture<Boolean> migration = needsMigration
                ? CompletableFuture.supplyAsync(() -> MigrationManager.migratePlayerData(this.server, sourceUuid, targetUuid))
                : CompletableFuture.completedFuture(true);

        migration.thenCompose(migrated -> {
            if (!migrated) return CompletableFuture.<UserData>completedFuture(null);
            String ip = this.directAuth$remoteAddress();
            String passwordHash = LoginManager.generateUnconfiguredPasswordHash();
            String[] profileTextures = directAuth$texturePair(profile);
            String texturesValue = profileTextures != null
                    ? profileTextures[0]
                    : this.directAuth$loginData == null ? null : this.directAuth$loginData.getTexturesValue();
            String texturesSignature = profileTextures != null
                    ? profileTextures[1]
                    : this.directAuth$loginData == null ? null : this.directAuth$loginData.getTexturesSignature();
            return DirectAuth.getDatabase().upsertPremiumIdentityAsync(
                    username, passwordHash, ip, targetUuid, texturesValue, texturesSignature);
        }).thenAcceptAsync(account -> {
            if (account == null || !this.connection.isConnected()) {
                if (this.connection.isConnected()) {
                    this.disconnect(Component.literal(DirectAuth.getConfig().getLang().errStorageUnavailable));
                }
                return;
            }

            this.directAuth$isStartingVerifiedProfile = true;
            this.directAuth$loginData = account;
            // The account is now premium. Let the final verified profile pass through
            // the normal login path instead of being treated as a second probe.
            this.directAuth$premiumUuid = profile.getId();
            this.directAuth$automaticPremiumProbe = false;
            this.directAuth$automaticPremiumRegistrationStarted = false;
            // Replace the pre-login null marker so PlayerLoggedInEvent sees the complete account.
            if (DirectAuth.getLoginManager() != null) {
                DirectAuth.getLoginManager().addPreLoadedData(username, account);
                DirectAuth.getLoginManager().markAutomaticPremiumLogin(profile.getId());
            }
            this.directAuth$startClientVerification(profile);
            this.directAuth$isStartingVerifiedProfile = false;
            DirectAuth.LOGGER.info("Automatic premium account created for {} with UUID {}", username, targetUuid);
        }, this.server).exceptionally(error -> {
            this.server.execute(() -> {
                if (this.connection.isConnected()) {
                    DirectAuth.LOGGER.error("Automatic premium registration failed for {}", username, error);
                    this.disconnect(Component.literal(DirectAuth.getConfig().getLang().errStorageUnavailable));
                }
            });
            return null;
        });
    }

    @Unique
    private String directAuth$remoteAddress() {
        if (this.connection.getRemoteAddress() instanceof InetSocketAddress address
                && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "";
    }

    @Unique
    private static boolean directAuth$isMojangVerificationFailure(Component reason) {
        if (!(reason.getContents() instanceof TranslatableContents translated)) return false;

        return switch (translated.getKey()) {
            case "multiplayer.disconnect.unverified_username",
                    "multiplayer.disconnect.authservers_down",
                    "multiplayer.disconnect.slow_login" -> true;
            default -> false;
        };
    }

    @Invoker("startClientVerification")
    protected abstract void directAuth$startClientVerification(GameProfile profile);

    @Shadow
    public abstract void handleHello(ServerboundHelloPacket packet);
}
