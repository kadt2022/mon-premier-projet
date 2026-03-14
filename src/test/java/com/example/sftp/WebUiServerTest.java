package com.example.sftp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUiServerTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private WebUiServer webUiServer;
    private Path rootDir;

    @AfterEach
    void tearDown() throws IOException {
        if (webUiServer != null) {
            webUiServer.stop();
        }
        if (rootDir != null && Files.exists(rootDir)) {
            try (var stream = Files.walk(rootDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("La facade web expose l'index et le bootstrap JSON")
    void servesIndexAndBootstrap() throws Exception {
        rootDir = Files.createTempDirectory("sftp-webui-");
        Files.createDirectories(rootDir.resolve("user"));
        Files.writeString(rootDir.resolve("user").resolve("sample.txt"), "demo");

        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", rootDir.toString(), "0"},
                Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();

        HttpResponse<String> indexResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + webUiServer.getPort() + "/"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> bootstrapResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + webUiServer.getPort() + "/api/ui/bootstrap"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, indexResponse.statusCode());
        assertTrue(indexResponse.body().contains("Crochet SFTP"));
        assertEquals(200, bootstrapResponse.statusCode());
        assertTrue(bootstrapResponse.body().contains("\"summary\""));
        assertTrue(bootstrapResponse.body().contains("\"sample.txt\""));
    }
}
