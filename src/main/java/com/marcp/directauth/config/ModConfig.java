package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.marcp.directauth.data.MigrationMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonObject;

public class ModConfig {
    private static final boolean DEFAULT_TOTP_ENABLED = false;
    private static final int MESSAGE_SCHEMA_VERSION = 2;

    public String language = "zh";
    public int sessionGracePeriod = 600;
    public int sessionCleanupInterval = 10;
    public boolean sessionPersistence = true;
    public boolean sessionBindToIp = false;
    public int minPasswordLength = 4;
    public int maxPasswordLength = 32;
    public int maxLoginAttempts = 5;
    public long loginCooldownMs = 3000;
    public int loginTimeout = 60;
    public boolean premiumLoginFallbackOnFailure = true;
    public boolean premiumAutoLogin = true;
    public boolean premiumAutoRegister = true;
    public int premiumVerificationTimeoutSeconds = 15;
    public boolean offlineSkinByName = true;
    public String skinNameEndpoint = "https://api.mojang.com/users/profiles/minecraft/{name}";
    public String skinProfileEndpoint = "https://sessionserver.mojang.com/session/minecraft/profile/{uuid}?unsigned=false";
    public String skinAllowedHosts = "api.mojang.com,sessionserver.mojang.com";
    public int registrationDelay = 1;
    public int maxAccountsPerIP = 0;
    public String totpIssuer = "NEOauth";
    public int totpWindowSize = 1;
    public int totpTimeStepSeconds = 30;
    public boolean totpEnabled = DEFAULT_TOTP_ENABLED;
    public int messagesVersion = MESSAGE_SCHEMA_VERSION;
    public Map<String, MigrationMode> migrationMap = new LinkedHashMap<>();
    private transient LangConfig langConfig;

    public ModConfig() { setDefaults(); }

    private void setDefaults() {
        migrationMap.put("playerdata", MigrationMode.RENAME);
        migrationMap.put("stats", MigrationMode.RENAME);
        migrationMap.put("advancements", MigrationMode.RENAME);
        migrationMap.put("deaths", MigrationMode.DIRECTORY);
        migrationMap.put("ftbquests", MigrationMode.TEXT_REPLACE);
        migrationMap.put("skinrestorer", MigrationMode.RENAME);
    }

    public static ModConfig load(Path configPath) {
        return load(configPath, null, null);
    }

    public static ModConfig load(Path configPath, Path legacyConfigPath, Path legacyLanguageDirectory) {
        ModConfig config = new ModConfig();
        NeoauthToml document = NeoauthToml.read(configPath);
        if (Files.exists(configPath)) {
            document.applySection("config", config);
            document.applyMigrationMap(config.migrationMap);
        } else if (legacyConfigPath != null && Files.exists(legacyConfigPath)) {
            try (java.io.Reader reader = Files.newBufferedReader(legacyConfigPath)) {
                Gson gson = new GsonBuilder().create();
                ModConfig legacy = gson.fromJson(reader, ModConfig.class);
                if (legacy != null) {
                    config = legacy;
                    if (config.migrationMap == null || config.migrationMap.isEmpty()) {
                        config.migrationMap = new LinkedHashMap<>();
                        config.setDefaults();
                    }
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to migrate legacy NEOauth configuration", exception);
            }
        }

        int storedMessagesVersion = document.getInt("config.messagesVersion", 0);
        config.normalize();
        LangConfig english = language(document, "en");
        LangConfig chinese = language(document, "zh");
        LangConfig spanish = language(document, "es");
        if (legacyLanguageDirectory != null && !Files.exists(configPath)) {
            english = LangConfig.load(legacyLanguageDirectory.resolve("DirectAuth-lang-en.json"), "en");
            chinese = LangConfig.load(legacyLanguageDirectory.resolve("DirectAuth-lang-zh.json"), "zh");
            spanish = LangConfig.load(legacyLanguageDirectory.resolve("DirectAuth-lang-es.json"), "es");
        }
        if (storedMessagesVersion < MESSAGE_SCHEMA_VERSION) {
            english.applyPromptDefaults("en");
            chinese.applyPromptDefaults("zh");
            spanish.applyPromptDefaults("es");
        }
        config.messagesVersion = MESSAGE_SCHEMA_VERSION;
        config.langConfig = switch (config.language.toLowerCase()) {
            case "zh", "zh_cn", "zh-cn" -> chinese;
            case "es" -> spanish;
            default -> english;
        };

        NeoauthToml output = new NeoauthToml();
        output.putSection("config", config);
        for (Map.Entry<String, MigrationMode> entry : config.migrationMap.entrySet()) {
            output.put("migrationMap." + entry.getKey(), entry.getValue());
        }
        output.putSection("messages.en", english);
        output.putSection("messages.zh", chinese);
        output.putSection("messages.es", spanish);
        output.write(configPath);
        return config;
    }

    private void normalize() {
        if (language == null || language.isBlank()) language = "zh";
        sessionGracePeriod = Math.max(0, sessionGracePeriod);
        sessionCleanupInterval = Math.max(1, sessionCleanupInterval);
        minPasswordLength = Math.max(1, minPasswordLength);
        maxPasswordLength = Math.max(minPasswordLength, maxPasswordLength);
        maxLoginAttempts = Math.max(1, maxLoginAttempts);
        loginCooldownMs = Math.max(0, loginCooldownMs);
        loginTimeout = Math.max(1, loginTimeout);
        premiumVerificationTimeoutSeconds = Math.max(1, premiumVerificationTimeoutSeconds);
        registrationDelay = Math.max(0, registrationDelay);
        maxAccountsPerIP = Math.max(0, maxAccountsPerIP);
        totpWindowSize = Math.max(0, Math.min(5, totpWindowSize));
        totpTimeStepSeconds = Math.max(10, Math.min(300, totpTimeStepSeconds));
        if (migrationMap == null) {
            migrationMap = new LinkedHashMap<>();
            setDefaults();
        }
    }

    private static LangConfig language(NeoauthToml document, String code) {
        LangConfig config = new LangConfig();
        config.setDefaults(code);
        document.applySection("messages." + code, config);
        return config;
    }

    public void save(Path configPath) { load(configPath); }

    public LangConfig getLang() {
        if (langConfig == null) langConfig = new LangConfig();
        return langConfig;
    }
}
