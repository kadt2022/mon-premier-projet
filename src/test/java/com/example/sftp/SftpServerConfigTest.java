package com.example.sftp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpServerConfigTest {

    @Test
    @DisplayName("La configuration expose des valeurs par defaut coherentes")
    void exposesExpectedDefaults() {
        SftpServerConfig config = new SftpServerConfig();

        assertEquals(2222, config.getPort());
        assertEquals("./sftp-root", config.getRootDirectory());
        assertEquals(SftpServerConfig.AuthMode.PASSWORD, config.getAuthMode());
        assertTrue(config.isProtectLocalStorage());
        assertEquals("password", config.getUsers().get("user"));
    }

    @Test
    @DisplayName("La configuration est fluide et accumule les utilisateurs")
    void supportsFluentConfiguration() {
        SftpServerConfig config = new SftpServerConfig();

        assertSame(config, config.setPort(2022));
        assertSame(config, config.setRootDirectory("/tmp/demo-root"));
        assertSame(config, config.setAuthMode(SftpServerConfig.AuthMode.BOTH));
        assertSame(config, config.setProtectLocalStorage(false));
        assertSame(config, config.addUser("alice", "secret"));

        assertEquals(2022, config.getPort());
        assertEquals("/tmp/demo-root", config.getRootDirectory());
        assertEquals(SftpServerConfig.AuthMode.BOTH, config.getAuthMode());
        assertEquals(false, config.isProtectLocalStorage());
        assertEquals("secret", config.getUsers().get("alice"));
    }
}
