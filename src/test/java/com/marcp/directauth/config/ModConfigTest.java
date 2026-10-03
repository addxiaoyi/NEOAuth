package com.marcp.directauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void generatedConfigDefaultsToChineseAndDisablesTotp() throws Exception {
        Path configPath = tempDir.resolve("Neoauth.toml");
        ModConfig config = ModConfig.load(configPath);

        assertEquals("zh", config.language);
        assertFalse(config.totpEnabled);
        assertEquals(600, config.sessionGracePeriod);
        assertEquals(0, config.maxAccountsPerIP);
        assertTrue(Files.readString(configPath).contains("totpEnabled = false"));
    }

    @Test
    void unsafeConfigValuesAreClamped() throws Exception {
        Path configPath = tempDir.resolve("Neoauth.toml");
        Files.writeString(configPath, """
                [config]
                language = ""
                sessionGracePeriod = -10
                sessionCleanupInterval = 0
                minPasswordLength = 0
                maxPasswordLength = 0
                maxLoginAttempts = 0
                loginTimeout = 0
                totpWindowSize = 99
                totpTimeStepSeconds = 1
                """);

        ModConfig config = ModConfig.load(configPath);

        assertEquals("zh", config.language);
        assertEquals(0, config.sessionGracePeriod);
        assertEquals(1, config.sessionCleanupInterval);
        assertEquals(1, config.minPasswordLength);
        assertEquals(1, config.maxPasswordLength);
        assertEquals(1, config.maxLoginAttempts);
        assertEquals(1, config.loginTimeout);
        assertEquals(5, config.totpWindowSize);
        assertEquals(10, config.totpTimeStepSeconds);
    }
    @Test
    void oldPromptTextIsMigratedToTheNaturalDefaults() throws Exception {
        Path configPath = tempDir.resolve("Neoauth.toml");
        Files.writeString(configPath, """
                [config]
                language = "zh"
                messagesVersion = 1
                [messages.zh]
                msgAuthReminder = "旧版重复提示"
                """);

        ModConfig.load(configPath);
        String generated = Files.readString(configPath);

        assertFalse(generated.contains("旧版重复提示"));
        assertTrue(generated.contains("已有账号请输入"));
        assertTrue(generated.contains("messagesVersion = 2"));
    }

}
