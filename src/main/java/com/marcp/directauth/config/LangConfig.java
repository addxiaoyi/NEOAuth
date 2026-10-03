package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.file.*;
import java.util.Map;

public class LangConfig {
    // --- General Messages ---
    public String msgWelcome = "§eWelcome! Use §a/register <password>§e to create your account.";
    public String msgLoginRequest = "§eUse §a/login <password>§e to authenticate.";
    public String msgAuthReminder = "§cYou must authenticate: /register <password> or /login <password>";
    public String msgAuthCountdown = "§cAuthentication required §7(%d seconds remaining)";
    public String msgAuthBossbar = "§cAuthenticate to play · %d seconds";
    public String errNotPlayer = "Only players can use this command.";
    
    // --- Registration Messages ---
    public String errAlreadyRegistered = "§cYou already have an account. Use §e/login <password>";
    public String errPasswordTooShort = "§cPassword must be at least 4 characters long.";
    public String errPasswordTooLong = "§cPassword cannot be longer than 32 characters.";
    public String msgRegistered = "§a✓ Account registered successfully.";
    public String msgPremiumEnableHint = "§7If you are using a legitimate Minecraft account, DirectAuth will verify it automatically on your next login.";
    public String errRegistrationCooldown = "§cPlease wait a moment before registering.";
    public String errIpLimitReached = "§cRegistration limit reached for this IP address.";
    public String errStorageUnavailable = "§cAuthentication storage is temporarily unavailable. Please try again.";
    
    // --- Login Messages ---
    public String errNotRegistered = "§cYou do not have an account. Use §e/register <password>";
    public String errAlreadyAuthenticated = "§eYou are already authenticated.";
    public String errCooldown = "§cPlease wait a few seconds before trying again.";
    public String errMaxAttempts = "§cToo many failed attempts.";
    public String msgAuthenticated = "§a✓ Authenticated successfully.";
    public String errWrongPassword = "§cIncorrect password (%d/%d attempts).";
    // Used where there is no attempt tracking (e.g. /unregister, /online), so it omits the counter.
    public String errWrongPasswordSimple = "§cIncorrect password.";
    public String msgTimeout = "§cLogin timed out.\n§7Please authenticate faster next time.";
    
    // --- Online Mode (formerly Premium) Messages ---
    public String msgPremiumHint = "§7Legitimate accounts are detected automatically; no extra command is required.";
    public String msgAutoLogin = "§a✓ Automatically authenticated (Online Mode).";
    public String msgAutoPasswordSetup = "§eYour premium account was created automatically. Set a fallback password with §a/setpassword <password>§e if Mojang is unavailable later.";
    public String msgTotpSetupSecret = "§eTOTP secret: §f%s§e\nAdd it to your authenticator app, then run §a/totp verify <code>§e.";
    public String msgTotpLoginRequired = "§eEnter your authenticator code with §a/totp verify <code>§e.";
    public String msgTotpInvalid = "§cInvalid or expired authenticator code.";
    public String msgTotpEnabled = "§a✓ Two-factor authentication enabled.";
    public String msgTotpDisabled = "§eTwo-factor authentication disabled.";
    public String msgTotpFeatureDisabled = "§eTwo-factor authentication is disabled by the server administrator.";
    public String msgPremiumAccountCreated = "§a✓ Premium account created automatically. You were logged in without a password.";
    public String errPasswordSetupNotRequired = "§eYour account already has a password. Use §a/changepassword§e to change it.";
    public String msgPremiumFallbackLogin = "§eMojang is temporarily unavailable. Your previously verified premium identity was accepted automatically.";
    public String msgPremiumError = "§cAuthentication Error\n§7This account is registered in Online Mode,\n§7but your UUID does not match.\n§7If you own this account, contact an administrator.";
    public String errNotAuthenticated = "§cYou must authenticate first.";
    public String errUserNotFound = "§cError: Account not found.";
    public String msgAlreadyPremium = "§eYour account is already set to Online Mode.";
    public String msgVerifying = "§eVerifying account with Mojang...";
    public String errMojangNotFound = "§cNo paid Minecraft account found with this username.";
    public String msgMojangHint = "§7Ensure you are using a legitimate Minecraft account.";
    public String errUUIDMismatch = "§cYour UUID does not match the Mojang account.";
    public String msgSessionHint = "§7You are using an offline session.";
    public String msgPremiumSuccess = "§a✓ Account verified as Online Mode.";
    public String msgPremiumKick = "§aAccount verified!\n§ePlease rejoin to apply changes.";
    public String msgAutoLoginHint = "§7Auto-login is now enabled for this account.";
    public String msgOnlineModeWarning = "§6WARNING! §eEnabling Online Mode will migrate your player data (e.g., inventory, stats, advancements). While DirectAuth tries to migrate data from other mods, there is a small risk of losing mod-specific data if not configured correctly. Please ensure your server owner has configured all mod data folders in directauth-config.json before proceeding, or make a backup.";

