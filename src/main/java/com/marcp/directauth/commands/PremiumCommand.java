package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.auth.MojangAPI;
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.mixin.PlayerListAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class PremiumCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("online")
                .executes(context -> execute(context, null))
                .then(Commands.argument("password", StringArgumentType.string())
                        .executes(context -> execute(context, StringArgumentType.getString(context, "password")))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, String password) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }

        String username = player.getGameProfile().getName();
        if (password == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgOnlineModeWarning));
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumWarning));
            return 1;
        }

        DirectAuth.getDatabase().getUserAsync(username)
                .thenCompose(user -> {
                    if (user == null) return CompletableFuture.completedFuture(new Verification(user, false));
                    return LoginManager.checkPasswordAsync(password, user.getPasswordHash())
                            .thenApply(valid -> new Verification(user, valid));
                })
                .thenAcceptAsync(result -> continueVerification(player, username, result), player.getServer())
                .exceptionally(error -> {
                    player.getServer().execute(() -> {
                        if (player.connection.isAcceptingMessages()) {
                            DirectAuth.LOGGER.error("Premium command lookup failed for {}", username, error);
                            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errStorageUnavailable));
                        }
                    });
                    return null;
                });
        return 1;
    }

    private static void continueVerification(ServerPlayer player, String username, Verification verification) {
        if (!player.connection.isAcceptingMessages()) return;
        UserData userData = verification.userData();
        if (userData == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errUserNotFound));
            return;
        }
        if (userData.isPremium()) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAlreadyPremium));
            return;
        }
        if (!verification.passwordValid()) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errWrongPasswordSimple));
            return;
        }

        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgVerifying));
        MojangAPI.getOnlineUUID(username).thenAcceptAsync(uuid -> {
            if (!player.connection.isAcceptingMessages()) return;
            if (uuid == null) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errMojangNotFound));
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgMojangHint));
                return;
            }

            String formattedUUID = MojangAPI.formatUUID(uuid);
            if (formattedUUID == null) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errMojangNotFound));
                return;
            }

            ((PlayerListAccessor) player.getServer().getPlayerList()).callSave(player);
            CompletableFuture.completedFuture(true)
                    .thenAcceptAsync(ignored -> {
                        if (!player.connection.isAcceptingMessages()) return;
                        userData.setPremium(true);
                        userData.setOnlineUUID(formattedUUID);
                        DirectAuth.getDatabase().updateUserAsync(username, userData);
                        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumSuccess));
                        player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgPremiumKick));
                    }, player.getServer());
        }, player.getServer());
    }

    private record Verification(UserData userData, boolean passwordValid) {}
}
