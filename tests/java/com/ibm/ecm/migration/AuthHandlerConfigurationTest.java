package com.ibm.ecm.migration;

import java.util.Properties;

public final class AuthHandlerConfigurationTest {
    public static void main(String[] args) {
        Properties disabled = new Properties();
        disabled.setProperty("webgui.auth.enabled", "false");
        AuthHandler.validateConfiguration(disabled);

        Properties invalidEnabled = new Properties();
        invalidEnabled.setProperty("webgui.auth.enabled", "typo");
        expectFailure(invalidEnabled, "invalid auth enabled value");

        expectFailure(new Properties(), "enabled auth without user or credentials");

        Properties missingCredentials = enabledConfig();
        expectFailure(missingCredentials, "enabled auth without credentials");

        Properties missingUser = new Properties();
        missingUser.setProperty("webgui.auth.enabled", "true");
        missingUser.setProperty("webgui.admin.password", "test-only-password-do-not-use");
        expectFailure(missingUser, "enabled auth without user");

        Properties withPassword = enabledConfig();
        withPassword.setProperty("webgui.admin.password", "test-only-password-do-not-use");
        AuthHandler.validateConfiguration(withPassword);

        Properties withHash = enabledConfig();
        withHash.setProperty("webgui.admin.password.hash", repeat("ab", 32));
        AuthHandler.validateConfiguration(withHash);

        Properties invalidHash = enabledConfig();
        String invalidValue = "not-a-valid-sha256-hash";
        invalidHash.setProperty("webgui.admin.password.hash", invalidValue);
        try {
            AuthHandler.validateConfiguration(invalidHash);
            throw new AssertionError("invalid hash must fail");
        } catch (IllegalStateException expected) {
            assertNotContains(expected.getMessage(), invalidValue, "hash value leaked in error");
        }

        System.out.println("AuthHandlerConfigurationTest: PASS");
    }

    private static Properties enabledConfig() {
        Properties properties = new Properties();
        properties.setProperty("webgui.auth.enabled", "true");
        properties.setProperty("webgui.admin.user", "admin");
        return properties;
    }

    private static void expectFailure(Properties properties, String message) {
        try {
            AuthHandler.validateConfiguration(properties);
            throw new AssertionError(message + " must fail");
        } catch (IllegalStateException expected) {
            assertNotContains(expected.getMessage(), "test-only-password-do-not-use", "password value leaked in error");
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void assertNotContains(String text, String value, String message) {
        if (text != null && text.contains(value)) throw new AssertionError(message);
    }
}
