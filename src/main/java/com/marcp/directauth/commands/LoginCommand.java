package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.config.LangConfig;
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class LoginCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(LoginCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }
        if (DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyAuthenticated));
            return 0;
        }
        if (!DirectAuth.getLoginManager().canAttemptLogin(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errCooldown));
            return 0;
        }
        if (DirectAuth.getLoginManager().hasExceededMaxAttempts(player)) {
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().errMaxAttempts));
            return 0;
        }

        String username = player.getGameProfile().getName();
        String password = StringArgumentType.getString(context, "password");
        CompletableFuture<LoginResult> login = DirectAuth.getDatabase().getUserAsync(username)
                .thenCompose(user -> {
                    if (user == null) return CompletableFuture.completedFuture(new LoginResult(null, false));
                    return LoginManager.checkPasswordAsync(password, user.getPasswordHash())
                            .thenApply(valid -> new LoginResult(user, valid));
                });

        login.thenAcceptAsync(result -> finishLogin(player, result), player.getServer())
                .exceptionally(error -> {
                    player.getServer().execute(() -> {
                        if (player.connection.isAcceptingMessages()) {
                            DirectAuth.LOGGER.error("Login lookup failed for {}", username, error);
                            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errStorageUnavailable));
                        }
                    });
                    return null;
                });
        return 1;
    }

    private static void finishLogin(ServerPlayer player, LoginResult result) {
        if (!player.connection.isAcceptingMessages() || DirectAuth.getLoginManager().isAuthenticated(player)) return;
        if (result.userData() == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotRegistered));
            return;
        }
        if (!result.passwordValid()) {
            DirectAuth.getLoginManager().recordLoginAttempt(player, false);
            int attempts = DirectAuth.getLoginManager().getFailedAttempts(player);
            player.sendSystemMessage(Component.literal(LangConfig.format(
                    DirectAuth.getConfig().getLang().errWrongPassword,
                    attempts, DirectAuth.getConfig().maxLoginAttempts)));
            return;
        }

        DirectAuth.getLoginManager().recordLoginAttempt(player, true);
        if (DirectAuth.getConfig().totpEnabled && result.userData().isTotpEnabled()) {
            DirectAuth.getLoginManager().beginTotp(player, result.userData());
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgTotpLoginRequired));
            return;
        }
        completeAuthenticated(player);
    }

    public static void completeAuthenticated(ServerPlayer player) {
        if (!player.connection.isAcceptingMessages()) return;
        DirectAuth.getLoginManager().setAuthenticated(player, true);
        restorePlayer(player);
        PlayerRestrictionHandler.removeAnchor(player);
        PlayerRestrictionHandler.resyncEffectsToClient(player);
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAuthenticated));
    }

    private static void restorePlayer(ServerPlayer player) {
        if (DirectAuth.getPositionManager().restorePosition(player)) return;

        BlockPos respawnPos = player.getRespawnPosition();
        ResourceKey<Level> respawnDim = player.getRespawnDimension();
        if (respawnPos == null) return;

        ServerLevel level = player.getServer().getLevel(respawnDim);
        if (level == null) return;

        BlockState state = level.getBlockState(respawnPos);
        Block block = state.getBlock();
        Optional<Vec3> safePos = Optional.empty();
        if (block instanceof BedBlock) {
            safePos = BedBlock.findStandUpPosition(EntityType.PLAYER, level, respawnPos,
                    state.getValue(BedBlock.FACING), player.getRespawnAngle());
        } else if (block instanceof RespawnAnchorBlock) {
            safePos = RespawnAnchorBlock.findStandUpPosition(EntityType.PLAYER, level, respawnPos);
        } else if (player.isRespawnForced()) {
            safePos = Optional.of(Vec3.atBottomCenterOf(respawnPos));
        }
        safePos.ifPresent(pos -> player.teleportTo(level, pos.x, pos.y, pos.z, player.getRespawnAngle(), 0.0F));
    }

    private record LoginResult(UserData userData, boolean passwordValid) {}
}
