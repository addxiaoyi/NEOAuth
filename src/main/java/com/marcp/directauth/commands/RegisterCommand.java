package com.marcp.directauth.commands;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class RegisterCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("register")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(RegisterCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }

        String username = player.getGameProfile().getName();
        String password = StringArgumentType.getString(context, "password");
        int requiredDelay = Math.max(0, DirectAuth.getConfig().registrationDelay);
        long secondsAlive = (System.currentTimeMillis()
                - DirectAuth.getLoginManager().getConnectionTime(player)) / 1000;

        if (secondsAlive < requiredDelay) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errRegistrationCooldown));
            return 0;
        }
        if (password.length() < DirectAuth.getConfig().minPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooShort));
            return 0;
        }
        if (password.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooLong));
            return 0;
        }

        String hash = LoginManager.hashPassword(password);
        String playerIp = player.getIpAddress();
        int maxAccountsPerIp = DirectAuth.getConfig().maxAccountsPerIP;

        CompletableFuture<RegistrationOutcome> registration = maxAccountsPerIp > 0
                ? DirectAuth.getDatabase().countAccountsByIPAsync(playerIp)
                        .thenCompose(count -> count >= maxAccountsPerIp
                                ? CompletableFuture.completedFuture(RegistrationOutcome.IP_LIMIT)
                                : DirectAuth.getDatabase().createUserIfAbsentAsync(username, hash, playerIp)
                                        .thenApply(created -> created
                                                ? RegistrationOutcome.CREATED
                                                : RegistrationOutcome.ALREADY_REGISTERED))
                : DirectAuth.getDatabase().createUserIfAbsentAsync(username, hash, playerIp)
                        .thenApply(created -> created
                                ? RegistrationOutcome.CREATED
                                : RegistrationOutcome.ALREADY_REGISTERED);

        registration.thenAcceptAsync(outcome -> {
            if (!player.connection.isAcceptingMessages()) return;
            if (outcome == RegistrationOutcome.IP_LIMIT) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errIpLimitReached));
                return;
            }
            if (outcome == RegistrationOutcome.ALREADY_REGISTERED) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyRegistered));
                return;
            }

            DirectAuth.getLoginManager().setAuthenticated(player, true);
            DirectAuth.getPositionManager().restorePosition(player);
            PlayerRestrictionHandler.removeAnchor(player);
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgRegistered));
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumEnableHint));
        }, player.getServer()).exceptionally(error -> {
            player.getServer().execute(() -> {
                if (player.connection.isAcceptingMessages()) {
                    DirectAuth.LOGGER.error("Registration failed for {}", username, error);
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errStorageUnavailable));
                }
            });
            return null;
        });
        return 1;
    }

    private enum RegistrationOutcome {
        CREATED,
        ALREADY_REGISTERED,
        IP_LIMIT
    }
}
