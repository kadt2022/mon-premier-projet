package com.example.sftp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lightweight demo web UI for the SFTP server.
 */
public class WebUiServer {

    private static final Logger log = LoggerFactory.getLogger(WebUiServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final int requestedPort;
    private final Path rootDir;
    private final SftpRuntimeSettings runtimeSettings;
    private final AuditTrail auditTrail;
    private final IntSupplier sftpPortSupplier;

    private HttpServer httpServer;
    private int boundPort = -1;

    public WebUiServer(
            int requestedPort,
            Path rootDir,
            SftpRuntimeSettings runtimeSettings,
            AuditTrail auditTrail,
            IntSupplier sftpPortSupplier) {
        this.requestedPort = requestedPort;
        this.rootDir = rootDir;
        this.runtimeSettings = runtimeSettings;
        this.auditTrail = auditTrail;
        this.sftpPortSupplier = sftpPortSupplier;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(6));
        httpServer.createContext("/api/ui/bootstrap", this::handleBootstrap);
        httpServer.createContext("/api/ui/login", this::handleLogin);
        httpServer.createContext("/assets/", this::handleAsset);
        httpServer.createContext("/", this::handleIndex);
        httpServer.start();

        boundPort = httpServer.getAddress().getPort();
        log.info("Interface web de demonstration disponible sur http://localhost:{}", boundPort);
        auditTrail.record(
                "system",
                "WEB",
                "WEB_UI_STARTED",
                "http://localhost:" + boundPort,
                "SUCCESS",
                "Facade web initialisee");
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            log.info("Interface web arretee.");
            auditTrail.record(
                    "system",
                    "WEB",
                    "WEB_UI_STOPPED",
                    rootDir.toString(),
                    "SUCCESS",
                    "Facade web arretee");
        }
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    public int getPort() {
        return boundPort > 0 ? boundPort : requestedPort;
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendNotFound(exchange);
            return;
        }

