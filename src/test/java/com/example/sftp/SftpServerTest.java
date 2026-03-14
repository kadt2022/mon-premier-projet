package com.example.sftp;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the simulated SFTP server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SftpServerTest {

    private SftpServer server;
    private Path rootDir;
    private Path userHomeDir;

    @BeforeAll
    void startServer() throws Exception {
        rootDir = Files.createTempDirectory("sftp-test-");
        userHomeDir = rootDir.resolve("testuser");

        SftpServerConfig config = new SftpServerConfig()
                .setPort(0)
                .setRootDirectory(rootDir.toString())
                .addUser("testuser", "testpass");

        server = new SftpServer(config);
        server.start();
    }

    @AfterAll
    void stopServer() throws Exception {
        server.stop();
        deleteDirectory(rootDir);
    }

    @Test
    @DisplayName("Le serveur demarre correctement")
    void serverStartsSuccessfully() {
        assertTrue(server.isRunning(), "Le serveur doit etre demarre");
        assertTrue(server.getPort() > 0, "Le serveur doit exposer le port reellement ouvert");
    }

    @Test
    @DisplayName("Le stockage local est masque sous Windows")
    void hideRootDirectoryOnWindows() throws IOException {
        assumeTrue(LocalStorageSecurity.isWindows(), "Test reserve a Windows");
        assertTrue((Boolean) Files.getAttribute(rootDir, "dos:hidden"),
                "La racine SFTP doit etre masquee dans l'explorateur Windows");
    }

    @Test
    @DisplayName("Connexion avec identifiants valides")
    void connectWithValidCredentials() throws Exception {
        ChannelSftp sftp = openSftpChannel("testuser", "testpass");
        assertNotNull(sftp);
        disconnect(sftp);
    }

    @Test
    @DisplayName("Connexion refusee avec mauvais mot de passe")
    void rejectInvalidPassword() {
        assertThrows(JSchException.class, () -> openSftpChannel("testuser", "mauvais"));
    }

    @Test
    @DisplayName("Upload d'un fichier")
    void uploadFile() throws Exception {
        ChannelSftp sftp = openSftpChannel("testuser", "testpass");
        try {
            String contenu = "Bonjour SFTP !";
            byte[] bytes = contenu.getBytes(StandardCharsets.UTF_8);
            sftp.put(new ByteArrayInputStream(bytes), "upload-test.txt");

            Path fichier = userHomeDir.resolve("upload-test.txt");
            assertTrue(Files.exists(fichier), "Le fichier doit exister dans le home utilisateur");
            assertEquals(contenu, Files.readString(fichier));
        } finally {
            disconnect(sftp);
        }
    }

    @Test
    @DisplayName("Download d'un fichier")
    void downloadFile() throws Exception {
        String contenu = "Fichier a telecharger";
        Files.writeString(userHomeDir.resolve("download-test.txt"), contenu);

        ChannelSftp sftp = openSftpChannel("testuser", "testpass");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sftp.get("download-test.txt", out);
            assertEquals(contenu, out.toString(StandardCharsets.UTF_8));
        } finally {
            disconnect(sftp);
        }
    }

    @Test
    @DisplayName("Listage du repertoire")
    void listDirectory() throws Exception {
        Files.writeString(userHomeDir.resolve("fichier1.txt"), "a");
        Files.writeString(userHomeDir.resolve("fichier2.txt"), "b");

        ChannelSftp sftp = openSftpChannel("testuser", "testpass");
        try {
            var entries = sftp.ls(".");
            var fileNames = entries.stream()
                    .map(entry -> ((ChannelSftp.LsEntry) entry).getFilename())
                    .toList();

            assertTrue(fileNames.contains("fichier1.txt"), "fichier1.txt doit etre visible");
            assertTrue(fileNames.contains("fichier2.txt"), "fichier2.txt doit etre visible");
        } finally {
            disconnect(sftp);
        }
    }

    @Test
    @DisplayName("Le dossier .ssh genere est masque sous Windows")
    void hideGeneratedSshDirectoryOnWindows() throws Exception {
        assumeTrue(LocalStorageSecurity.isWindows(), "Test reserve a Windows");

        Path keyRoot = Files.createTempDirectory("sftp-key-root-");
        try {
            LocalStorageSecurity.hardenServerRoot(keyRoot);
            SshKeyUtils.generateAndInstall(keyRoot, "keyuser", "test-key");

            Path sshDir = keyRoot.resolve("keyuser").resolve(".ssh");
            Path privateKey = sshDir.resolve("id_rsa");

            assertTrue(Files.exists(privateKey), "La cle privee doit etre generee");
            assertTrue((Boolean) Files.getAttribute(sshDir, "dos:hidden"),
                    "Le dossier .ssh doit etre masque");
            assertTrue((Boolean) Files.getAttribute(privateKey, "dos:hidden"),
                    "La cle privee doit etre masquee");
        } finally {
            deleteDirectory(keyRoot);
        }
    }

    private ChannelSftp openSftpChannel(String user, String password) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(user, "localhost", server.getPort());
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(5000);

        Channel channel = session.openChannel("sftp");
        channel.connect(3000);
        return (ChannelSftp) channel;
    }

    private void disconnect(ChannelSftp sftp) {
        if (sftp == null) {
            return;
        }

        Session session = null;
        try {
            session = sftp.getSession();
        } catch (JSchException ignored) {
            // Best effort cleanup.
        }
        sftp.disconnect();
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
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
