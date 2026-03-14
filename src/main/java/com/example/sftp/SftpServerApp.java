package com.example.sftp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * Main entry point for the simulated SFTP server.
 */
public class SftpServerApp {

    private static final Logger log = LoggerFactory.getLogger(SftpServerApp.class);

    public static void main(String[] args) throws Exception {
        SftpRuntimeSettings runtimeSettings = SftpRuntimeSettings.from(args, System.getenv());
        SftpServerConfig config = runtimeSettings.toServerConfig();
        SftpServerConfig.AuthMode mode = runtimeSettings.authMode();
        String primaryUser = runtimeSettings.primaryUser();
        Path rootDir = Paths.get(runtimeSettings.rootDirectory()).toAbsolutePath();
        AuditTrail auditTrail = new AuditTrail(300);

        ensureUserKeyMaterial(rootDir, config, primaryUser, auditTrail);

        SftpServer server = new SftpServer(config, auditTrail);
        WebUiServer webUiServer = runtimeSettings.webUiEnabled()
                ? new WebUiServer(runtimeSettings.webPort(), rootDir, runtimeSettings, auditTrail, server::getPort)
                : null;
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> stopServices(server, webUiServer, shutdownLatch), "sftp-server-shutdown"));

        try {
            server.start();
            if (webUiServer != null) {
                webUiServer.start();
            }
            logStartup(runtimeSettings, server.getPort(), webUiServer, rootDir, primaryUser);
            shutdownLatch.await();
        } catch (Exception e) {
            stopServices(server, webUiServer, null);
            throw e;
        }
    }

    private static void ensureUserKeyMaterial(
            Path rootDir,
            SftpServerConfig config,
            String username,
            AuditTrail auditTrail) throws Exception {
        SftpServerConfig.AuthMode mode = config.getAuthMode();
        if (mode != SftpServerConfig.AuthMode.PUBLIC_KEY && mode != SftpServerConfig.AuthMode.BOTH) {
            return;
        }

        if (config.isProtectLocalStorage()) {
            LocalStorageSecurity.hardenServerRoot(rootDir);
        }

        Path authorizedKeys = rootDir.resolve(username).resolve(".ssh").resolve("authorized_keys");
        if (authorizedKeys.toFile().exists()) {
            log.info("Cles SSH deja presentes pour '{}'.", username);
            return;
        }

        log.info("Generation des cles SSH pour l'utilisateur '{}'...", username);
        SshKeyUtils.generateAndInstall(rootDir, username, "sftp-server-auto");
        log.info(">>> Cle privee a utiliser : {}/{}/.ssh/id_rsa", rootDir, username);
        auditTrail.record(
                "system",
                "SFTP",
                "KEYPAIR_GENERATED",
                rootDir.resolve(username).resolve(".ssh").toString(),
                "SUCCESS",
                "Paire de cles de demonstration creee");
    }

    private static void logStartup(
            SftpRuntimeSettings runtimeSettings,
            int effectivePort,
            WebUiServer webUiServer,
            Path rootDir,
            String primaryUser) {
        SftpServerConfig.AuthMode mode = runtimeSettings.authMode();
        log.info("=======================================================");
        log.info("  Service SFTP operationnel");
        log.info("  Port             : {}", effectivePort);
        log.info("  Port web         : {}",
                webUiServer == null ? "desactive" : webUiServer.getPort());
        log.info("  Mode auth        : {}", mode);
        log.info("  Dossier racine   : {}", rootDir);
        log.info("  Utilisateur demo : {}", primaryUser);
        log.info("  Protection locale: {}",
                runtimeSettings.protectLocalStorage() ? "activee" : "desactivee");
        log.info("  Config env       : AUTH_MODE={}, SFTP_PORT={}, SFTP_ROOT={}, PROTECT_LOCAL_STORAGE={}, ENABLE_WEB_UI={}, WEB_PORT={}",
                mode.name().toLowerCase(),
                runtimeSettings.port(),
                runtimeSettings.rootDirectory(),
                runtimeSettings.protectLocalStorage(),
                runtimeSettings.webUiEnabled(),
                runtimeSettings.webPort());

        if (mode == SftpServerConfig.AuthMode.PASSWORD || mode == SftpServerConfig.AuthMode.BOTH) {
            String password = runtimeSettings.users().get(primaryUser);
            log.info("  -- Connexion par mot de passe --");
            log.info("  sftp -P {} {}@localhost  (mdp: {})", effectivePort, primaryUser, password);
        }
        if (mode == SftpServerConfig.AuthMode.PUBLIC_KEY || mode == SftpServerConfig.AuthMode.BOTH) {
            log.info("  -- Connexion par cle privee --");
            log.info("  sftp -i {}/{}/.ssh/id_rsa -P {} {}@localhost",
                    rootDir, primaryUser, effectivePort, primaryUser);
            log.info("  WinSCP : Avance > SSH > Auth > Fichier de cle privee > id_rsa");
        }
        if (webUiServer != null) {
            log.info("  -- Interface web --");
            log.info("  http://localhost:{}", webUiServer.getPort());
        }
        log.info("  Arret propre : Ctrl+C ou docker stop");
        log.info("=======================================================");
    }

    private static void stopServices(
            SftpServer server,
            WebUiServer webUiServer,
            CountDownLatch shutdownLatch) {
        try {
            if (webUiServer != null && webUiServer.isRunning()) {
                log.info("Arret de l'interface web...");
                webUiServer.stop();
            }
            if (server.isRunning()) {
                log.info("Signal d'arret recu, extinction du serveur SFTP...");
                server.stop();
            }
        } catch (IOException e) {
            log.error("Erreur pendant l'arret du serveur: {}", e.getMessage(), e);
        } finally {
            if (shutdownLatch != null) {
                shutdownLatch.countDown();
            }
        }
    }
}
