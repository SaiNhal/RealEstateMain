package com.nexstruct;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** A dependency-free Java 21 web application for NEXSTRUCT. */
public final class NexstructApplication {
    private static final int DEFAULT_PORT = 8080;
    private static final String SESSION_COOKIE = "NEXSTRUCT_ADMIN_SESSION";
    private static final long SESSION_LIFETIME_MS = 8 * 60 * 60 * 1000L;
    private static final int MAX_BODY_BYTES = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();
    private static final Object ENQUIRY_LOCK = new Object();
    private static Path enquiriesFile;
    private static String adminUsername;
    private static byte[] adminPassword;
    private static boolean production;
    private static boolean enquiryStorageEnabled;

    private NexstructApplication() { }

    public static void main(String[] args) throws IOException {
        int port = args.length == 1 ? parsePort(args[0]) : DEFAULT_PORT;
        Path projectRoot = Path.of(System.getProperty("user.dir")).toRealPath();
        Path publicRoot = projectRoot.resolve("public").toRealPath();
        if (!Files.isRegularFile(publicRoot.resolve("index.html"))) {
            throw new IllegalStateException("Missing public/index.html. Run the application from the project root.");
        }
        configureApplication();
        if (enquiryStorageEnabled) {
            Path dataDirectory = projectRoot.resolve(".data");
            Files.createDirectories(dataDirectory);
            enquiriesFile = dataDirectory.resolve("enquiries.db");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/enquiries", NexstructApplication::handleEnquiries);
        server.createContext("/api/public-settings", NexstructApplication::handlePublicSettings);
        server.createContext("/api/auth/login", NexstructApplication::handleLogin);
        server.createContext("/api/auth/logout", NexstructApplication::handleLogout);
        server.createContext("/api/auth/session", NexstructApplication::handleSession);
        server.createContext("/api/admin/enquiries", NexstructApplication::handleAdminLeadApi);
        server.createContext("/api/admin/export", NexstructApplication::handleExport);
        server.createContext("/", exchange -> serveStatic(exchange, publicRoot));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("NEXSTRUCT is running at http://localhost:%d/ (enquiry storage: %s)%n", port,
                enquiryStorageEnabled ? "enabled" : "disabled");
    }

    private static void configureApplication() {
        enquiryStorageEnabled = !"false".equalsIgnoreCase(System.getenv("NEXSTRUCT_ENQUIRY_STORAGE_ENABLED"));
        production = "production".equalsIgnoreCase(System.getenv("APP_ENV"));
        if (!enquiryStorageEnabled) return;

        adminUsername = System.getenv("NEXSTRUCT_ADMIN_USERNAME");
        String password = System.getenv("NEXSTRUCT_ADMIN_PASSWORD");
        if (adminUsername == null || adminUsername.isBlank() || password == null || password.length() < 12) {
            throw new IllegalStateException("Set NEXSTRUCT_ADMIN_USERNAME and a 12+ character NEXSTRUCT_ADMIN_PASSWORD before starting the server.");
        }
        adminPassword = password.getBytes(StandardCharsets.UTF_8);
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
    }

    private static void handleEnquiries(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }

        Map<String, String> form = parseForm(readRequestBody(exchange));
        String now = Instant.now().toString();
        Enquiry enquiry = new Enquiry(newLeadId(), now, now, clean(form.get("name"), 120), clean(form.get("phone"), 40),
                clean(form.get("email"), 160), clean(form.get("location"), 180),
                clean(form.get("projectType"), 100), clean(form.get("budget"), 80), clean(form.get("timeline"), 80),
                clean(form.get("description"), 4000), "New", "");
        if (enquiry.name().isBlank() || enquiry.phone().length() < 8 || enquiry.projectType().isBlank() || enquiry.description().isBlank()) {
            sendJson(exchange, 400, "{\"message\":\"Please complete the required project details.\"}");
            return;
        }
        if (!enquiryStorageEnabled) {
            sendJson(exchange, 202, "{\"message\":\"Your enquiry is ready to send on WhatsApp.\",\"stored\":false}");
            return;
        }
        saveEnquiry(enquiry);
        System.out.printf("[%s] New enquiry: %s | %s | %s%n", enquiry.createdAt(), enquiry.name(), enquiry.phone(), enquiry.projectType());
        sendJson(exchange, 201, "{\"message\":\"Thank you. Your project enquiry has been received.\",\"stored\":true}");
    }

