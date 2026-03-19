package com.example.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests d'authentification par cle publique et mode BOTH.
 * Utilise le client Apache MINA SSHD pour eviter les incompatibilites de format de cle avec JSch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SftpServerPublicKeyAuthTest {

    private SftpServer publicKeyServer;
    private SftpServer bothModeServer;
    private SshClient sshClient;
    private Path rootDir;
    private KeyPair keyPair;
    private KeyPair wrongPair;

    @BeforeAll
    void setUp() throws Exception {
        rootDir = Files.createTempDirectory("sftp-pk-test-");

        keyPair = SshKeyUtils.generateRsaKeyPair(2048);
        wrongPair = SshKeyUtils.generateRsaKeyPair(2048);

        // Installer la cle pour "pkuser" avec un commentaire et une ligne vide
        // pour couvrir la branche de saut dans la boucle d'analyse authorized_keys
        Path sshDir = rootDir.resolve("pkuser").resolve(".ssh");
        Files.createDirectories(sshDir);
        Path authorizedKeys = sshDir.resolve("authorized_keys");
        // Commentaire et ligne vide pour couvrir les branches de saut dans la boucle
        Files.writeString(authorizedKeys, "# cle de test\n\n");
        SshKeyUtils.appendToAuthorizedKeys(keyPair.getPublic(), authorizedKeys, "test");

        // Serveur mode PUBLIC_KEY
        SftpServerConfig pkConfig = new SftpServerConfig()
                .setPort(0)
                .setRootDirectory(rootDir.toString())
                .setAuthMode(SftpServerConfig.AuthMode.PUBLIC_KEY)
                .setProtectLocalStorage(false)
                .addUser("pkuser", "ignored");
        publicKeyServer = new SftpServer(pkConfig);
        publicKeyServer.start();

        // Serveur mode BOTH
        SftpServerConfig bothConfig = new SftpServerConfig()
                .setPort(0)
                .setRootDirectory(rootDir.toString())
                .setAuthMode(SftpServerConfig.AuthMode.BOTH)
                .setProtectLocalStorage(false)
                .addUser("pkuser", "testpass");
        bothModeServer = new SftpServer(bothConfig);
        bothModeServer.start();

        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (sshClient != null) {
            sshClient.stop();
        }
        if (publicKeyServer != null) {
            publicKeyServer.stop();
        }
        if (bothModeServer != null) {
            bothModeServer.stop();
        }
        deleteDirectory(rootDir);
    }

    @Test
    @DisplayName("Connexion par cle publique valide acceptee")
    void connectWithValidPublicKey() throws Exception {
        try (ClientSession session = openSession("pkuser", publicKeyServer.getPort())) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(5, TimeUnit.SECONDS);
            assertTrue(session.isOpen());
        }
    }

    @Test
    @DisplayName("Connexion refusee quand authorized_keys est absent")
    void connectDeniedWhenNoAuthorizedKeys() throws Exception {
        try (ClientSession session = openSession("nokey-user", publicKeyServer.getPort())) {
            session.addPublicKeyIdentity(keyPair);
            AuthFuture auth = session.auth();
            assertTrue(auth.await(5, TimeUnit.SECONDS));
            assertFalse(auth.isSuccess());
        }
    }

    @Test
    @DisplayName("Connexion refusee avec une cle non autorisee")
    void connectDeniedWithWrongKey() throws Exception {
        try (ClientSession session = openSession("pkuser", publicKeyServer.getPort())) {
            session.addPublicKeyIdentity(wrongPair);
            AuthFuture auth = session.auth();
            assertTrue(auth.await(5, TimeUnit.SECONDS));
            assertFalse(auth.isSuccess());
        }
    }

    @Test
    @DisplayName("Mode BOTH accepte l'authentification par mot de passe")
    void bothModeAcceptsPasswordAuth() throws Exception {
        try (ClientSession session = openSession("pkuser", bothModeServer.getPort())) {
            session.addPasswordIdentity("testpass");
            session.auth().verify(5, TimeUnit.SECONDS);
            assertTrue(session.isOpen());
        }
    }

    @Test
    @DisplayName("Mode BOTH accepte l'authentification par cle publique")
    void bothModeAcceptsPublicKeyAuth() throws Exception {
        try (ClientSession session = openSession("pkuser", bothModeServer.getPort())) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(5, TimeUnit.SECONDS);
            assertTrue(session.isOpen());
        }
    }

    private ClientSession openSession(String username, int port) throws Exception {
        return sshClient.connect(username, "localhost", port)
                .verify(5, TimeUnit.SECONDS)
                .getSession();
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }
}
