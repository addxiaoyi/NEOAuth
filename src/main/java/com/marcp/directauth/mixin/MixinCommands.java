package com.marcp.directauth.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import net.minecraft.commands.Commands;

@Mixin(Commands.class)
public abstract class MixinCommands {
    @ModifyVariable(method = "performCommand", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String directAuth$redactSensitiveCommand(String command) {
        return redact(command);
    }

    private static String redact(String command) {
        if (command == null || command.isBlank()) return command;
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return command;
        String root = parts[0].toLowerCase();
        if (!root.equals("login") && !root.equals("register") && !root.equals("setpassword")
                && !root.equals("changepassword") && !root.equals("unregister")) {
            return command;
        }
        return "/" + root + " <redacted>";
    }
}