        sendResource(exchange, "webui/index.html", "text/html; charset=UTF-8");
    }

    private void handleAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        String requestPath = exchange.getRequestURI().getPath();
        String assetPath = requestPath.substring("/assets/".length());
        if (assetPath.isBlank() || assetPath.contains("..")) {
            sendNotFound(exchange);
            return;
        }

        sendResource(exchange, "webui/" + assetPath, guessContentType(assetPath));
    }

    private void handleBootstrap(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branding", Map.of(
                "title", "Crochet SFTP",
                "tagline", "Le pont de commandement du coffre de transfert",
                "subtitle", "Une maquette vivante pour visualiser le produit avant les vraies API metier."));
        payload.put("summary", buildSummary());
        payload.put("explorer", Map.of("entries", buildExplorerEntries()));
        payload.put("admin", buildAdminPayload());
        payload.put("audit", Map.of(
                "count", auditTrail.size(),
                "events", auditTrail.snapshot().stream().map(this::toAuditMap).toList()));
        payload.put("roadmap", List.of(
                Map.of("title", "API de droits", "detail", "Persisting roles, groups and entitlements by tenant."),
                Map.of("title", "Audit des transferts", "detail", "Capturing uploads, downloads and refusals at file level."),
                Map.of("title", "Sessions web", "detail", "Replacing demo login with real authentication and permissions.")));

        sendJson(exchange, 200, payload);
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.readValue(exchange.getRequestBody(), Map.class);
        String username = Objects.toString(payload.get("username"), "").trim();
        String password = Objects.toString(payload.get("password"), "").trim();

        String expected = runtimeSettings.users().get(username);
        boolean ok = expected != null && expected.equals(password);
        auditTrail.record(
                username,
                "WEB",
                "UI_LOGIN",
                "/login",
                ok ? "SUCCESS" : "DENIED",
                ok ? "Connexion interface acceptee" : "Identifiants inconnus");

        if (!ok) {
            sendJson(exchange, 401, Map.of(
                    "ok", false,
                    "message", "Identifiants invalides pour la maquette."));
            return;
        }

        sendJson(exchange, 200, Map.of(
                "ok", true,
                "username", username,
                "role", deriveRole(username),
                "home", rootDir.resolve(username).toString(),
                "mode", runtimeSettings.authMode().name().toLowerCase(Locale.ROOT)));
    }

    private Map<String, Object> buildSummary() {
        StorageSnapshot snapshot = collectStorageSnapshot();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sftpPort", sftpPortSupplier.getAsInt());
        summary.put("webPort", getPort());
        summary.put("authMode", runtimeSettings.authMode().name().toLowerCase(Locale.ROOT));
        summary.put("storageRoot", rootDir.toString());
        summary.put("usersCount", runtimeSettings.users().size());
        summary.put("filesCount", snapshot.filesCount());
        summary.put("foldersCount", snapshot.directoriesCount());
        summary.put("auditCount", auditTrail.size());
        summary.put("updatedAt", Instant.now().toString());
        summary.put("cards", List.of(
                metricCard("Port SFTP", String.valueOf(sftpPortSupplier.getAsInt()), "Canal de transfert actif"),
                metricCard("Port web", String.valueOf(getPort()), "Facade de demo ouverte"),
                metricCard("Stockage", snapshot.filesCount() + " fichiers", snapshot.directoriesCount() + " dossiers analyses"),
                metricCard("Audit", String.valueOf(auditTrail.size()), "Evenements memoires disponibles")));
        return summary;
    }

    private List<Map<String, Object>> buildExplorerEntries() {
        if (!Files.exists(rootDir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(rootDir)) {
            return stream
                    .sorted(pathComparator())
                    .map(path -> describePath(path, 0, 3))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            auditTrail.record(
                    "system",
                    "WEB",
                    "EXPLORER_SCAN",
                    rootDir.toString(),
                    "ERROR",
                    e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildAdminPayload() {
        List<Map<String, Object>> users = new ArrayList<>();
        for (String username : runtimeSettings.users().keySet()) {
            Path userHome = rootDir.resolve(username);
            Path authorizedKeys = userHome.resolve(".ssh").resolve("authorized_keys");

            Map<String, Object> user = new LinkedHashMap<>();
            user.put("username", username);
            user.put("role", deriveRole(username));
            user.put("group", deriveGroup(username));
            user.put("rights", deriveRights(username));
            user.put("home", userHome.toString());
            user.put("auth", switch (runtimeSettings.authMode()) {
                case PASSWORD -> "Mot de passe";
                case PUBLIC_KEY -> "Cle publique";
                case BOTH -> "Mot de passe + cle";
            });
            user.put("status", Files.exists(userHome) ? "Provisionne" : "En attente");
            user.put("authorizedKeys", Files.exists(authorizedKeys) ? "Present" : "Absent");
            user.put("files", countDirectChildren(userHome));
            users.add(user);
        }

        return Map.of(
                "users", users,
                "groups", List.of(
                        Map.of("name", "Gouvernance", "purpose", "Controle des comptes et supervision"),
                        Map.of("name", "Transferts", "purpose", "Depots, retraits et traitements quotidiens")),
                "roles", List.of(
                        Map.of("name", "Administrateur", "coverage", "Pilotage, audit, gestion des acces"),
                        Map.of("name", "Operateur", "coverage", "Echange de fichiers et suivi des depots")));
    }

    private Map<String, Object> toAuditMap(AuditTrail.AuditEvent event) {
        return Map.of(
                "timestamp", event.timestamp(),
                "actor", event.actor(),
                "channel", event.channel(),
                "action", event.action(),
                "target", event.target(),
                "outcome", event.outcome(),
                "detail", event.detail());
    }

    private Map<String, Object> metricCard(String label, String value, String note) {
        return Map.of("label", label, "value", value, "note", note);
    }

    private Comparator<Path> pathComparator() {
        return Comparator
                .comparing((Path path) -> !Files.isDirectory(path))
                .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> describePath(Path path, int depth, int maxDepth) {
        Map<String, Object> node = new LinkedHashMap<>();
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            boolean directory = attributes.isDirectory();
            node.put("name", path.getFileName() == null ? path.toString() : path.getFileName().toString());
            node.put("path", rootDir.equals(path)
                    ? rootDir.getFileName().toString()
                    : rootDir.relativize(path).toString().replace('\\', '/'));
            node.put("kind", directory ? "directory" : "file");
            node.put("size", attributes.size());
            node.put("updatedAt", attributes.lastModifiedTime().toInstant().toString());
            node.put("owner", resolveOwner(path));
            node.put("hidden", isHidden(path));
            node.put("childrenCount", directory ? countDirectChildren(path) : 0L);
            node.put("children", directory && depth < maxDepth ? describeChildren(path, depth + 1, maxDepth) : List.of());
        } catch (IOException e) {
            node.put("name", path.getFileName() == null ? path.toString() : path.getFileName().toString());
            node.put("path", path.toString());
            node.put("kind", "error");
            node.put("size", 0L);
            node.put("updatedAt", Instant.now().toString());
            node.put("owner", "n/a");
            node.put("hidden", false);
            node.put("childrenCount", 0L);
            node.put("children", List.of());
            node.put("error", e.getMessage());
        }
        return node;
    }

    private List<Map<String, Object>> describeChildren(Path path, int depth, int maxDepth) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream
                    .sorted(pathComparator())
                    .limit(18)
                    .map(child -> describePath(child, depth, maxDepth))
                    .collect(Collectors.toList());
        }
    }

    private String resolveOwner(Path path) {
        try {
            UserPrincipal owner = Files.getOwner(path);
            return owner == null ? "n/a" : owner.getName();
        } catch (IOException e) {
            return "n/a";
        }
    }

    private boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
    }

    private long countDirectChildren(Path path) {
        if (!Files.isDirectory(path)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.list(path)) {
            return stream.count();
        } catch (IOException e) {
            return 0L;
        }
    }

    private StorageSnapshot collectStorageSnapshot() {
        if (!Files.exists(rootDir)) {
            return new StorageSnapshot(0, 0);
        }

        try (Stream<Path> stream = Files.walk(rootDir)) {
            long directories = 0;
            long files = 0;
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) {
                    directories++;
                } else {
                    files++;
                }
            }
            return new StorageSnapshot(Math.max(0, directories - 1), files);
        } catch (IOException e) {
            return new StorageSnapshot(0, 0);
        }
    }

    private String deriveRole(String username) {
        return username.equalsIgnoreCase("admin") ? "Administrateur" : "Operateur";
    }

    private String deriveGroup(String username) {
        return username.equalsIgnoreCase("admin") ? "Gouvernance" : "Transferts";
    }

    private List<String> deriveRights(String username) {
        if (username.equalsIgnoreCase("admin")) {
            return List.of("gerer utilisateurs", "voir audit", "consulter l'explorateur", "superviser depots");
        }
        return List.of("deposer", "telecharger", "lister son espace", "consulter ses traces");
    }

    private void sendResource(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        byte[] body;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                sendNotFound(exchange);
                return;
            }
            body = stream.readAllBytes();
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = mapper.writeValueAsBytes(payload);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=UTF-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private void sendNotFound(HttpExchange exchange) throws IOException {
        sendJson(exchange, 404, Map.of("ok", false, "message", "Ressource introuvable"));
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendJson(exchange, 405, Map.of("ok", false, "message", "Methode non supportee"));
    }

    private String guessContentType(String assetPath) {
        String lower = assetPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private record StorageSnapshot(long directoriesCount, long filesCount) {
    }
}