    // --- Admin Messages ---
    public String msgPremiumWarning = "§cWARNING! §7You are about to enable Online Mode.\n§7If you do not own this account, §cyou will lose access.\n§7Type §b/online <your_password> §7to confirm.";
    public String msgAdminPremiumUpdated = "§aOnline Mode status updated for %s: %s";
    public String errAdminUserNotFound = "§cUser %s does not exist in the database.";
    public String errAdminUsage = "§cUsage: /directauth online <user> <true|false>";
    public String errAdminUsageReset = "§cUsage: /directauth resetpass <user> <newPassword>";
    public String errAdminUsageUnregister = "§cUsage: /directauth unregister <user>";
    public String msgConfigReloaded = "§a✓ DirectAuth configuration reloaded.";
    public String msgLangReset = "§a✓ DirectAuth built-in (English/Chinese/Spanish) language files reset to defaults.";

    // --- Restriction Messages ---
    public String msgNoDrop = "§cYou cannot drop items before authenticating.";
    public String msgUseCommands = "§cPlease use commands to authenticate first.";
    
    // --- Account Management & Confirmation ---
    public String msgConfirmRequest = "§e⚠️ Confirmation Required!\n§7You are about to perform a sensitive action.\n§7Type §6/directauth confirm§7 to proceed.";
    public String msgPasswordChanged = "§a✓ Password updated successfully.";
    public String msgAccountDeleted = "§cYour account has been deleted.";
    public String errOldPasswordWrong = "§cThe old password is incorrect.";
    public String errNoPendingAction = "§cYou have no pending action to confirm.";
    public String msgActionExpired = "§cThe confirmation request has expired.";
    
    // --- Session Messages ---
    public String msgSessionRestored = "§aWelcome back. Session restored automatically.";
    public String msgLogoutSuccess = "§cLogged out successfully.";

    // --- Admin Management ---
    public String msgAdminResetSuccess = "§aPassword reset for user %s.";
    public String msgAdminUnregisterSuccess = "§aUser %s has been removed from the database.";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Formats a (possibly admin-edited) message template, falling back to the raw template
     * if it contains an invalid format specifier (e.g. a stray '%'). This prevents a typo
     * in a language file from throwing an exception in the middle of a command.
     */
    public static String format(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (java.util.IllegalFormatException e) {
            com.marcp.directauth.DirectAuth.LOGGER.warn(
                "DirectAuth: malformed format string in language file: {}", template);
            return template;
        }
    }

