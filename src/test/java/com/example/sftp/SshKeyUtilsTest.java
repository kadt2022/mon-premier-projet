package com.example.sftp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshKeyUtilsTest {

    private Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    @Test
    @DisplayName("Les utilitaires peuvent generer et sauvegarder une paire RSA")
    void generatesAndStoresOpenSshKeys() throws Exception {
        tempDir = Files.createTempDirectory("ssh-key-utils-");
        Path authorizedKeys = tempDir.resolve(".ssh").resolve("authorized_keys");
        Path privateKey = tempDir.resolve(".ssh").resolve("id_rsa");

        KeyPair pair = SshKeyUtils.generateRsaKeyPair(2048);
        SshKeyUtils.appendToAuthorizedKeys(pair.getPublic(), authorizedKeys, "unit-test");
        SshKeyUtils.savePrivateKey(pair, privateKey, "unit-test");

        assertEquals("RSA", pair.getPrivate().getAlgorithm());
        assertTrue(Files.readString(authorizedKeys).contains("unit-test"));
        assertTrue(Files.readString(authorizedKeys).contains("ssh-rsa"));
        assertTrue(Files.readString(privateKey).contains("BEGIN OPENSSH PRIVATE KEY"));
    }

    @Test
    @DisplayName("La generation complete installe la cle privee et authorized_keys")
    void generateAndInstallCreatesExpectedFiles() throws Exception {
        tempDir = Files.createTempDirectory("ssh-key-install-");

        KeyPair pair = SshKeyUtils.generateAndInstall(tempDir, "alice", "demo");
        Path sshDir = tempDir.resolve("alice").resolve(".ssh");
        Path authorizedKeys = sshDir.resolve("authorized_keys");
        Path privateKey = sshDir.resolve("id_rsa");

        assertNotNull(pair);
        assertTrue(Files.exists(authorizedKeys));
        assertTrue(Files.exists(privateKey));
        assertFalse(Files.readString(authorizedKeys).isBlank());
        assertTrue(Files.size(privateKey) > 0);
    }
}
