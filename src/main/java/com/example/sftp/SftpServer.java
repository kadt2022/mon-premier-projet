package com.example.sftp;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.SelectorUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Simulated SFTP server based on Apache MINA SSHD.
 */
public class SftpServer {

    private static final Logger log = LoggerFactory.getLogger(SftpServer.class);

    private final SftpServerConfig config;
    private final AuditTrail auditTrail;
    private SshServer sshServer;
    private int boundPort = -1;

    public SftpServer(SftpServerConfig config) {
        this(config, new AuditTrail(200));
    }

    public SftpServer(SftpServerConfig config, AuditTrail auditTrail) {
        this.config = config;
        this.auditTrail = auditTrail;
    }

    /**
     * Starts the SFTP server.
     */
    public void start() throws IOException {
        Path rootDir = Paths.get(config.getRootDirectory()).toAbsolutePath();
        Files.createDirectories(rootDir);
        if (config.isProtectLocalStorage()) {
            LocalStorageSecurity.hardenServerRoot(rootDir);
        }
        log.info("Repertoire racine SFTP : {}", rootDir);

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(config.getPort());

        Path hostKeyPath = rootDir.resolve("hostkey.ser");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyPath));
        SftpSubsystemFactory sftpSubsystemFactory = new SftpSubsystemFactory();
        sftpSubsystemFactory.setFileSystemAccessor(new SftpFileSystemAccessor() {
            @Override
            public Path resolveLocalFilePath(
                    org.apache.sshd.sftp.server.SftpSubsystemProxy subsystem,
                    Path ignoredDefaultDir,
                    String remotePath) throws IOException {
                Path userHome = resolveUserHome(rootDir, subsystem.getSession().getUsername());
                String translated = SelectorUtils.translateToLocalFileSystemPath(
                        remotePath, '/', userHome.getFileSystem());
                Path localPath = userHome.getFileSystem().getPath(translated);
                Path resolved = localPath.isAbsolute()
                        ? localPath.toAbsolutePath().normalize()
                        : userHome.resolve(localPath).toAbsolutePath().normalize();

                if (!resolved.startsWith(userHome)) {
                    throw new InvalidPathException(remotePath, "Access outside user home is not allowed");
                }

                return resolved;
            }
        });
        sshServer.setSubsystemFactories(Collections.singletonList(sftpSubsystemFactory));

        configureAuthentication(rootDir);

        final Path finalRootDir = rootDir;
        NativeFileSystemFactory fsFactory = new NativeFileSystemFactory(true) {
            @Override
            public Path getUserHomeDir(SessionContext session) throws IOException {
                return resolveUserHome(finalRootDir, session.getUsername());
            }
        };
        sshServer.setFileSystemFactory(fsFactory);

        for (String username : config.getUsers().keySet()) {
            Path userDir = rootDir.resolve(username);
            Files.createDirectories(userDir);
            if (config.isProtectLocalStorage()) {
                LocalStorageSecurity.hardenSensitivePath(userDir);
            }
            log.info("Repertoire utilisateur '{}' : {}", username, userDir);
        }

        sshServer.start();
        if (config.isProtectLocalStorage()) {
            LocalStorageSecurity.hardenSensitivePath(hostKeyPath);
        }
        boundPort = resolveBoundPort();
        log.info("Serveur SFTP demarre sur le port {} [mode auth: {}]",
                boundPort, config.getAuthMode());
        auditTrail.record(
                "system",
                "SFTP",
                "SERVER_STARTED",
                rootDir.toString(),
                "SUCCESS",
                "Port " + boundPort + ", mode " + config.getAuthMode());
    }

    /**
     * Configures authenticators for the selected mode.
     */
    private void configureAuthentication(Path rootDir) {
        SftpServerConfig.AuthMode mode = config.getAuthMode();

        if (mode == SftpServerConfig.AuthMode.PASSWORD || mode == SftpServerConfig.AuthMode.BOTH) {
            sshServer.setPasswordAuthenticator((username, password, session) -> {
                String expected = config.getUsers().get(username);
                boolean ok = expected != null && expected.equals(password);
                log.info("Auth mot de passe - utilisateur: {}, succes: {}", username, ok);
                auditTrail.record(
                        username,
                        "SFTP",
                        "PASSWORD_LOGIN",
                        username,
                        ok ? "SUCCESS" : "DENIED",
                        ok ? "Connexion SFTP acceptee" : "Mot de passe refuse");
                return ok;
            });
        }

        if (mode == SftpServerConfig.AuthMode.PUBLIC_KEY || mode == SftpServerConfig.AuthMode.BOTH) {
            sshServer.setPublickeyAuthenticator((username, key, session) -> {
                Path authKeysFile = rootDir
                        .resolve(username)
                        .resolve(".ssh")
                        .resolve("authorized_keys");

                if (!Files.exists(authKeysFile)) {
                    log.warn("Auth cle - pas de fichier authorized_keys pour '{}'", username);
                    auditTrail.record(
                            username,
                            "SFTP",
                            "PUBLIC_KEY_LOGIN",
                            authKeysFile.toString(),
                            "DENIED",
                            "authorized_keys absent");
                    return false;
                }

                try {
                    List<String> lines = Files.readAllLines(authKeysFile);
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }

                        PublicKeyEntry entry = PublicKeyEntry.parsePublicKeyEntry(line);
                        if (entry == null) {
                            continue;
                        }

                        PublicKey authorizedKey = entry.resolvePublicKey(
                                session, Collections.emptyMap(), null);

                        if (authorizedKey != null
                                && Arrays.equals(key.getEncoded(), authorizedKey.getEncoded())) {
                            log.info("Auth cle - succes pour '{}'", username);
                            auditTrail.record(
                                    username,
                                    "SFTP",
                                    "PUBLIC_KEY_LOGIN",
                                    username,
                                    "SUCCESS",
                                    "Cle publique acceptee");
                            return true;
                        }
                    }
                } catch (Exception e) {
                    log.error("Erreur lecture authorized_keys pour '{}': {}", username, e.getMessage());
                    auditTrail.record(
                            username,
                            "SFTP",
                            "PUBLIC_KEY_LOGIN",
                            authKeysFile.toString(),
                            "ERROR",
                            e.getMessage());
                }

                log.warn("Auth cle - echec pour '{}'", username);
                auditTrail.record(
                        username,
                        "SFTP",
                        "PUBLIC_KEY_LOGIN",
                        username,
                        "DENIED",
                        "Cle publique refusee");
                return false;
            });
        }
    }

    /**
     * Stops the SFTP server.
     */
    public void stop() throws IOException {
        if (sshServer != null && sshServer.isStarted()) {
            sshServer.stop();
            boundPort = -1;
            log.info("Serveur SFTP arrete.");
            auditTrail.record(
                    "system",
                    "SFTP",
                    "SERVER_STOPPED",
                    config.getRootDirectory(),
                    "SUCCESS",
                    "Serveur arrete proprement");
        }
    }

    /**
     * Returns whether the server is running.
     */
    public boolean isRunning() {
        return sshServer != null && sshServer.isStarted();
    }

    public int getPort() {
        return boundPort > 0 ? boundPort : config.getPort();
    }

    private int resolveBoundPort() {
        if (sshServer == null) {
            return config.getPort();
        }

        for (SocketAddress address : sshServer.getBoundAddresses()) {
            if (address instanceof InetSocketAddress inetAddress && inetAddress.getPort() > 0) {
                return inetAddress.getPort();
            }
        }

        int serverPort = sshServer.getPort();
        return serverPort > 0 ? serverPort : config.getPort();
    }

    private Path resolveUserHome(Path rootDir, String username) throws IOException {
        Path userDir = rootDir.resolve(username).toAbsolutePath().normalize();
        Files.createDirectories(userDir);
        return userDir;
    }
}
