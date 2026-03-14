package com.example.sftp;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration du serveur SFTP simulé.
 */
public class SftpServerConfig {

    /**
     * Mode d'authentification supporté par le serveur.
     */
    public enum AuthMode {
        /** Authentification par mot de passe uniquement */
        PASSWORD,
        /** Authentification par clé publique uniquement */
        PUBLIC_KEY,
        /** Les deux modes acceptés (mot de passe OU clé) */
        BOTH
    }

    private int port = 2222;
    private String rootDirectory = "./sftp-root";
    private AuthMode authMode = AuthMode.PASSWORD;
    private boolean protectLocalStorage = true;

    /** username → mot de passe (utilisé si authMode = PASSWORD ou BOTH) */
    private Map<String, String> users = new HashMap<>();

    public SftpServerConfig() {
        users.put("user", "password");
    }

    // -------------------------------------------------------------------------
    // Port
    // -------------------------------------------------------------------------

    public int getPort() { return port; }

    public SftpServerConfig setPort(int port) {
        this.port = port;
        return this;
    }

    // -------------------------------------------------------------------------
    // Répertoire racine
    // -------------------------------------------------------------------------

    public String getRootDirectory() { return rootDirectory; }

    public SftpServerConfig setRootDirectory(String rootDirectory) {
        this.rootDirectory = rootDirectory;
        return this;
    }

    // -------------------------------------------------------------------------
    // Mode d'authentification
    // -------------------------------------------------------------------------

    public AuthMode getAuthMode() { return authMode; }

    public SftpServerConfig setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
        return this;
    }

    public boolean isProtectLocalStorage() { return protectLocalStorage; }

    public SftpServerConfig setProtectLocalStorage(boolean protectLocalStorage) {
        this.protectLocalStorage = protectLocalStorage;
        return this;
    }

    // -------------------------------------------------------------------------
    // Utilisateurs (mot de passe)
    // -------------------------------------------------------------------------

    public Map<String, String> getUsers() { return users; }

    public SftpServerConfig addUser(String username, String password) {
        this.users.put(username, password);
        return this;
    }
}
