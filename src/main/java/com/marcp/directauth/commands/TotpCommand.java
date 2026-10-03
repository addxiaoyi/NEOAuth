package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.auth.TotpEngine;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.CompletableFuture;

public final class TotpCommand {
    private TotpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("totp")
                .then(Commands.literal("setup").executes(TotpCommand::setup))
                .then(Commands.literal("verify").then(Commands.argument("code", StringArgumentType.word())
                        .executes(TotpCommand::verify)))
                .then(Commands.literal("recovery").then(Commands.argument("code", StringArgumentType.word())
                        .executes(TotpCommand::verify)))
                .then(Commands.literal("disable").then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(TotpCommand::disable))));
    }

    private static int setup(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        if (!DirectAuth.getConfig().totpEnabled) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpFeatureDisabled));
            return 0;
        }
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }
        String username = player.getGameProfile().getName();
        DirectAuth.getDatabase().getUserAsync(username).thenCompose(user -> {
            if (user == null) return CompletableFuture.completedFuture(null);
            if (user.getTotpSecret() == null || user.getTotpSecret().isBlank()) {
                user.setTotp(TotpEngine.generateSecret(), false);
                return DirectAuth.getDatabase().updateUserAsync(username, user).thenApply(ignored -> user);
            }
            return CompletableFuture.completedFuture(user);
        }).thenAcceptAsync(user -> {
            if (user == null || !player.connection.isAcceptingMessages()) return;
            if (user.isTotpEnabled()) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpEnabled));
                return;
            }
            String uri = TotpEngine.otpauthUri(DirectAuth.getConfig().totpIssuer, username, user.getTotpSecret());
            if (user.getTotpRecoveryCodes() == null || user.getTotpRecoveryCodes().isBlank()) {
                java.util.List<String> codes = TotpEngine.generateRecoveryCodes();
                user.setTotpRecoveryCodes(TotpEngine.hashRecoveryCodes(codes));
                DirectAuth.getDatabase().updateUserAsync(username, user);
                player.sendSystemMessage(Component.literal(String.format(
                        DirectAuth.getConfig().getLang().msgTotpRecoveryCodes, String.join(" ", codes))));
            }
            player.sendSystemMessage(Component.literal(String.format(DirectAuth.getConfig().getLang().msgTotpSetupSecret, uri)));
        }, player.getServer());
        return 1;
    }

    private static int verify(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        if (!DirectAuth.getConfig().totpEnabled) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpFeatureDisabled));
            return 0;
        }
        String code = StringArgumentType.getString(context, "code");
        String username = player.getGameProfile().getName();
        DirectAuth.getDatabase().getUserAsync(username).thenAcceptAsync(user -> {
            boolean valid = user != null && user.getTotpSecret() != null
                    && TotpEngine.verify(user.getTotpSecret(), code,
                    DirectAuth.getConfig().totpWindowSize, DirectAuth.getConfig().totpTimeStepSeconds);
            if (!valid && user != null) {
                String remainingCodes = TotpEngine.consumeRecoveryCodeAndGetRemaining(
                        user.getTotpRecoveryCodes(), code);
                if (remainingCodes != null) {
                    user.setTotpRecoveryCodes(remainingCodes.isBlank() ? null : remainingCodes);
                    DirectAuth.getDatabase().updateUserAsync(username, user);
                    valid = true;
                }
            }
            if (!valid) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpInvalid));
                return;
            }
            if (DirectAuth.getLoginManager().isAwaitingTotp(player)) {
                DirectAuth.getLoginManager().consumeTotp(player);
                LoginManager.AuthenticationMethod method = DirectAuth.getLoginManager().consumeTotpMethod(player);
                DirectAuth.getLoginManager().markAuthenticationMethod(player,
                        method == null ? LoginManager.AuthenticationMethod.PASSWORD : method);
                LoginCommand.completeAuthenticated(player);
                return;
            }
            user.setTotp(user.getTotpSecret(), true);
            DirectAuth.getDatabase().updateUserAsync(username, user);
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpEnabled));
        }, player.getServer());
        return 1;
    }

    private static int disable(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        if (!DirectAuth.getConfig().totpEnabled) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpFeatureDisabled));
            return 0;
        }
        String username = player.getGameProfile().getName();
        String password = StringArgumentType.getString(context, "password");
        DirectAuth.getDatabase().getUserAsync(username).thenCompose(user -> user == null
                ? CompletableFuture.completedFuture(new Verification(null, false))
                : LoginManager.checkPasswordAsync(password, user.getPasswordHash())
                        .thenApply(valid -> new Verification(user, valid)))
                .thenAcceptAsync(result -> {
                    if (result.user() == null || !result.passwordValid()) {
                        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errWrongPasswordSimple));
                        return;
                    }
                    result.user().setTotp(null, false);
                    DirectAuth.getDatabase().updateUserAsync(username, result.user());
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpDisabled));
                }, player.getServer());
        return 1;
    }

    private record Verification(UserData user, boolean passwordValid) {}
}
