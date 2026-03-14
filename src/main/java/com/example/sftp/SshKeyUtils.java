package com.example.sftp;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;

/**
 * Utilitaires pour la gestion des clÃ©s SSH.
 *
 * <p>Permet de :
 * <ul>
 *   <li>GÃ©nÃ©rer une paire de clÃ©s RSA</li>
 *   <li>Sauvegarder la clÃ© publique au format OpenSSH {@code authorized_keys}</li>
 *   <li>Sauvegarder la clÃ© privÃ©e au format OpenSSH (compatible WinSCP)</li>
 * </ul>
 *
 * <p>Exemple d'utilisation :
 * <pre>
 *   KeyPair pair = SshKeyUtils.generateRsaKeyPair(4096);
 *   SshKeyUtils.appendToAuthorizedKeys(pair.getPublic(), authorizedKeysPath, "mon-pc");
 *   SshKeyUtils.savePrivateKey(pair, privateKeyPath, "mon-pc");
 * </pre>
 */
public final class SshKeyUtils {

    private static final Logger log = LoggerFactory.getLogger(SshKeyUtils.class);

    private SshKeyUtils() {}

    // -------------------------------------------------------------------------
    // GÃ©nÃ©ration de clÃ©s
    // -------------------------------------------------------------------------

    /**
     * GÃ©nÃ¨re une paire de clÃ©s RSA.
     *
     * @param bits taille de la clÃ© (2048 ou 4096 recommandÃ©)
     * @return la paire de clÃ©s gÃ©nÃ©rÃ©e
     */
    public static KeyPair generateRsaKeyPair(int bits) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits, new SecureRandom());
        log.info("GÃ©nÃ©ration d'une clÃ© RSA {} bits...", bits);
        KeyPair pair = generator.generateKeyPair();
        log.info("ClÃ© RSA gÃ©nÃ©rÃ©e.");
        return pair;
    }

    // -------------------------------------------------------------------------
    // ClÃ© publique â†’ authorized_keys
    // -------------------------------------------------------------------------

    /**
     * Ajoute la clÃ© publique dans le fichier {@code authorized_keys} (format OpenSSH).
     *
     * <p>Le fichier est crÃ©Ã© s'il n'existe pas. La clÃ© est ajoutÃ©e en fin de fichier.
     *
     * @param publicKey  la clÃ© publique Ã  enregistrer
     * @param destFile   chemin vers le fichier {@code authorized_keys}
     * @param comment    commentaire associÃ© Ã  la clÃ© (ex: nom du poste, email)
     */
    public static void appendToAuthorizedKeys(PublicKey publicKey, Path destFile, String comment)
            throws IOException {

        Files.createDirectories(destFile.getParent());

        // Format OpenSSH : "ssh-rsa AAAA... commentaire"
        String keyEntry = PublicKeyEntry.toString(publicKey);
        if (comment != null && !comment.isBlank()) {
            keyEntry = keyEntry + " " + comment;
        }

        Files.writeString(destFile, keyEntry + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        log.info("ClÃ© publique ajoutÃ©e dans : {}", destFile);
    }

    // -------------------------------------------------------------------------
    // ClÃ© privÃ©e â†’ fichier OpenSSH
    // -------------------------------------------------------------------------

    /**
     * Sauvegarde la clÃ© privÃ©e au format OpenSSH
     * ({@code -----BEGIN OPENSSH PRIVATE KEY-----}).
     *
     * <p>Ce format est directement importable dans WinSCP via :
     * <em>AvancÃ© â†’ SSH â†’ Authentification â†’ Fichier de clÃ© privÃ©e</em>.
     *
     * @param keyPair   la paire de clÃ©s Ã  sauvegarder
     * @param destFile  chemin du fichier de sortie (ex: {@code id_rsa})
     * @param comment   commentaire intÃ©grÃ© dans la clÃ© (ex: nom du poste)
     */
    public static void savePrivateKey(KeyPair keyPair, Path destFile, String comment)
            throws IOException, GeneralSecurityException {

        Files.createDirectories(destFile.getParent());

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Ã‰criture au format OpenSSH sans passphrase (null = pas de chiffrement)
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
                keyPair, comment, null, out);

        Files.write(destFile, out.toByteArray(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("ClÃ© privÃ©e sauvegardÃ©e dans : {}", destFile);
    }

    // -------------------------------------------------------------------------
    // Helper : gÃ©nÃ©rer + installer en une seule opÃ©ration
    // -------------------------------------------------------------------------

    /**
     * GÃ©nÃ¨re une paire de clÃ©s RSA et l'installe directement pour un utilisateur.
     *
     * <ul>
     *   <li>ClÃ© publique â†’ {@code {rootDir}/{username}/.ssh/authorized_keys}</li>
     *   <li>ClÃ© privÃ©e  â†’ {@code {rootDir}/{username}/.ssh/id_rsa}</li>
     * </ul>
     *
     * @param rootDir   rÃ©pertoire racine du serveur SFTP
     * @param username  nom de l'utilisateur
     * @param comment   commentaire (ex: nom du poste)
     * @return la paire de clÃ©s gÃ©nÃ©rÃ©e (pour sauvegarde cÃ´tÃ© client si besoin)
     */
    public static KeyPair generateAndInstall(Path rootDir, String username, String comment)
            throws Exception {

        Path sshDir          = rootDir.resolve(username).resolve(".ssh");
        Path authorizedKeys  = sshDir.resolve("authorized_keys");
        Path privateKeyFile  = sshDir.resolve("id_rsa");

        KeyPair pair = generateRsaKeyPair(4096);
        appendToAuthorizedKeys(pair.getPublic(), authorizedKeys, comment);
        savePrivateKey(pair, privateKeyFile, comment);
        LocalStorageSecurity.hardenSensitivePath(sshDir);
        LocalStorageSecurity.hardenSensitivePath(authorizedKeys);
        LocalStorageSecurity.hardenSensitivePath(privateKeyFile);

        log.info("ClÃ©s SSH installÃ©es pour '{}' dans {}", username, sshDir);
        log.info("  ClÃ© privÃ©e  : {}", privateKeyFile);
        log.info("  authorized_keys : {}", authorizedKeys);

        return pair;
    }
}
