package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class ChangePasswordCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("changepassword")
                .then(Commands.argument("oldPassword", StringArgumentType.string())
                        .then(Commands.argument("newPassword", StringArgumentType.word())
                                .executes(ChangePasswordCommand::execute))));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }

        String username = player.getGameProfile().getName();
        String oldPassword = StringArgumentType.getString(context, "oldPassword");
        String newPassword = StringArgumentType.getString(context, "newPassword");
        if (newPassword.length() < DirectAuth.getConfig().minPasswordLength
                || newPassword.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooShort));
            return 0;
        }

        DirectAuth.getDatabase().getUserAsync(username)
                .thenCompose(user -> user == null
                        ? java.util.concurrent.CompletableFuture.completedFuture(new Verification(null, false))
                        : LoginManager.checkPasswordAsync(oldPassword, user.getPasswordHash())
                                .thenApply(valid -> new Verification(user, valid)))
                .thenAcceptAsync(result -> {
                    if (!player.connection.isAcceptingMessages()) return;
                    if (!result.passwordValid()) {
                        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errOldPasswordWrong));
                        return;
                    }
                    ConfirmationManager.requestConfirmation(player, () ->
                            java.util.concurrent.CompletableFuture.supplyAsync(() -> LoginManager.hashPassword(newPassword))
                                    .thenAcceptAsync(newHash -> {
                                        UserData updated = result.userData();
                                        if (updated == null || !player.connection.isAcceptingMessages()) return;
                                        updated.setPasswordHash(newHash);
                                        updated.setPremium(false);
                                        updated.setOnlineUUID(null);
                                        DirectAuth.getDatabase().updateUserAsync(username, updated);
                                        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPasswordChanged));
                                    }, player.getServer()));
                }, player.getServer());
        return 1;
    }

    private record Verification(UserData userData, boolean passwordValid) {}
}