    private static void handlePublicSettings(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        sendJson(exchange, 200, "{\"whatsappBusinessNumber\":\"\"}");
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (!enquiryStorageEnabled) { storageUnavailable(exchange); return; }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { methodNotAllowed(exchange, "POST"); return; }
        Map<String, String> form = parseForm(readRequestBody(exchange));
        byte[] suppliedUser = clean(form.get("username"), 120).getBytes(StandardCharsets.UTF_8);
        byte[] suppliedPassword = clean(form.get("password"), 512).getBytes(StandardCharsets.UTF_8);
        boolean valid = MessageDigest.isEqual(adminUsername.getBytes(StandardCharsets.UTF_8), suppliedUser)
                & MessageDigest.isEqual(adminPassword, suppliedPassword);
        if (!valid) { sendJson(exchange, 401, "{\"message\":\"Invalid username or password.\"}"); return; }
        String token = newSession();
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token + "; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax" + (production ? "; Secure" : ""));
        sendJson(exchange, 200, "{\"message\":\"Signed in successfully.\"}");
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        if (!enquiryStorageEnabled) { storageUnavailable(exchange); return; }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { methodNotAllowed(exchange, "POST"); return; }
        String token = sessionToken(exchange);
        if (token != null) SESSIONS.remove(token);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + (production ? "; Secure" : ""));
        sendJson(exchange, 200, "{\"message\":\"Signed out.\"}");
    }

    private static void handleSession(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { methodNotAllowed(exchange, "GET"); return; }
        if (!enquiryStorageEnabled) { sendJson(exchange, 200, "{\"authenticated\":false,\"storageEnabled\":false}"); return; }
        sendJson(exchange, 200, "{\"authenticated\":" + isAuthenticated(exchange) + "}");
    }

    private static void handleAdminLeadApi(HttpExchange exchange) throws IOException {
        if (!enquiryStorageEnabled) { storageUnavailable(exchange); return; }
        if (!isAuthenticated(exchange)) { sendJson(exchange, 401, "{\"message\":\"Please sign in to continue.\"}"); return; }
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/admin/enquiries/";
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.equals("/api/admin/enquiries")) {
            Map<String, String> filters = parseForm(exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
            List<Enquiry> enquiries = readEnquiries(filters.get("status"), filters.get("q"));
            StringBuilder json = new StringBuilder("{\"enquiries\":[");
            for (int i = 0; i < enquiries.size(); i++) { if (i > 0) json.append(','); json.append(enquiries.get(i).toJson()); }
            sendJson(exchange, 200, json.append("]}").toString());
            return;
        }
        if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod()) && path.startsWith(prefix)) {
            String id = path.substring(prefix.length());
            Map<String, String> update = parseForm(readRequestBody(exchange));
            String status = clean(update.get("status"), 30);
            String note = clean(update.get("note"), 4000);
            if (!List.of("New", "Contacted", "Qualified", "Won", "Lost").contains(status)) { sendJson(exchange, 400, "{\"message\":\"Choose a valid lead status.\"}"); return; }
            Enquiry lead = updateEnquiry(id, status, note);
            if (lead == null) { sendJson(exchange, 404, "{\"message\":\"Lead not found.\"}"); return; }
            sendJson(exchange, 200, "{\"message\":\"Lead updated.\",\"enquiry\":" + lead.toJson() + "}");
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod()) && path.startsWith(prefix)) {
            String id = path.substring(prefix.length());
            if (id.isBlank() || id.contains("/")) { sendJson(exchange, 400, "{\"message\":\"Choose a valid lead.\"}"); return; }
            if (!deleteEnquiry(id)) { sendJson(exchange, 404, "{\"message\":\"Lead not found.\"}"); return; }
            sendJson(exchange, 200, "{\"message\":\"Lead permanently deleted.\"}");
            return;
        }
        methodNotAllowed(exchange, "GET, PATCH, DELETE");
    }

    private static void handleExport(HttpExchange exchange) throws IOException {
        if (!enquiryStorageEnabled) { storageUnavailable(exchange); return; }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { methodNotAllowed(exchange, "GET"); return; }
        if (!isAuthenticated(exchange)) { sendJson(exchange, 401, "{\"message\":\"Please sign in to continue.\"}"); return; }
        List<Enquiry> enquiries = readEnquiries();
        StringBuilder csv = new StringBuilder("ID,Submitted,Status,Name,Phone,Email,Location,Project Type,Budget,Timeline,Project Brief,Internal Notes\\n");
        for (Enquiry lead : enquiries) csv.append(lead.toCsvRow());
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=nexstruct-leads.csv");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(content); }
    }

    private static void storageUnavailable(HttpExchange exchange) throws IOException {
        sendJson(exchange, 503, "{\"message\":\"Enquiry storage is disabled for this deployment.\"}");
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new IOException("Request is too large");
        return new String(body, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            if (!key.isBlank()) values.put(key, value);
        }
        return values;
    }

    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private static String clean(String value, int maximum) { return value == null ? "" : value.trim().substring(0, Math.min(value.trim().length(), maximum)); }

    private static void saveEnquiry(Enquiry enquiry) throws IOException {
        synchronized (ENQUIRY_LOCK) {
            List<String> lines = Files.exists(enquiriesFile) ? Files.readAllLines(enquiriesFile, StandardCharsets.UTF_8) : new ArrayList<>();
            lines.add(enquiry.toStorageLine());
            Path temporary = enquiriesFile.resolveSibling("enquiries.db.tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try { Files.move(temporary, enquiriesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, enquiriesFile, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    private static List<Enquiry> readEnquiries() throws IOException { return readEnquiries("", ""); }

    private static List<Enquiry> readEnquiries(String status, String search) throws IOException {
        synchronized (ENQUIRY_LOCK) {
            if (!Files.exists(enquiriesFile)) return List.of();
            List<Enquiry> enquiries = new ArrayList<>();
            for (String line : Files.readAllLines(enquiriesFile, StandardCharsets.UTF_8)) {
                try { enquiries.add(Enquiry.fromStorageLine(line)); } catch (IllegalArgumentException ignored) { /* skip malformed rows */ }
            }
            String safeStatus = status == null ? "" : status.trim();
            String query = search == null ? "" : search.trim().toLowerCase();
            enquiries.removeIf(lead -> (!safeStatus.isBlank() && !lead.status().equalsIgnoreCase(safeStatus))
                    || (!query.isBlank() && !(lead.name() + " " + lead.phone() + " " + lead.email() + " " + lead.projectType() + " " + lead.location()).toLowerCase().contains(query)));
            enquiries.sort(Comparator.comparing(Enquiry::createdAt).reversed());
            return enquiries;
        }
    }

    private static Enquiry updateEnquiry(String id, String status, String note) throws IOException {
        synchronized (ENQUIRY_LOCK) {
            if (!Files.exists(enquiriesFile)) return null;
            List<Enquiry> leads = new ArrayList<>();
            Enquiry updated = null;
            for (String line : Files.readAllLines(enquiriesFile, StandardCharsets.UTF_8)) {
                Enquiry lead;
                try { lead = Enquiry.fromStorageLine(line); } catch (IllegalArgumentException ignored) { continue; }
                if (lead.id().equals(id)) {
                    String nextNotes = appendNote(lead.notes(), note);
                    lead = lead.withManagement(status, nextNotes, Instant.now().toString());
                    updated = lead;
                }
                leads.add(lead);
            }
            if (updated == null) return null;
            writeEnquiries(leads);
            return updated;
        }
    }

    private static boolean deleteEnquiry(String id) throws IOException {
        synchronized (ENQUIRY_LOCK) {
            if (!Files.exists(enquiriesFile)) return false;
            List<Enquiry> leads = new ArrayList<>();
            boolean deleted = false;
            for (String line : Files.readAllLines(enquiriesFile, StandardCharsets.UTF_8)) {
                Enquiry lead;
                try { lead = Enquiry.fromStorageLine(line); } catch (IllegalArgumentException ignored) { continue; }
                if (lead.id().equals(id)) { deleted = true; continue; }
                leads.add(lead);
            }
            if (deleted) writeEnquiries(leads);
            return deleted;
        }
    }

    private static void writeEnquiries(List<Enquiry> leads) throws IOException {
        List<String> lines = leads.stream().map(Enquiry::toStorageLine).toList();
        Path temporary = enquiriesFile.resolveSibling("enquiries.db.tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try { Files.move(temporary, enquiriesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, enquiriesFile, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String appendNote(String history, String note) {
        if (note == null || note.isBlank()) return history;
        String entry = Instant.now() + " | " + note.replace("\r", " ").replace("\n", " ").trim();
        return history == null || history.isBlank() ? entry : history + "\n---\n" + entry;
    }

    private static String newSession() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SESSIONS.put(token, System.currentTimeMillis() + SESSION_LIFETIME_MS);
        return token;
    }

    private static String newLeadId() {
        byte[] bytes = new byte[9]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isAuthenticated(HttpExchange exchange) {
        String token = sessionToken(exchange);
        if (token == null) return false;
        Long expiresAt = SESSIONS.get(token);
        if (expiresAt == null || expiresAt < System.currentTimeMillis()) { SESSIONS.remove(token); return false; }
        return true;
    }

    private static String sessionToken(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(SESSION_COOKIE + "=")) return trimmed.substring((SESSION_COOKIE + "=").length());
        }
        return null;
    }

    private static void serveStatic(HttpExchange exchange, Path publicRoot) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) { methodNotAllowed(exchange, "GET, HEAD"); return; }
        String requestPath = exchange.getRequestURI().getPath();
        String fileName = requestPath.equals("/") ? "index.html"
                : requestPath.equals("/admin") ? "admin.html" : requestPath.substring(1);
        Path requested = publicRoot.resolve(fileName).normalize();
        if (!requested.startsWith(publicRoot) || !Files.isRegularFile(requested)) { sendText(exchange, 404, "Not found"); return; }
        byte[] content = Files.readAllBytes(requested);
        exchange.getResponseHeaders().set("Content-Type", contentType(requested));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' https: data:; media-src 'self' https:; style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(200, exchange.getRequestMethod().equalsIgnoreCase("HEAD") ? -1 : content.length);
        if (!exchange.getRequestMethod().equalsIgnoreCase("HEAD")) try (OutputStream output = exchange.getResponseBody()) { output.write(content); } else exchange.close();
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException { exchange.getResponseHeaders().set("Allow", allowed); sendText(exchange, 405, "Method not allowed"); }
    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException { exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); send(exchange, status, "application/json; charset=utf-8", body); }
    private static void sendText(HttpExchange exchange, int status, String body) throws IOException { send(exchange, status, "text/plain; charset=utf-8", body); }
    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } }
    private static String contentType(Path file) { String name = file.getFileName().toString().toLowerCase(); if (name.endsWith(".html")) return "text/html; charset=utf-8"; if (name.endsWith(".css")) return "text/css; charset=utf-8"; if (name.endsWith(".js")) return "application/javascript; charset=utf-8"; if (name.endsWith(".svg")) return "image/svg+xml"; return "application/octet-stream"; }

    private record Enquiry(String id, String createdAt, String updatedAt, String name, String phone, String email, String location, String projectType, String budget, String timeline, String description, String status, String notes) {
        private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
        private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
        String toStorageLine() { return String.join("|", encode(id), encode(createdAt), encode(updatedAt), encode(name), encode(phone), encode(email), encode(location), encode(projectType), encode(budget), encode(timeline), encode(description), encode(status), encode(notes)); }
        static Enquiry fromStorageLine(String line) {
            String[] values = line.split("\\|", -1);
            if (values.length == 7) {
                for (int i = 0; i < values.length; i++) values[i] = decode(values[i]);
                String legacyId = "legacy-" + Integer.toUnsignedString(line.hashCode(), 36);
                return new Enquiry(legacyId, values[0], values[0], values[1], values[2], values[3], values[4], values[5], "", "", values[6], "New", "");
            }
            if (values.length != 13) throw new IllegalArgumentException();
            for (int i = 0; i < values.length; i++) values[i] = decode(values[i]);
            return new Enquiry(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10], values[11], values[12]);
        }
        Enquiry withManagement(String nextStatus, String nextNotes, String nextUpdatedAt) { return new Enquiry(id, createdAt, nextUpdatedAt, name, phone, email, location, projectType, budget, timeline, description, nextStatus, nextNotes); }
        String toJson() { return "{\"id\":\"" + json(id) + "\",\"createdAt\":\"" + json(createdAt) + "\",\"updatedAt\":\"" + json(updatedAt) + "\",\"name\":\"" + json(name) + "\",\"phone\":\"" + json(phone) + "\",\"email\":\"" + json(email) + "\",\"location\":\"" + json(location) + "\",\"projectType\":\"" + json(projectType) + "\",\"budget\":\"" + json(budget) + "\",\"timeline\":\"" + json(timeline) + "\",\"description\":\"" + json(description) + "\",\"status\":\"" + json(status) + "\",\"notes\":\"" + json(notes) + "\"}"; }
        String toCsvRow() { return String.join(",", csv(id), csv(createdAt), csv(status), csv(name), csv(phone), csv(email), csv(location), csv(projectType), csv(budget), csv(timeline), csv(description), csv(notes)) + "\n"; }
        private static String encode(String value) { return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
        private static String decode(String value) { return new String(DECODER.decode(value), StandardCharsets.UTF_8); }
        private static String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
        private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    }
}
