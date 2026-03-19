package com.example.sftp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SftpRuntimeSettingsTest {

    @Test
    @DisplayName("Les arguments de ligne de commande priment sur les variables d'environnement")
    void commandLineOverridesEnvironment() {
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"pubkey", "2200", "./custom-root"},
                Map.of(
                        "AUTH_MODE", "password",
                        "SFTP_PORT", "2300",
                        "SFTP_ROOT", "./env-root",
                        "PROTECT_LOCAL_STORAGE", "false"));

        assertEquals(SftpServerConfig.AuthMode.PUBLIC_KEY, settings.authMode());
        assertEquals(2200, settings.port());
        assertEquals("./custom-root", settings.rootDirectory());
        assertFalse(settings.protectLocalStorage());
        assertEquals(8080, settings.webPort());
        assertTrue(settings.webUiEnabled());
    }

    @Test
    @DisplayName("Les utilisateurs peuvent etre fournis via SFTP_USERS")
    void parsesUsersFromEnvironment() {
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[0],
                Map.of("SFTP_USERS", "alice=secret;bob=topsecret"));

        assertEquals("secret", settings.users().get("alice"));
        assertEquals("topsecret", settings.users().get("bob"));
        assertEquals("alice", settings.primaryUser());
    }

    @Test
    @DisplayName("Le mode par defaut reste both")
    void defaultsToBothMode() {
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(new String[0], Map.of());

        assertEquals(SftpServerConfig.AuthMode.BOTH, settings.authMode());
        assertEquals(2222, settings.port());
        assertEquals(8080, settings.webPort());
        assertEquals("./sftp-root", settings.rootDirectory());
        assertTrue(settings.protectLocalStorage());
        assertTrue(settings.webUiEnabled());
        assertEquals("password", settings.users().get("user"));
    }

    @Test
    @DisplayName("Le port web et l'activation de l'interface sont configurables")
    void parsesWebUiSettings() {
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", "./root", "9090"},
                Map.of("ENABLE_WEB_UI", "false"));

        assertEquals(9090, settings.webPort());
        assertFalse(settings.webUiEnabled());
    }

    @Test
    @DisplayName("Les valeurs invalides sont rejetees explicitement")
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () ->
                SftpRuntimeSettings.from(new String[0], Map.of("AUTH_MODE", "invalid")));
        assertThrows(IllegalArgumentException.class, () ->
                SftpRuntimeSettings.from(new String[0], Map.of("SFTP_PORT", "99999")));
        assertThrows(IllegalArgumentException.class, () ->
                SftpRuntimeSettings.from(new String[0], Map.of("SFTP_USERS", "alice")));
    }

    @Test
    @DisplayName("toServerConfig produit une configuration coherente avec les parametres")
    void toServerConfigMapsAllFields() {
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"password", "2022", "./my-root"},
                Map.of(
                        "SFTP_USERS", "alice=secret",
                        "PROTECT_LOCAL_STORAGE", "false"));

        SftpServerConfig config = settings.toServerConfig();

        assertEquals(2022, config.getPort());
        assertEquals("./my-root", config.getRootDirectory());
        assertEquals(SftpServerConfig.AuthMode.PASSWORD, config.getAuthMode());
        assertFalse(config.isProtectLocalStorage());
        assertEquals("secret", config.getUsers().get("alice"));
        assertNotNull(config);
    }

    @Test
    @DisplayName("parseAuthMode reconnait le mode password")
    void parsesPasswordAuthMode() {
        assertEquals(SftpServerConfig.AuthMode.PASSWORD,
                SftpRuntimeSettings.parseAuthMode("password"));
        assertEquals(SftpServerConfig.AuthMode.PASSWORD,
                SftpRuntimeSettings.parseAuthMode("PASSWORD"));
    }

    @Test
    @DisplayName("parseBoolean accepte toutes les valeurs vraies")
    void parseBooleanAcceptsTruthyValues() {
        assertTrue(SftpRuntimeSettings.parseBoolean("true", false));
        assertTrue(SftpRuntimeSettings.parseBoolean("1", false));
        assertTrue(SftpRuntimeSettings.parseBoolean("yes", false));
        assertTrue(SftpRuntimeSettings.parseBoolean("y", false));
        assertTrue(SftpRuntimeSettings.parseBoolean("on", false));
    }

    @Test
    @DisplayName("parseBoolean rejette une valeur inconnue")
    void parseBooleanRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () ->
                SftpRuntimeSettings.parseBoolean("maybe", false));
    }

    @Test
    @DisplayName("parseUsers ignore les entrees vides dans la chaine")
    void parseUsersSkipsEmptyEntries() {
        Map<String, String> users = SftpRuntimeSettings.parseUsers(";alice=secret;");
        assertEquals(1, users.size());
        assertEquals("secret", users.get("alice"));
    }

    @Test
    @DisplayName("parseUsers rejette une chaine ne contenant que des separateurs")
    void parseUsersThrowsWhenNoValidEntry() {
        assertThrows(IllegalArgumentException.class, () ->
                SftpRuntimeSettings.parseUsers(";;"));
    }
}
