package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.config.LangConfig;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DirectAuthCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("directauth");

        // --- Subcomando: CONFIRM (Para todos los usuarios) ---
        root.then(Commands.literal("confirm")
            .executes(context -> {
                if (context.getSource().getEntity() instanceof ServerPlayer player) {
                    ConfirmationManager.confirm(player);
                    return 1;
                }
                return 0;
            })
        );

        // --- Subcomandos: ADMIN (Requieren OP nivel 4) ---

        // 1. Online Mode Toggle (Antiguo PremiumAdmin)
        root.then(Commands.literal("online")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> usage(ctx, DirectAuth.getConfig().getLang().errAdminUsage))
            .then(Commands.argument("user", StringArgumentType.string())
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(DirectAuthCommand::setOnlineMode)
                )
            )
        );

        // 2. Reset Password (Admin)
        root.then(Commands.literal("resetpass")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> usage(ctx, DirectAuth.getConfig().getLang().errAdminUsageReset))
            .then(Commands.argument("user", StringArgumentType.string())
                .then(Commands.argument("newPassword", StringArgumentType.word())
                    .executes(DirectAuthCommand::resetPassword)
                )
            )
        );

        // 3. Force Unregister (Admin)
        root.then(Commands.literal("unregister")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> usage(ctx, DirectAuth.getConfig().getLang().errAdminUsageUnregister))
            .then(Commands.argument("user", StringArgumentType.string())
                .executes(DirectAuthCommand::forceUnregister)
            )
        );

        // 4. Reload config & language files from disk (Admin)
        root.then(Commands.literal("reload")
            .requires(s -> s.hasPermission(4))
            .executes(DirectAuthCommand::reloadConfig)
        );

        // 5. Reset language files to the mod's built-in defaults (Admin)
        root.then(Commands.literal("resetlang")
            .requires(s -> s.hasPermission(4))
            .executes(DirectAuthCommand::resetLang)
        );

        dispatcher.register(root);
    }

    /** Sends a localized usage hint when an admin subcommand is called without its arguments. */
    private static int usage(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        Path configPath = context.getSource().getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig").resolve("DirectAuth-config.json");
        DirectAuth.initConfig(configPath);

        // Reaplicar el intervalo de limpieza de sesiones (init() es idempotente: reprograma la tarea)
        DirectAuth.getLoginManager().init(DirectAuth.getConfig().sessionCleanupInterval);

        context.getSource().sendSuccess(() -> Component.literal(
            DirectAuth.getConfig().getLang().msgConfigReloaded
        ), true);
        return 1;
    }

    private static int resetLang(CommandContext<CommandSourceStack> context) {
        Path serverConfig = context.getSource().getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig");

        // Deleting the language files forces LangConfig.load() to regenerate them from the
        // mod's built-in defaults (this discards any manual customizations in those files).
        try {
            Files.deleteIfExists(serverConfig.resolve("DirectAuth-lang-en.json"));
            Files.deleteIfExists(serverConfig.resolve("DirectAuth-lang-zh.json"));
            Files.deleteIfExists(serverConfig.resolve("DirectAuth-lang-es.json"));
        } catch (IOException e) {
            DirectAuth.LOGGER.error("DirectAuth: Failed to delete language files: {}", e.getMessage());
        }

        DirectAuth.initConfig(serverConfig.resolve("DirectAuth-config.json"));

        context.getSource().sendSuccess(() -> Component.literal(
            DirectAuth.getConfig().getLang().msgLangReset
        ), true);
        return 1;
    }

    private static int setOnlineMode(CommandContext<CommandSourceStack> context) {
        String username = StringArgumentType.getString(context, "user");
        boolean newValue = BoolArgumentType.getBool(context, "value");
        
        UserData userData = DirectAuth.getDatabase().getUser(username);
        if (userData == null) {
            context.getSource().sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
            return 0;
        }

        userData.setPremium(newValue);
        if (!newValue) userData.setOnlineUUID(null); // Limpiar UUID si se desactiva
        
        DirectAuth.getDatabase().updateUser(username, userData);
        context.getSource().sendSuccess(() -> Component.literal(
            LangConfig.format(DirectAuth.getConfig().getLang().msgAdminPremiumUpdated, username, newValue)
        ), true);
        return 1;
    }

    private static int resetPassword(CommandContext<CommandSourceStack> context) {
        String username = StringArgumentType.getString(context, "user");
        String newPass = StringArgumentType.getString(context, "newPassword");
        
        UserData userData = DirectAuth.getDatabase().getUser(username);
        if (userData == null) {
            context.getSource().sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
            return 0;
        }

        userData.setPasswordHash(LoginManager.hashPassword(newPass));
        DirectAuth.getDatabase().updateUser(username, userData);
        
        context.getSource().sendSuccess(() -> Component.literal(
            LangConfig.format(DirectAuth.getConfig().getLang().msgAdminResetSuccess, username)
        ), true);
        return 1;
    }

    private static int forceUnregister(CommandContext<CommandSourceStack> context) {
        String username = StringArgumentType.getString(context, "user");
        
        if (!DirectAuth.getDatabase().userExists(username)) {
            context.getSource().sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
            return 0;
        }

        DirectAuth.getDatabase().deleteUser(username);
        
        // Si el jugador está online, lo echamos
        ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayerByName(username);
        if (player != null) {
            DirectAuth.getLoginManager().removePlayer(player);
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgAccountDeleted));
        }

        context.getSource().sendSuccess(() -> Component.literal(
            LangConfig.format(DirectAuth.getConfig().getLang().msgAdminUnregisterSuccess, username)
        ), true);
        return 1;
    }
}
