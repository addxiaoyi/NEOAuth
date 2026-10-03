package com.marcp.directauth.api;

import com.marcp.directauth.auth.LoginManager;
import net.minecraft.server.level.ServerPlayer;

/** Stable read-only integration API for other server-side mods. */
public final class NeoauthApi {
    private NeoauthApi() {}

    public static boolean isAuthenticated(ServerPlayer player) {
        return com.marcp.directauth.DirectAuth.getLoginManager().isAuthenticated(player);
    }

    public static LoginManager.AuthenticationMethod getAuthenticationMethod(ServerPlayer player) {
        return com.marcp.directauth.DirectAuth.getLoginManager().getAuthenticationMethod(player);
    }

    public static boolean isPremiumVerified(ServerPlayer player) {
        LoginManager.AuthenticationMethod method = getAuthenticationMethod(player);
        return method == LoginManager.AuthenticationMethod.PREMIUM
                || method == LoginManager.AuthenticationMethod.PREMIUM_OUTAGE_FALLBACK;
    }

    public static boolean isAwaitingTotp(ServerPlayer player) {
        return com.marcp.directauth.DirectAuth.getLoginManager().isAwaitingTotp(player);
    }
}
