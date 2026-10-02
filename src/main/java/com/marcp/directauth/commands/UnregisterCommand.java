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

public class UnregisterCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("unregister")
                .then(Commands.argument("password", StringArgumentType.string())
                        .executes(UnregisterCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        String username = player.getGameProfile().getName();
        String password = StringArgumentType.getString(context, "password");

        DirectAuth.getDatabase().getUserAsync(username)
                .thenCompose(user -> user == null
                        ? java.util.concurrent.CompletableFuture.completedFuture(new Verification(null, false))
                        : LoginManager.checkPasswordAsync(password, user.getPasswordHash())
                                .thenApply(valid -> new Verification(user, valid)))
                .thenAcceptAsync(result -> {
                    if (!player.connection.isAcceptingMessages()) return;
                    if (result.userData() == null || !result.passwordValid()) {
                        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errWrongPasswordSimple));
                        return;
                    }
                    ConfirmationManager.requestConfirmation(player, () ->
                            DirectAuth.getDatabase().deleteUserAsync(username)
                                    .thenAcceptAsync(deleted -> {
                                        if (!deleted || !player.connection.isAcceptingMessages()) return;
                                        DirectAuth.getLoginManager().removePlayer(player);
                                        player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgAccountDeleted));
                                    }, player.getServer()));
                }, player.getServer());
        return 1;
    }

    private record Verification(UserData userData, boolean passwordValid) {}
}
