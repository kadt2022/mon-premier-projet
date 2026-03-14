package com.example.sftp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves runtime settings from command-line arguments and environment variables.
 */
final class SftpRuntimeSettings {

    private static final int DEFAULT_PORT = 2222;
    private static final int DEFAULT_WEB_PORT = 8080;
    private static final String DEFAULT_ROOT_DIRECTORY = "./sftp-root";

    private final SftpServerConfig.AuthMode authMode;
    private final int port;
    private final int webPort;
    private final boolean webUiEnabled;
    private final String rootDirectory;
    private final boolean protectLocalStorage;
    private final Map<String, String> users;

    private SftpRuntimeSettings(
            SftpServerConfig.AuthMode authMode,
            int port,
            int webPort,
            boolean webUiEnabled,
            String rootDirectory,
            boolean protectLocalStorage,
            Map<String, String> users) {
        this.authMode = authMode;
        this.port = port;
        this.webPort = webPort;
        this.webUiEnabled = webUiEnabled;
        this.rootDirectory = rootDirectory;
        this.protectLocalStorage = protectLocalStorage;
        this.users = new LinkedHashMap<>(users);
    }

    static SftpRuntimeSettings from(String[] args, Map<String, String> env) {
        String authModeValue = firstNonBlank(argValue(args, 0), env.get("AUTH_MODE"));
        String portValue = firstNonBlank(argValue(args, 1), env.get("SFTP_PORT"));
        String rootValue = firstNonBlank(argValue(args, 2), env.get("SFTP_ROOT"));
        String webPortValue = firstNonBlank(argValue(args, 3), env.get("WEB_PORT"));

        SftpServerConfig.AuthMode authMode = parseAuthMode(authModeValue);
        int port = parsePort(portValue);
        int webPort = parsePort(webPortValue, DEFAULT_WEB_PORT, "WEB_PORT");
        boolean webUiEnabled = parseBoolean(env.get("ENABLE_WEB_UI"), true);
        String rootDirectory = rootValue == null ? DEFAULT_ROOT_DIRECTORY : rootValue;
        boolean protectLocalStorage = parseBoolean(env.get("PROTECT_LOCAL_STORAGE"), true);
        Map<String, String> users = parseUsers(env.get("SFTP_USERS"));

        return new SftpRuntimeSettings(
                authMode,
                port,
                webPort,
                webUiEnabled,
                rootDirectory,
                protectLocalStorage,
                users);
    }

    SftpServerConfig toServerConfig() {
        SftpServerConfig config = new SftpServerConfig()
                .setPort(port)
                .setRootDirectory(rootDirectory)
                .setAuthMode(authMode)
                .setProtectLocalStorage(protectLocalStorage);

        config.getUsers().clear();
        users.forEach(config::addUser);
        return config;
    }

    SftpServerConfig.AuthMode authMode() {
        return authMode;
    }

    int port() {
        return port;
    }

    int webPort() {
        return webPort;
    }

    boolean webUiEnabled() {
        return webUiEnabled;
    }

    String rootDirectory() {
        return rootDirectory;
    }

    boolean protectLocalStorage() {
        return protectLocalStorage;
    }

    Map<String, String> users() {
        return new LinkedHashMap<>(users);
    }

    String primaryUser() {
        return users.keySet().stream().findFirst().orElse("user");
    }

    static SftpServerConfig.AuthMode parseAuthMode(String value) {
        if (value == null || value.isBlank()) {
            return SftpServerConfig.AuthMode.BOTH;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "password" -> SftpServerConfig.AuthMode.PASSWORD;
            case "pubkey", "public_key", "public-key", "publickey", "key" ->
                    SftpServerConfig.AuthMode.PUBLIC_KEY;
            case "both" -> SftpServerConfig.AuthMode.BOTH;
            default -> throw new IllegalArgumentException("AUTH_MODE invalide: " + value);
        };
    }

    static int parsePort(String value) {
        return parsePort(value, DEFAULT_PORT, "SFTP_PORT");
    }

    static int parsePort(String value, int defaultValue, String label) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        int port = Integer.parseInt(value.trim());
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(label + " invalide: " + value);
        }
        return port;
    }

    static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalArgumentException("Booleen invalide: " + value);
        };
    }

    static Map<String, String> parseUsers(String value) {
        if (value == null || value.isBlank()) {
            return defaultUsers();
        }

        Map<String, String> parsed = new LinkedHashMap<>();
        String[] entries = value.split("[;,]");
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int separatorIndex = trimmed.indexOf('=');
            if (separatorIndex <= 0 || separatorIndex == trimmed.length() - 1) {
                throw new IllegalArgumentException("Entree SFTP_USERS invalide: " + trimmed);
            }

            String username = trimmed.substring(0, separatorIndex).trim();
            String password = trimmed.substring(separatorIndex + 1).trim();
            if (username.isEmpty() || password.isEmpty()) {
                throw new IllegalArgumentException("Entree SFTP_USERS invalide: " + trimmed);
            }

            parsed.put(username, password);
        }

        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("SFTP_USERS ne contient aucun utilisateur exploitable");
        }
        return parsed;
    }

    private static Map<String, String> defaultUsers() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("user", "password");
        defaults.put("admin", "admin123");
        return defaults;
    }

    private static String argValue(String[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            return null;
        }
        return args[index];
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