    public static LangConfig load(Path langPath, String language) {
        if (Files.exists(langPath)) {
            try (Reader reader = Files.newBufferedReader(langPath)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                JsonObject fileJson = (parsed != null && parsed.isJsonObject())
                        ? parsed.getAsJsonObject() : new JsonObject();

                // Start from the correct defaults for this language, then overlay the on-disk
                // values. This guarantees keys added in a mod update appear in the right
                // language instead of falling back to the English field defaults.
                LangConfig base = new LangConfig();
                base.setDefaults(language);
                JsonObject merged = GSON.toJsonTree(base).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : fileJson.entrySet()) {
                    merged.add(entry.getKey(), entry.getValue());
                }

                LangConfig config = GSON.fromJson(merged, LangConfig.class);
                config.save(langPath); // persist any newly added keys
                return config;
            } catch (Exception e) {
                System.err.println("DirectAuth: Error loading lang config: " + e.getMessage());
                LangConfig config = new LangConfig();
                config.setDefaults(language);
                return config;
            }
        } else {
            LangConfig config = new LangConfig();
            config.setDefaults(language);
            config.save(langPath);
            return config;
        }
    }

    public void setDefaults(String language) {
        if ("zh".equalsIgnoreCase(language) || "zh_cn".equalsIgnoreCase(language)
                || "zh-cn".equalsIgnoreCase(language)) {
            applyChineseDefaults();
            return;
        }

        if ("es".equalsIgnoreCase(language)) {
            // --- General Messages ---
            msgWelcome = "§e¡Bienvenido! Usa §a/register <contraseña>§e para crear tu cuenta.";
            msgLoginRequest = "§eUsa §a/login <contraseña>§e para autenticarte.";
            msgAuthReminder = "§cDebes autenticarte: /register <contraseña> o /login <contraseña>";
            msgAuthCountdown = "§cDebes autenticarte §7(%d segundos restantes)";
            msgAuthBossbar = "§cAutentícate para jugar · %d segundos";
            errNotPlayer = "Solo los jugadores pueden usar este comando.";

            // --- Registration Messages ---
            errAlreadyRegistered = "§cYa tienes una cuenta. Usa §e/login <contraseña>";
            errPasswordTooShort = "§cLa contraseña debe tener al menos 4 caracteres.";
            errPasswordTooLong = "§cLa contraseña no puede tener más de 32 caracteres.";
            msgRegistered = "§a✓ Cuenta registrada exitosamente.";
            msgPremiumEnableHint = "§7Si usas una cuenta original de Minecraft, DirectAuth la verificará automáticamente en tu próximo acceso.";
            errRegistrationCooldown = "§cPor favor, espera un momento antes de registrarte.";
            errIpLimitReached = "§cSe ha alcanzado el límite de registros para esta dirección IP.";
            errStorageUnavailable = "§cEl almacenamiento de autenticación no está disponible temporalmente. Inténtalo de nuevo.";

            // --- Login Messages ---
            errNotRegistered = "§cNo tienes una cuenta. Usa §e/register <contraseña>";
            errAlreadyAuthenticated = "§eYa estás autenticado.";
            errCooldown = "§cPor favor espera unos segundos antes de intentar de nuevo.";
            errMaxAttempts = "§cDemasiados intentos fallidos.";
            msgAuthenticated = "§a✓ Autenticado exitosamente.";
            errWrongPassword = "§cContraseña incorrecta (%d/%d intentos).";
            errWrongPasswordSimple = "§cContraseña incorrecta.";
            msgTimeout = "§cTiempo de espera agotado.\n§7Por favor autentícate más rápido la próxima vez.";

            // --- Online Mode (formerly Premium) Messages ---
            msgPremiumHint = "§7Las cuentas originales se detectan automáticamente; no necesitas ejecutar ningún comando.";
            msgAutoLogin = "§a✓ Autenticado automáticamente (Modo Online).";
            msgAutoPasswordSetup = "§eTu cuenta original se creó automáticamente. Usa §a/setpassword <contraseña>§e para configurar una contraseña de respaldo si Mojang no está disponible.";
            msgTotpSetupSecret = "§eSecreto TOTP: §f%s§e\nAñádelo a tu aplicación autenticadora y usa §a/totp verify <código>§e.";
            msgTotpLoginRequired = "§eIntroduce tu código autenticador con §a/totp verify <código>§e.";
            msgTotpInvalid = "§cEl código autenticador no es válido o ha caducado.";
            msgTotpEnabled = "§a✓ Autenticación de dos factores activada.";
            msgTotpDisabled = "§eAutenticación de dos factores desactivada.";
            msgTotpFeatureDisabled = "§eLa autenticación de dos factores está desactivada por el administrador.";
            msgPremiumAccountCreated = "§a✓ Cuenta original creada automáticamente. Has entrado sin contraseña.";
            errPasswordSetupNotRequired = "§eTu cuenta ya tiene una contraseña. Usa §a/changepassword§e para cambiarla.";
            msgPremiumFallbackLogin = "§eMojang no está disponible temporalmente. Tu identidad premium verificada anteriormente fue aceptada automáticamente.";
            msgPremiumError = "§cError de Autenticación\n§7Esta cuenta está en Modo Online,\n§7pero tu UUID no coincide.\n§7Si eres el dueño, contacta a un administrador.";
            errNotAuthenticated = "§cDebes autenticarte primero.";
            errUserNotFound = "§cError: Cuenta no encontrada.";
            msgAlreadyPremium = "§eTu cuenta ya está en Modo Online.";
            msgVerifying = "§eVerificando cuenta con Mojang...";
            errMojangNotFound = "§cNo se encontró una cuenta de Minecraft pagada con este nombre.";
            msgMojangHint = "§7Asegúrate de estar usando una cuenta legítima de Minecraft.";
            errUUIDMismatch = "§cTu UUID no coincide con la cuenta de Mojang.";
            msgSessionHint = "§7Estás usando una sesión offline.";
            msgPremiumSuccess = "§a✓ Cuenta verificada como Modo Online.";
            msgPremiumKick = "§a¡Cuenta verificada!\n§ePor favor, vuelve a entrar para aplicar los cambios.";
            msgAutoLoginHint = "§7El auto-login está activado para esta cuenta.";
            msgOnlineModeWarning = "§6¡ADVERTENCIA! §eActivar el Modo Online migrará tus datos de jugador (ej. inventario, estadísticas, avances). Aunque DirectAuth intenta migrar datos de otros mods, existe un pequeño riesgo de perder datos específicos de mods si no se configura correctamente. Asegúrate de que el dueño de tu servidor haya configurado todas las carpetas de datos de mods en directauth-config.json antes de continuar, o haz una copia de seguridad.";

            // --- Admin Messages ---
            msgPremiumWarning = "§c¡ADVERTENCIA! §7Estás a punto de activar el Modo Online.\n§7Si no eres dueño de esta cuenta, §cperderás el acceso.\n§7Escribe §b/online <tu_contraseña> §7para confirmar.";
            msgAdminPremiumUpdated = "§aEstado de Modo Online actualizado para %s: %s";
            errAdminUserNotFound = "§cEl usuario %s no existe en la base de datos.";
            errAdminUsage = "§cUso: /directauth online <usuario> <true|false>";
            errAdminUsageReset = "§cUso: /directauth resetpass <usuario> <nueva_contraseña>";
            errAdminUsageUnregister = "§cUso: /directauth unregister <usuario>";
            msgConfigReloaded = "§a✓ Configuración de DirectAuth recargada.";
            msgLangReset = "§a✓ Archivos de idioma integrados de DirectAuth (Inglés/Chino/Español) restablecidos a los valores por defecto.";

            // --- Restriction Messages ---
            msgNoDrop = "§cNo puedes soltar objetos antes de autenticarte.";
            msgUseCommands = "§cPor favor usa los comandos para autenticarte primero.";
            // --- Nuevas Traducciones ---
            msgConfirmRequest = "§e⚠️ ¡Confirmación Requerida!\n§7Estás a punto de realizar una acción delicada.\n§7Escribe §6/directauth confirm§7 para proceder.";
            msgPasswordChanged = "§a✓ Contraseña actualizada correctamente.";
            msgAccountDeleted = "§cTu cuenta ha sido eliminada.";
            errOldPasswordWrong = "§cLa contraseña antigua es incorrecta.";
            errNoPendingAction = "§cNo tienes ninguna acción pendiente de confirmar.";
            msgActionExpired = "§cLa solicitud de confirmación ha caducado.";
            
            msgAdminResetSuccess = "§aContraseña restablecida para el usuario %s.";
            msgAdminUnregisterSuccess = "§aEl usuario %s ha sido eliminado de la base de datos.";
            
            // --- Session Messages ---
            msgSessionRestored = "§aBienvenido de nuevo. Sesión restaurada automáticamente.";
            msgLogoutSuccess = "§cHas cerrado sesión correctamente.";
        }
    }

    private void applyChineseDefaults() {
        msgWelcome = "§e欢迎！请输入 §a/register <密码>§e 注册账号。";
        msgLoginRequest = "§e请输入 §a/login <密码>§e 登录。";
        msgAuthReminder = "§c你必须先验证身份：/register <密码> 或 /login <密码>";
        msgAuthCountdown = "§c请完成身份验证 §7（剩余 %d 秒）";
        msgAuthBossbar = "§c完成身份验证后才能游戏 · 剩余 %d 秒";
        errNotPlayer = "只有玩家可以使用此命令。";

        errAlreadyRegistered = "§c你已经注册过账号，请使用 §e/login <密码>§c。";
        errPasswordTooShort = "§c密码长度至少为 4 个字符。";
        errPasswordTooLong = "§c密码长度不能超过 32 个字符。";
        msgRegistered = "§a✓ 账号注册成功。";
        msgPremiumEnableHint = "§7如果你使用正版 Minecraft，DirectAuth 会在下次登录时自动验证。";
        errRegistrationCooldown = "§c请稍等片刻后再注册。";
        errIpLimitReached = "§c此 IP 的注册账号数量已达到上限。";
        errStorageUnavailable = "§c认证存储暂时不可用，请稍后再试。";

        errNotRegistered = "§c你还没有注册账号，请使用 §e/register <密码>§c。";
        errAlreadyAuthenticated = "§e你已经完成验证。";
        errCooldown = "§c请等待几秒后再试。";
        errMaxAttempts = "§c失败次数过多。";
        msgAuthenticated = "§a✓ 登录成功。";
        errWrongPassword = "§c密码错误（%d/%d 次）。";
        errWrongPasswordSimple = "§c密码错误。";
        msgTimeout = "§c登录超时。\n§7请下次更快完成身份验证。";

        msgPremiumHint = "§7正版账号会自动识别，不需要执行额外命令。";
        msgAutoLogin = "§a✓ 已自动完成正版验证。";
        msgAutoPasswordSetup = "§e正版账号已自动创建。建议使用 §a/setpassword <密码>§e 设置备用密码，以便 Mojang 不可用时登录。";
        msgTotpSetupSecret = "§eTOTP 密钥：§f%s§e\n请添加到验证器，然后执行 §a/totp verify <验证码>§e。";
        msgTotpLoginRequired = "§e请输入验证器验证码：§a/totp verify <验证码>§e。";
        msgTotpInvalid = "§c验证码无效或已过期。";
        msgTotpEnabled = "§a✓ 双因素认证已启用。";
        msgTotpDisabled = "§e双因素认证已关闭。";
        msgTotpFeatureDisabled = "§e服务器管理员尚未启用双因素认证。";
        msgPremiumAccountCreated = "§a✓ 已自动创建正版账号，并免密登录。";
        errPasswordSetupNotRequired = "§e你的账号已经设置密码，请使用 §a/changepassword§e 修改。";
        msgPremiumFallbackLogin = "§eMojang 暂时不可用，之前验证过的正版身份已自动通过。";
        msgPremiumError = "§c正版验证失败\n§7此账号已绑定正版 UUID，\n§7但本次连接的 UUID 不匹配。\n§7如果这是你的账号，请联系管理员。";
        errNotAuthenticated = "§c请先完成身份验证。";
        errUserNotFound = "§c错误：账号不存在。";
        msgAlreadyPremium = "§e你的账号已经绑定正版模式。";
        msgVerifying = "§e正在向 Mojang 验证账号……";
        errMojangNotFound = "§c没有找到与此用户名对应的正版 Minecraft 账号。";
        msgMojangHint = "§7请确认你使用的是正版 Minecraft 账号。";
        errUUIDMismatch = "§c你的 UUID 与 Mojang 账号不匹配。";
        msgSessionHint = "§7当前使用的是离线会话。";
        msgPremiumSuccess = "§a✓ 正版账号验证成功。";
        msgPremiumKick = "§a正版账号验证成功！\n§e请重新进入服务器以应用 UUID 变更。";
        msgAutoLoginHint = "§7此账号已启用自动登录。";
        msgOnlineModeWarning = "§6警告！§e启用正版模式会迁移玩家数据（例如背包、统计和进度）。如果服务器使用了其他模组，请先确认迁移配置正确并做好备份。";

        msgPremiumWarning = "§c警告！§7此命令为兼容旧流程保留。当前版本会在登录阶段自动验证正版账号。";
        msgAdminPremiumUpdated = "§a已将 %s 的正版模式状态更新为：%s";
        errAdminUserNotFound = "§c数据库中不存在用户 %s。";
        errAdminUsage = "§c用法：/directauth online <用户> <true|false>";
        errAdminUsageReset = "§c用法：/directauth resetpass <用户> <新密码>";
        errAdminUsageUnregister = "§c用法：/directauth unregister <用户>";
        msgConfigReloaded = "§a✓ DirectAuth 配置已重新加载。";
        msgLangReset = "§a✓ 内置中英文和西班牙语语言文件已恢复默认值。";

        msgNoDrop = "§c完成身份验证前不能丢弃物品。";
        msgUseCommands = "§c请先使用认证命令。";

        msgConfirmRequest = "§e⚠️ 需要确认！\n§7你即将执行敏感操作。\n§7请输入 §6/directauth confirm§7 确认。";
        msgPasswordChanged = "§a✓ 密码修改成功。";
        msgAccountDeleted = "§c你的账号已删除。";
        errOldPasswordWrong = "§c旧密码错误。";
        errNoPendingAction = "§c没有等待确认的操作。";
        msgActionExpired = "§c确认请求已过期。";

        msgSessionRestored = "§a欢迎回来，会话已自动恢复。";
        msgLogoutSuccess = "§c已成功退出登录。";

        msgAdminResetSuccess = "§a用户 %s 的密码已重置。";
        msgAdminUnregisterSuccess = "§a用户 %s 已从数据库删除。";
    }

    public void save(Path langPath) {
        try {
            Files.createDirectories(langPath.getParent());
            Writer writer = Files.newBufferedWriter(langPath);
            GSON.toJson(this, writer);
            writer.close();
        } catch (IOException e) {
            System.err.println("Error saving lang config: " + e.getMessage());
        }
    }
}
