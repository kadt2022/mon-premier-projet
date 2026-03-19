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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("isRunning et getPort fonctionnent avant le demarrage")
    void isRunningAndPortBeforeStart() throws Exception {
        rootDir = Files.createTempDirectory("sftp-webui-pre-");
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", rootDir.toString(), "0"}, Map.of());
        webUiServer = new WebUiServer(8765, rootDir, settings, new AuditTrail(10), () -> 2222);

        assertFalse(webUiServer.isRunning());
        assertEquals(8765, webUiServer.getPort());
    }

    @Test
    @DisplayName("isRunning retourne true quand le serveur est demarre")
    void isRunningTrueAfterStart() throws Exception {
        startWebUi();
        assertTrue(webUiServer.isRunning());
    }

    @Test
    @DisplayName("Un second appel a stop() ne lance pas d'exception")
    void stopTwiceIsNoOp() throws Exception {
        startWebUi();
        webUiServer.stop();
        assertDoesNotThrow(() -> webUiServer.stop());
        webUiServer = null;
    }

    @Test
    @DisplayName("handleIndex rejette les methodes non-GET")
    void handleIndexRejectsNonGet() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                request("/").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
        assertTrue(resp.body().contains("Methode non supportee"));
    }

    @Test
    @DisplayName("handleAsset rejette les methodes non-GET")
    void handleAssetRejectsNonGet() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                request("/assets/app.js").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    @Test
    @DisplayName("handleLogin rejette les methodes non-POST")
    void handleLoginRejectsGet() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                request("/api/ui/login").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    @Test
    @DisplayName("handleAsset rejette un chemin vide")
    void handleAssetRejectsBlankPath() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                request("/assets/").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    @DisplayName("handleAsset rejette une tentative de traversee de chemin")
    void handleAssetRejectsPathTraversal() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + webUiServer.getPort() + "/assets/../etc/passwd"))
                        .timeout(Duration.ofSeconds(5))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    @DisplayName("Les assets CSS sont servis avec le bon Content-Type")
    void servesStylesheetWithCssContentType() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                request("/assets/styles.css").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type")
                .orElse("").contains("text/css"));
    }

    @Test
    @DisplayName("Un asset SVG inexistant retourne 404 avec le bon type detecte")
    void nonExistentSvgAssetReturns404() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                request("/assets/icon.svg").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    @DisplayName("Un asset de type inconnu retourne 404")
    void nonExistentUnknownTypeAssetReturns404() throws Exception {
        startWebUi();
        HttpResponse<String> resp = httpClient.send(
                request("/assets/data.xyz").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    @DisplayName("Bootstrap avec un rootDir inexistant retourne un explorateur vide")
    void bootstrapWithNonExistentRootDir() throws Exception {
        rootDir = Path.of(System.getProperty("java.io.tmpdir"),
                "sftp-noexist-" + System.nanoTime());
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", rootDir.toString(), "0"}, Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();

        HttpResponse<String> resp = httpClient.send(
                request("/api/ui/bootstrap").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"entries\":[]"));
    }

    @Test
    @DisplayName("Bootstrap trie les entrees de l'explorateur alphabetiquement")
    void bootstrapSortsExplorerEntries() throws Exception {
        rootDir = Files.createTempDirectory("sftp-webui-sort-");
        Files.createDirectories(rootDir.resolve("admin"));
        Files.createDirectories(rootDir.resolve("user"));
        Files.writeString(rootDir.resolve("user").resolve("alpha.txt"), "a");
        Files.writeString(rootDir.resolve("user").resolve("beta.txt"), "b");

        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", rootDir.toString(), "0"}, Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();

        HttpResponse<String> resp = httpClient.send(
                request("/api/ui/bootstrap").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertTrue(body.contains("\"admin\"") && body.contains("\"user\""));
        assertTrue(body.indexOf("\"admin\"") < body.indexOf("\"user\""));
    }

    @Test
    @DisplayName("Bootstrap avec mode PASSWORD affiche la bonne methode d'auth")
    void adminPayloadShowsPasswordMode() throws Exception {
        rootDir = Files.createTempDirectory("sftp-webui-pwd-");
        Files.createDirectories(rootDir.resolve("user"));
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"password", "2222", rootDir.toString(), "0"}, Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();

        HttpResponse<String> resp = httpClient.send(
                request("/api/ui/bootstrap").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Mot de passe"));
    }

    @Test
    @DisplayName("Bootstrap avec mode PUBLIC_KEY affiche la bonne methode d'auth")
    void adminPayloadShowsPublicKeyMode() throws Exception {
        rootDir = Files.createTempDirectory("sftp-webui-pk-");
        Files.createDirectories(rootDir.resolve("user"));
        // Creer un authorized_keys pour couvrir la branche "Present"
        Path sshDir = rootDir.resolve("user").resolve(".ssh");
        Files.createDirectories(sshDir);
        Files.writeString(sshDir.resolve("authorized_keys"), "ssh-rsa AAAA test");
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"pubkey", "2222", rootDir.toString(), "0"}, Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();

        HttpResponse<String> resp = httpClient.send(
                request("/api/ui/bootstrap").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Cle publique"));
        assertTrue(resp.body().contains("Present"));
    }

    @Test
    @DisplayName("Bootstrap avec mode BOTH affiche la bonne methode d'auth")
    void adminPayloadShowsBothMode() throws Exception {
        rootDir = Files.createTempDirectory("sftp-webui-both-");
        Files.createDirectories(rootDir.resolve("user"));
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", rootDir.toString(), "0"}, Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();

        HttpResponse<String> resp = httpClient.send(
                request("/api/ui/bootstrap").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Mot de passe + cle"));
    }

    @Test
    @DisplayName("Bootstrap gere l'IOException de l'explorateur quand rootDir est un fichier")
    void bootstrapHandlesExplorerIoExceptionWhenRootIsFile() throws Exception {
        rootDir = Files.createTempFile("sftp-webui-file-root-", ".tmp");
        SftpRuntimeSettings settings = SftpRuntimeSettings.from(
                new String[]{"both", "2222", rootDir.toString(), "0"}, Map.of());
        AuditTrail auditTrail = new AuditTrail(50);
        webUiServer = new WebUiServer(0, rootDir, settings, auditTrail, () -> 2222);
        webUiServer.start();

        HttpResponse<String> resp = httpClient.send(
                request("/api/ui/bootstrap").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"entries\":[]"));
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
