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
import java.time.Duration;
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
        startWebUi();

        HttpResponse<String> indexResponse = httpClient.send(
                request("/")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> bootstrapResponse = httpClient.send(
                request("/api/ui/bootstrap")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, indexResponse.statusCode());
        assertTrue(indexResponse.body().contains("Crochet SFTP"));
        assertEquals(200, bootstrapResponse.statusCode());
        assertTrue(bootstrapResponse.body().contains("\"summary\""));
        assertTrue(bootstrapResponse.body().contains("\"sample.txt\""));
    }

    @Test
    @DisplayName("La facade sert les assets et gere les erreurs HTTP courantes")
    void servesAssetsAndHandlesErrors() throws Exception {
        startWebUi();

        HttpResponse<String> assetResponse = httpClient.send(
                request("/assets/app.js").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> methodNotAllowedResponse = httpClient.send(
                request("/api/ui/bootstrap")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> notFoundResponse = httpClient.send(
                request("/introuvable").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, assetResponse.statusCode());
        assertTrue(assetResponse.body().contains("fetch(\"/api/ui/bootstrap\")"));
        assertEquals(405, methodNotAllowedResponse.statusCode());
        assertTrue(methodNotAllowedResponse.body().contains("Methode non supportee"));
        assertEquals(404, notFoundResponse.statusCode());
        assertTrue(notFoundResponse.body().contains("Ressource introuvable"));
    }

    @Test
    @DisplayName("La facade accepte un login valide et refuse un login invalide")
    void handlesLoginRequests() throws Exception {
        startWebUi();

        HttpResponse<String> validLoginResponse = httpClient.send(
                request("/api/ui/login")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"user\",\"password\":\"password\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> invalidLoginResponse = httpClient.send(
                request("/api/ui/login")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"user\",\"password\":\"wrong\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, validLoginResponse.statusCode());
        assertTrue(validLoginResponse.body().contains("\"ok\":true"));
        assertTrue(validLoginResponse.body().contains("\"username\":\"user\""));
        assertEquals(401, invalidLoginResponse.statusCode());
        assertTrue(invalidLoginResponse.body().contains("\"ok\":false"));
    }

    private void startWebUi() throws Exception {
        rootDir = Files.createTempDirectory("sftp-webui-");
        Files.createDirectories(rootDir.resolve("user"));
        Files.writeString(rootDir.resolve("user").resolve("sample.txt"), "demo");

        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", rootDir.toString(), "0"},
                Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + webUiServer.getPort() + path))
                .timeout(Duration.ofSeconds(5));
    }
}
