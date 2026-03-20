package com.example.sftp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hardens the local filesystem storage used by the SFTP server.
 *
 * <p>On Windows, NTFS ACLs are restricted to the runtime account, SYSTEM and
 * the local Administrators group. This protects the storage against other
 * local accounts, but not against the runtime account itself.</p>
 */
public final class LocalStorageSecurity {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageSecurity.class);

    private static final String WINDOWS_SYSTEM = "*S-1-5-18";
    private static final String WINDOWS_ADMINS = "*S-1-5-32-544";

    private LocalStorageSecurity() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    public static void hardenServerRoot(Path rootDir) throws IOException {
        Path normalized = rootDir.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        applyHiddenAttributes(normalized, true);

        if (!isWindows()) {
            return;
        }

        String runtimeIdentity = resolveRuntimeIdentity(normalized);
        restrictAcl(normalized, true, runtimeIdentity);
        log.warn(
                "Le stockage local '{}' est reserve a {}, {} et {}. "
                        + "Si le serveur tourne sous votre compte interactif, ce compte garde l'acces local. "
                        + "Pour bloquer l'explorateur, utilisez un compte de service dedie.",
                normalized,
                runtimeIdentity,
                WINDOWS_SYSTEM,
                WINDOWS_ADMINS);
    }

    public static void hardenSensitivePath(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }

        Path normalized = path.toAbsolutePath().normalize();
        boolean directory = Files.isDirectory(normalized);
        applyHiddenAttributes(normalized, directory);

        if (!isWindows()) {
            return;
        }

        restrictAcl(normalized, directory, resolveRuntimeIdentity(normalized));
    }

    private static void applyHiddenAttributes(Path path, boolean directory) throws IOException {
        DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class);
        if (view == null) {
            return;
        }

        view.setHidden(true);
        if (directory) {
            view.setSystem(true);
        }
    }

    /**
     * Rejects identities that contain characters which could alter the icacls command line.
     * ProcessBuilder does not invoke a shell, but icacls itself parses its arguments and
     * could misinterpret certain sequences.  Keeping identities to printable ASCII without
     * shell metacharacters is a conservative, safe policy.
     */
    private static void validateIdentity(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("L'identite du compte d'execution est vide");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || "&|;<>(){}[]$`\"'\r\n\0".indexOf(c) >= 0) {
                throw new IOException(
                        "L'identite du compte contient un caractere non autorise (U+"
                                + Integer.toHexString(c).toUpperCase(Locale.ROOT) + "): " + value);
            }
        }
    }

    private static String resolveRuntimeIdentity(Path path) throws IOException {
        String runtimeUser = System.getProperty("user.name", "").trim();
        if (!runtimeUser.isBlank()) {
            try {
                UserPrincipal principal = path.getFileSystem()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName(runtimeUser);
                if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
                    String identity = principal.getName();
                    validateIdentity(identity);
                    return identity;
                }
            } catch (IOException ignored) {
                // Fall back to the folder owner if name resolution fails.
            }
        }

        UserPrincipal owner = Files.getOwner(path);
        if (owner == null || owner.getName() == null || owner.getName().isBlank()) {
            throw new IOException("Impossible de determiner le compte Windows a autoriser pour " + path);
        }
        String identity = owner.getName();
        validateIdentity(identity);
        return identity;
    }

    private static void restrictAcl(Path path, boolean directory, String runtimeIdentity) throws IOException {
        runIcacls(path, buildGrantArguments(directory, runtimeIdentity));
        runIcacls(path, buildInheritanceArguments());
    }

    private static List<String> buildInheritanceArguments() {
        List<String> args = new ArrayList<>();
        args.add("/inheritance:r");
        return args;
    }

    private static List<String> buildGrantArguments(boolean directory, String runtimeIdentity) {
        String permission = directory ? "(OI)(CI)F" : "F";

        List<String> args = new ArrayList<>();
        args.add("/grant:r");
        args.add(runtimeIdentity + ":" + permission);
        args.add(WINDOWS_SYSTEM + ":" + permission);
        args.add(WINDOWS_ADMINS + ":" + permission);
        return args;
    }

    private static void runIcacls(Path path, List<String> args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("icacls");
        command.add(path.toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Execution de icacls interrompue pour " + path, e);
        }

        if (exitCode != 0) {
            throw new IOException("icacls a echoue pour " + path + ": " + output);
        }

        if (!output.isBlank()) {
            log.debug("icacls {} -> {}", path, output);
        }
    }
}
