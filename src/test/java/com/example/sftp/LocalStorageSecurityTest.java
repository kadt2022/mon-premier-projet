package com.example.sftp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalStorageSecurityTest {

    private String originalOsName;
    private Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (originalOsName != null) {
            System.setProperty("os.name", originalOsName);
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    @Test
    @DisplayName("La detection Windows depend du nom du systeme")
    void detectsWindowsFromOsName() {
        originalOsName = System.getProperty("os.name");

        System.setProperty("os.name", "Windows 11");
        assertTrue(LocalStorageSecurity.isWindows());

        System.setProperty("os.name", "Linux");
        assertFalse(LocalStorageSecurity.isWindows());
    }

    @Test
    @DisplayName("Le durcissement public ne casse pas sur le systeme courant")
    void hardeningMethodsDoNotFailOnCurrentPlatform() throws Exception {
        tempDir = Files.createTempDirectory("local-storage-security-");
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "demo");

        LocalStorageSecurity.hardenServerRoot(tempDir);
        LocalStorageSecurity.hardenSensitivePath(file);
        LocalStorageSecurity.hardenSensitivePath(tempDir.resolve("missing.txt"));
        LocalStorageSecurity.hardenSensitivePath(null);

        assertTrue(Files.exists(tempDir));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(tempDir.resolve("missing.txt")));
    }

    // --- validateIdentity (private) tested via reflection ---

    private static void callValidateIdentity(String value) throws IOException {
        try {
            Method m = LocalStorageSecurity.class.getDeclaredMethod("validateIdentity", String.class);
            m.setAccessible(true);
            m.invoke(null, value);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("validateIdentity accepte un identifiant normal")
    void validateIdentityAcceptsNormalUsername() {
        assertDoesNotThrow(() -> callValidateIdentity("DOMAIN\\user"));
        assertDoesNotThrow(() -> callValidateIdentity("alice"));
        assertDoesNotThrow(() -> callValidateIdentity("LAPTOP-ABC\\Admin"));
    }

    @Test
    @DisplayName("validateIdentity rejette une valeur nulle")
    void validateIdentityRejectsNull() {
        IOException ex = assertThrows(IOException.class, () -> callValidateIdentity(null));
        assertTrue(ex.getMessage().contains("vide"));
    }

    @Test
    @DisplayName("validateIdentity rejette une valeur vide ou espace")
    void validateIdentityRejectsBlank() {
        assertThrows(IOException.class, () -> callValidateIdentity(""));
        assertThrows(IOException.class, () -> callValidateIdentity("   "));
    }

    @Test
    @DisplayName("validateIdentity rejette les caracteres d'injection shell")
    void validateIdentityRejectsShellMetacharacters() {
        for (char c : new char[]{'&', '|', ';', '<', '>', '(', ')', '{', '}', '[', ']', '$', '`', '"', '\''}) {
            String value = "user" + c + "injected";
            IOException ex = assertThrows(IOException.class,
                    () -> callValidateIdentity(value),
                    "Le caractere '" + c + "' devrait etre rejete");
            assertTrue(ex.getMessage().contains("caractere non autorise"));
        }
    }

    @Test
    @DisplayName("validateIdentity rejette les caracteres de controle")
    void validateIdentityRejectsControlCharacters() {
        assertThrows(IOException.class, () -> callValidateIdentity("user\ninjected"));
        assertThrows(IOException.class, () -> callValidateIdentity("user\rinjected"));
        assertThrows(IOException.class, () -> callValidateIdentity("user\u0000injected"));
        assertThrows(IOException.class, () -> callValidateIdentity("user\u001finjected"));
    }
}
