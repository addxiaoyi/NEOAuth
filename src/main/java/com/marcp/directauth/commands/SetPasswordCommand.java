package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public final class SetPasswordCommand {
    private SetPasswordCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("setpassword")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(SetPasswordCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }

        String username = player.getGameProfile().getName();
        String password = StringArgumentType.getString(context, "password");
        if (password.length() < DirectAuth.getConfig().minPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooShort));
            return 0;
        }
        if (password.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooLong));
            return 0;
        }

        DirectAuth.getDatabase().getUserAsync(username)
                .thenCompose(user -> {
                    if (user == null) return CompletableFuture.completedFuture(new SetupResult(null, false));
                    boolean needsSetup = LoginManager.passwordNeedsSetup(user.getPasswordHash());
                    if (!needsSetup) return CompletableFuture.completedFuture(new SetupResult(user, false));
                    return CompletableFuture.supplyAsync(() -> LoginManager.hashPassword(password))
                            .thenApply(hash -> {
                                user.setPasswordHash(hash);
                                return new SetupResult(user, true);
                            });
                })
                .thenAcceptAsync(result -> {
                    if (!player.connection.isAcceptingMessages() || result.userData() == null) return;
                    if (!result.updated()) {
                        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordSetupNotRequired));
                        return;
                    }
                    DirectAuth.getLoginManager().invalidateSession(player);
                    DirectAuth.getDatabase().updateUserAsync(username, result.userData());
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPasswordChanged));
                }, player.getServer());
        return 1;
    }

    private record SetupResult(UserData userData, boolean updated) {}
}
