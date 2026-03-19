package com.example.sftp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
