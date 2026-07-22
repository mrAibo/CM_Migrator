/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.ibm.ecm.migration.AuthHandler;

/**
 * Wir nutzen den Embedded HTTP Server für CM-Migrator WebGUI
 * 
 * @since v2.2.0
 */
public class WebServer {
    private static final Logger logger = LogManager.getLogger(WebServer.class);
    
    private final int port;
    private final String webappDir;
    private HttpServer server;
    private ExecutorService executor;
    
    // Status Management
    private static final AtomicBoolean migrationRunning = new AtomicBoolean(false);
    private static final AtomicBoolean benchmarkRunning = new AtomicBoolean(false);
    private static final AtomicReference<MigrationStats> currentStats = new AtomicReference<>();
    private static final AtomicReference<ConfigAutoDetector.BenchmarkResults> lastBenchmark = new AtomicReference<>();
    private static final AtomicReference<Thread> migrationThread = new AtomicReference<>();

    // WebGUI process/profile management. The WebGUI must be usable before a
    // migration.properties file exists, so profiles are first-class objects.
    private static final String DEFAULT_CONFIG_FILE = "conf/migration.properties";
    private static final Path PROFILE_DIR = Paths.get("conf", "profiles");
    private static final int PROCESS_LOG_LIMIT = 400;
    private static final AtomicReference<ProcessState> currentProcess = new AtomicReference<>();
    private static final ConcurrentHashMap<String, ProcessState> processRegistry = new ConcurrentHashMap<>();
    private static final DateTimeFormatter RUN_ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final class ProcessState {
        final String runId;
        final String mode;
        final String profile;
        final String configFile;
        final String processUrl;
        final String dashboardUrl;
        final String migrationReportUrl;
        final String verificationReportUrl;
        final long startedAtMs;
        final List<String> logTail = new ArrayList<>();
        volatile String status = "STARTING";
        volatile String currentStep = "Starting";
        volatile long finishedAtMs = 0L;
        volatile String message = "";
        volatile Thread thread;

        ProcessState(String runId, String mode, String profile, String configFile,
                     String dashboardUrl, String migrationReportUrl, String verificationReportUrl) {
            this.runId = runId;
            this.mode = mode;
            this.profile = profile;
            this.configFile = configFile;
            this.processUrl = "/process.html?runId=" + runId;
            this.dashboardUrl = dashboardUrl;
            this.migrationReportUrl = migrationReportUrl;
            this.verificationReportUrl = verificationReportUrl;
            this.startedAtMs = System.currentTimeMillis();
            appendLog("Process created: mode=" + mode + ", profile=" + profile + ", config=" + configFile);
        }

        synchronized void appendLog(String line) {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logTail.add(ts + " " + line);
            while (logTail.size() > PROCESS_LOG_LIMIT) {
                logTail.remove(0);
            }
        }

        synchronized String logJson() {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < logTail.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append(jsonEscape(logTail.get(i))).append('"');
            }
            json.append(']');
            return json.toString();
        }
    }
    
    public WebServer(int port) {
        this(port, "./webapp");
    }
    
    public WebServer(int port, String webappDir) {
        this.port = port;
        this.webappDir = webappDir;
    }
    
    /**
     * Starte den web server
     */
    public void start() throws IOException {
        // Round 13B: bind to 127.0.0.1 by default; require explicit opt-in to
        // expose the WebGUI on all interfaces. Prevents accidental network
        // exposure of migration counters, config, and migration triggers.
        boolean bindAll = Boolean.parseBoolean(System.getProperty("cm.migrator.webgui.bindAll", "false"));
        String bindAddrProp = System.getProperty("cm.migrator.webgui.bindAddress");
        InetSocketAddress addr;
        if (bindAddrProp != null && !bindAddrProp.isEmpty()) {
            addr = new InetSocketAddress(bindAddrProp, port);
        } else if (bindAll) {
            addr = new InetSocketAddress(port);
            logger.warn("WebGUI bound to ALL interfaces (cm.migrator.webgui.bindAll=true). "
                    + "Ensure BasicAuth credentials are set and a firewall protects this port.");
        } else {
            addr = new InetSocketAddress("127.0.0.1", port);
        }
        Properties authConfig = loadAuthConfig();
        AuthHandler.validateConfiguration(authConfig);

        server = HttpServer.create(addr, 0);
        executor = Executors.newFixedThreadPool(10);
        server.setExecutor(executor);

        // Statische file Routen (GESCHÜTZT - braucht Auth für Dashboard)
        server.createContext("/", new AuthHandler(new StaticFileHandler(), authConfig));

        // Round 13B: /api/status liefert Migrationsdaten — jetzt ebenfalls
        // hinter AuthHandler, statt public. /api/health bleibt public, aber
        // minimal (siehe HealthHandler).
        server.createContext("/api/status", new AuthHandler(new StatusHandler(), authConfig));
        server.createContext("/api/health", new HealthHandler());

        // Geschütze API Routen (Auth erforderlich)
        server.createContext("/api/profiles", new AuthHandler(new ProfilesHandler(), authConfig));
        server.createContext("/api/config", new AuthHandler(new ConfigHandler(), authConfig));
        server.createContext("/api/operation", new AuthHandler(new OperationHandler(), authConfig));
        server.createContext("/api/process", new AuthHandler(new ProcessHandler(), authConfig));
        server.createContext("/api/benchmark", new AuthHandler(new BenchmarkHandler(), authConfig));
        server.createContext("/api/migration", new AuthHandler(new MigrationHandler(), authConfig));
        server.createContext("/api/delete", new AuthHandler(new DeleteHandler(), authConfig));
        server.createContext("/api/verify", new AuthHandler(new VerifyHandler(), authConfig));
        server.createContext("/api/itemtypes", new AuthHandler(new ItemTypesHandler(), authConfig));
        
        server.start();
        
        // Lese lokale IP-Addresse
        String localIP = getLocalIPAddress();
        
        logger.info("============================================");
        logger.info(" CM Migrator WebGUI v2.2");
        logger.info(" Server started on http://localhost:{}", port);
        if (localIP != null) {
            logger.info("                    http://{}:{}", localIP, port);
        }
        logger.info("============================================");
        
        System.out.println();
        System.out.println("🌐 WebGUI available at:");
        System.out.println("   • Local:   http://127.0.0.1:" + port);

        if (bindAddrProp != null && !bindAddrProp.isEmpty()) {
            System.out.println("   • Bound:   http://" + bindAddrProp + ":" + port);
        } else if (bindAll) {
            if (localIP != null) {
                System.out.println("   • Network: http://" + localIP + ":" + port);
            }
        } else {
            System.out.println("   • Network: not exposed; bound to localhost only");
            System.out.println("   • Enable:  CM_JAVA_OPTS=\"-Dcm.migrator.webgui.bindAll=true\" ./bin/webgui.sh --port " + port);
            if (localIP != null) {
                System.out.println("   • Or use SSH tunnel: ssh -L " + port + ":127.0.0.1:" + port + " " +
                        System.getProperty("user.name") + "@" + localIP);
            }
        }
        System.out.println();
    }
    
    /**
     * Stoppe den web server
     */
    public void stop() {
        if (server != null) {
            server.stop(2);
        }
        if (executor != null) {
            executor.shutdown();
        }
        logger.info("WebServer stopped");
    }

    public static void attachCurrentStats(MigrationStats stats) {
        currentStats.set(stats);
    }

    public static void clearCurrentStats() {
        currentStats.set(null);
    }
    
    // ========================================================================
    // STATIC FILE HANDLER
    // ========================================================================
    
    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            
            // Default to index.html
            if ("/".equals(path) || path.isEmpty()) {
                path = "/index.html";
            }
            
            // Security: HTTP 403 falls jemand rumstöbert 
            if (path.contains("..")) {
                sendError(exchange, 403, "Forbidden");
                return;
            }
            
            // Try to serve from webapp directory
            Path filePath = Paths.get(webappDir, path);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                String contentType = getContentType(path);
                byte[] content = Files.readAllBytes(filePath);
                
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } else if ("/index.html".equals(path)) {
                // Serve embedded dashboard if no external file exists
                serveEmbeddedDashboard(exchange);
            } else {
                sendError(exchange, 404, "Not Found");
            }
        }
        
        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".json")) return "application/json; charset=utf-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }
    
    // ========================================================================
    // PROFILE HANDLER
    // ========================================================================

    private class ProfilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            try {
                StringBuilder json = new StringBuilder("{\"profiles\":[");
                boolean first = true;

                // Legacy/default file first, if it exists.
                Path legacy = Paths.get(DEFAULT_CONFIG_FILE);
                if (Files.exists(legacy)) {
                    first = appendProfileJson(json, first, "migration", legacy.toString(), true);
                }

                // Then conf/*.properties, except migration.properties already listed.
                Path confDir = Paths.get("conf");
                if (Files.isDirectory(confDir)) {
                    try (java.util.stream.Stream<Path> stream = Files.list(confDir)) {
                        for (Path file : (Iterable<Path>) stream
                                .filter(p -> p.getFileName().toString().endsWith(".properties"))
                                .sorted()::iterator) {
                            if (file.normalize().toString().equals(legacy.normalize().toString())) continue;
                            String name = stripPropertiesSuffix(file.getFileName().toString());
                            first = appendProfileJson(json, first, name, file.toString(), false);
                        }
                    }
                }

                // Dedicated profile directory.
                if (Files.isDirectory(PROFILE_DIR)) {
                    try (java.util.stream.Stream<Path> stream = Files.list(PROFILE_DIR)) {
                        for (Path file : (Iterable<Path>) stream
                                .filter(p -> p.getFileName().toString().endsWith(".properties"))
                                .sorted()::iterator) {
                            String name = stripPropertiesSuffix(file.getFileName().toString());
                            first = appendProfileJson(json, first, name, file.toString(), false);
                        }
                    }
                }

                json.append("],\"defaultProfile\":\"migration\"}");
                sendJson(exchange, 200, json.toString());
            } catch (Exception e) {
                sendError(exchange, 500, "Error listing profiles: " + e.getMessage());
            }
        }

        private boolean appendProfileJson(StringBuilder json, boolean first, String name, String path, boolean legacy) {
            if (!first) json.append(',');
            json.append('{')
                .append("\"name\":\"").append(escapeJson(name)).append("\",")
                .append("\"path\":\"").append(escapeJson(path.replace('\\', '/'))).append("\",")
                .append("\"legacy\":").append(legacy)
                .append('}');
            return false;
        }
    }

    // ========================================================================
    // CONFIG HANDLER
    // ========================================================================
    
    private class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String profile = queryParam(exchange, "profile");
            String configFile = queryParam(exchange, "configFile");
            String selector = (configFile != null && !configFile.isEmpty()) ? configFile : profile;
            
            if ("GET".equals(method)) {
                try {
                    Path configPath = resolveConfigPath(selector, false);
                    Properties props = loadConfig(configPath);
                    String json = propertiesToJson(props);
                    if (json.endsWith("}")) {
                        json = json.substring(0, json.length() - 1)
                                + (props.isEmpty() ? "" : ",")
                                + "\"__configFile\":\"" + escapeJson(configPath.toString().replace('\\', '/')) + "\"}";
                    }
                    sendJson(exchange, 200, json);
                } catch (Exception e) {
                    sendError(exchange, 500, "Error loading config: " + e.getMessage());
                }
                
            } else if ("POST".equals(method)) {
                try {
                    String body = readRequestBody(exchange);
                    Properties patch = jsonToProperties(body);
                    String bodyProfile = patch.getProperty("__profile");
                    String bodyConfigFile = patch.getProperty("__configFile");
                    patch.remove("__profile");
                    patch.remove("__configFile");

                    if ((selector == null || selector.isEmpty()) && bodyConfigFile != null && !bodyConfigFile.isEmpty()) {
                        selector = bodyConfigFile;
                    } else if ((selector == null || selector.isEmpty()) && bodyProfile != null && !bodyProfile.isEmpty()) {
                        selector = bodyProfile;
                    }

                    Path configPath = resolveConfigPath(selector, true);
                    Properties props = loadConfig(configPath);

                    // Merge instead of replacing the file. This prevents the WebGUI from
                    // deleting advanced properties it does not yet expose as form fields.
                    for (String key : patch.stringPropertyNames()) {
                        String value = patch.getProperty(key);
                        if (value != null) props.setProperty(key, value);
                    }

                    saveConfig(configPath, props);
                    sendJson(exchange, 200, "{\"success\":true,\"message\":\"Configuration saved\",\"configFile\":\""
                            + escapeJson(configPath.toString().replace('\\', '/')) + "\"}");
                } catch (Exception e) {
                    sendError(exchange, 500, "Error saving config: " + e.getMessage());
                }
                
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
        
        private Properties loadConfig(Path configPath) throws IOException {
            Properties props = new Properties();
            if (Files.exists(configPath)) {
                try (InputStream is = Files.newInputStream(configPath)) {
                    props.load(is);
                }
            }
            return props;
        }
        
        private void saveConfig(Path configPath, Properties props) throws IOException {
            Path parent = configPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream os = Files.newOutputStream(configPath)) {
                props.store(os, "CM Migrator Configuration - Generated by WebGUI");
            }
            logger.info("Configuration saved via WebGUI: {}", configPath);
        }
    }
    

    // ========================================================================
    // OPERATION HANDLER (unified WebGUI run launcher)
    // ========================================================================

    private class OperationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equals(method) && path.equals("/api/operation/start")) {
                if (migrationRunning.get()) {
                    ProcessState active = currentProcess.get();
                    String activeUrl = active == null ? "" : active.processUrl;
                    sendJson(exchange, 409, "{\"error\":\"Operation already running\",\"processUrl\":\""
                            + escapeJson(activeUrl) + "\"}");
                    return;
                }

                try {
                    String body = readRequestBody(exchange);
                    Map<String, String> params = parseJsonParams(body);
                    String mode = params.getOrDefault("mode", "migration").trim().toLowerCase();
                    String profile = params.getOrDefault("profile", "").trim();
                    String configFileParam = params.getOrDefault("configFile", "").trim();

                    if (configFileParam.isEmpty()) {
                        sendJson(exchange, 400,
                                "{\"error\":\"No configFile provided. Select a concrete properties file before starting. Refusing to fall back to conf/migration.properties.\"}");
                        return;
                    }

                    Path configPath = resolveConfigPath(configFileParam, false);

                    if (!Files.exists(configPath)) {
                        sendJson(exchange, 400, "{\"error\":\"Configuration file not found\",\"configFile\":\""
                                + escapeJson(configPath.toString().replace('\\', '/')) + "\"}");
                        return;
                    }

                    Properties startProps = loadPropertiesFile(configPath);

                    boolean allowPasswordless = Boolean.parseBoolean(
                            startProps.getProperty("WEBGUI_ALLOW_PASSWORDLESS_CM_LOGIN", "false")
                    );

                    if (!allowPasswordless && !hasAnyPassword(startProps)) {
                        sendJson(exchange, 400,
                                "{\"error\":\"Configuration has no CM password. Set SOURCE_PASSWORD_CRYPT, SOURCE_PASSWORD, DEST_PASSWORD_CRYPT, DEST_PASSWORD, CONNECT_PASSWORD_CRYPT or CONNECT_PASSWORD before starting.\","
                                        + "\"configFile\":\"" + escapeJson(configPath.toString().replace('\\', '/')) + "\"}");
                        return;
                    }

                    String runId = makeRunId(mode, profile, configPath);
                    String host = requestHost(exchange);
                    int monitorPort = Integer.getInteger("cm.migrator.monitor.port", 8000);
                    String monitorHost = primaryIpOrHost();
                    String monitorBase = "http://" + monitorHost + ":" + monitorPort;

                    ProcessState state = new ProcessState(
                            runId,
                            mode,
                            profile == null || profile.isEmpty() ? stripPropertiesSuffix(configPath.getFileName().toString()) : profile,
                            configPath.toString().replace('\\', '/'),
                            monitorBase + "/status.html",
                            monitorBase + "/migration_report.html",
                            monitorBase + "/verification_report.html");

                    processRegistry.put(runId, state);
                    currentProcess.set(state);

                    Thread thread = new Thread(() -> runOperation(state, configPath, mode), "webgui-run-" + runId);
                    state.thread = thread;
                    migrationThread.set(thread);
                    thread.start();

                    String processAbsoluteUrl = "http://" + host + state.processUrl;
                    sendJson(exchange, 202,
                            "{\"success\":true"
                                    + ",\"runId\":\"" + escapeJson(runId) + "\""
                                    + ",\"processUrl\":\"" + escapeJson(state.processUrl) + "\""
                                    + ",\"processAbsoluteUrl\":\"" + escapeJson(processAbsoluteUrl) + "\""
                                    + ",\"dashboardUrl\":\"" + escapeJson(state.dashboardUrl) + "\""
                                    + ",\"migrationReportUrl\":\"" + escapeJson(state.migrationReportUrl) + "\""
                                    + ",\"verificationReportUrl\":\"" + escapeJson(state.verificationReportUrl) + "\"}");
                } catch (Exception e) {
                    logger.error("Could not start operation", e);
                    sendError(exchange, 500, "Could not start operation: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    private void runOperation(ProcessState state, Path configPath, String requestedMode) {
        ShutdownCoordinator.reset();
        migrationRunning.set(true);
        state.status = "RUNNING";
        state.currentStep = "Starting";
        state.appendLog("Starting operation");

        Path runConfig = null;
        boolean releaseRunSlot = true;
        try {
            runConfig = RunConfigSnapshot.create(
                    configPath,
                    requestedMode,
                    state.runId,
                    Paths.get("data", "webgui-runs"));
            String runConfigFile = runConfig.toString();
            String mode = requestedMode == null ? "migration" : requestedMode.toLowerCase();

            if ("safe".equals(mode)) {
                state.currentStep = "Step 1/2: migration";
                state.appendLog("SAFE workflow: migration started");
                Main.startMigration(runConfigFile);

                if (ShutdownCoordinator.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                    state.appendLog("SAFE workflow stopped after migration because shutdown was requested");
                    throw new InterruptedException("Safe workflow stopped after migration");
                }

                state.appendLog("SAFE workflow: migration completed");

                state.currentStep = "Step 2/2: verification";
                state.appendLog("SAFE workflow: verification started");
                Verifier.run(runConfigFile);
                state.appendLog("SAFE workflow: verification completed");
            } else if ("verify".equals(mode) || "verification".equals(mode)) {
                state.currentStep = "Verification";
                state.appendLog("Verification started");
                Verifier.run(runConfigFile);
                state.appendLog("Verification completed");
            } else if ("delete".equals(mode)) {
                state.currentStep = "Delete";
                state.appendLog("Delete started");
                Main.startMigration(runConfigFile);
                state.appendLog("Delete completed");
            } else {
                state.currentStep = "Migration";
                state.appendLog("Migration started");
                Main.startMigration(runConfigFile);
                state.appendLog("Migration completed");
            }

            state.status = "COMPLETED";
            state.message = "Operation completed";
        } catch (RunTerminationException e) {
            state.status = e.getWebStatus();
            state.message = webMessageFor(e.getReason());
            state.appendLog(state.message);
            releaseRunSlot = e.isTerminationConfirmed();
            logger.error("WebGUI operation terminated: reason={}, terminationConfirmed={}",
                    e.getReason(), e.isTerminationConfirmed(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.status = "INTERRUPTED";
            state.message = "Operation interrupted by operator request";
            state.appendLog(state.message);
            logger.error("WebGUI operation interrupted", e);
        } catch (Exception e) {
            state.status = "FAILED";
            state.message = "Operation failed; see server logs";
            state.appendLog(state.message);
            logger.error("WebGUI operation failed", e);
        } finally {
            try {
                RunConfigSnapshot.cleanupIfSafe(runConfig, releaseRunSlot);
            } catch (IOException cleanupError) {
                logger.error("Could not remove terminal WebGUI run snapshot", cleanupError);
                if ("COMPLETED".equals(state.status)) {
                    state.status = "FAILED";
                    state.message = "Operation completed but secure snapshot cleanup failed";
                    state.appendLog(state.message);
                }
            }
            state.finishedAtMs = System.currentTimeMillis();
            if (releaseRunSlot) {
                migrationRunning.set(false);
            } else {
                logger.warn("WebGUI run slot remains blocked because worker termination is unconfirmed.");
            }
        }
    }

    private static String webMessageFor(RunTerminationException.Reason reason) {
        switch (reason) {
            case POLICY:
                return "Operation refused by security policy";
            case TIMEOUT:
                return "Operation timed out";
            case INTERRUPTED:
                return "Operation interrupted by operator request";
            case FAILED:
            default:
                return "Operation failed; see server logs";
        }
    }

    // ========================================================================
    // PROCESS HANDLER
    // ========================================================================

    private class ProcessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String runId = queryParam(exchange, "runId");
            ProcessState state = resolveProcess(runId);

            if ("GET".equals(method) && (path.equals("/api/process") || path.equals("/api/process/current"))) {
                if (state == null) {
                    sendJson(exchange, 200, "{\"running\":false,\"process\":null}");
                } else {
                    sendJson(exchange, 200, processStateJson(state, true));
                }
                return;
            }

            if ("GET".equals(method) && path.equals("/api/process/log")) {
                if (state == null) {
                    sendError(exchange, 404, "Process not found");
                } else {
                    sendJson(exchange, 200, "{\"runId\":\"" + escapeJson(state.runId) + "\",\"log\":" + state.logJson() + "}");
                }
                return;
            }

            if ("POST".equals(method) && path.equals("/api/process/stop")) {
                if (state == null || state.thread == null || !state.thread.isAlive()) {
                    sendJson(exchange, 400, "{\"error\":\"No running process found\"}");
                    return;
                }
                state.status = "STOPPING";
                state.currentStep = "Stopping";
                state.appendLog("Stop requested by WebGUI");

                ShutdownCoordinator.requestShutdown();

                state.thread.interrupt();
                sendJson(exchange, 200, "{\"success\":true,\"message\":\"Graceful stop requested\"}");
                return;
            }

            sendError(exchange, 405, "Method Not Allowed");
        }
    }

    // ========================================================================
    // BENCHMARK HANDLER
    // ========================================================================
    
    private class BenchmarkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            
            if ("POST".equals(method) && path.equals("/api/benchmark")) {
                // Starte benchmark
                if (benchmarkRunning.get()) {
                    sendJson(exchange, 409, "{\"error\":\"Benchmark already running\"}");
                    return;
                }
                
                String body = readRequestBody(exchange);
                Map<String, String> params = parseJsonParams(body);
                
                // Run benchmark im Hintergrund
                benchmarkRunning.set(true);
                new Thread(() -> {
                    try {
                        ConfigAutoDetector detector = new ConfigAutoDetector();
                        ConfigAutoDetector.BenchmarkResults results;
                        
                        if (params.containsKey("quick") && "true".equals(params.get("quick"))) {
                            results = detector.runQuickBenchmark();
                        } else {
                            String source = params.get("sourceSSID");
                            String dest = params.get("destSSID");
                            String user = params.get("user");
                            String password = params.get("password");
                            results = detector.runFullBenchmark(source, dest, user, password);
                        }
                        
                        lastBenchmark.set(results);
                    } catch (Exception e) {
                        logger.error("Benchmark failed", e);
                    } finally {
                        benchmarkRunning.set(false);
                    }
                }).start();
                
                sendJson(exchange, 202, "{\"success\":true,\"message\":\"Benchmark started\"}");
                
            } else if ("GET".equals(method) && path.equals("/api/benchmark/status")) {
                // Return benchmark status/results
                ConfigAutoDetector.BenchmarkResults results = lastBenchmark.get();
                
                StringBuilder json = new StringBuilder("{");
                json.append("\"running\":").append(benchmarkRunning.get());
                
                if (results != null) {
                    json.append(",\"results\":{");
                    json.append("\"cpuCores\":").append(results.cpuCores);
                    json.append(",\"memoryMB\":").append(results.memoryMB);
                    json.append(",\"bottleneck\":\"").append(results.getBottleneck()).append("\"");
                    
                    if (results.sourceBench != null && results.sourceBench.success) {
                        json.append(",\"source\":{");
                        json.append("\"latencyMs\":").append(String.format("%.1f", results.sourceBench.latencyMs));
                        json.append(",\"throughputMBps\":").append(String.format("%.1f", results.sourceBench.throughputMBps));
                        json.append(",\"itemsPerSec\":").append(String.format("%.0f", results.sourceBench.itemsPerSec));
                        json.append("}");
                    }
                    
                    if (results.destBench != null && results.destBench.success) {
                        json.append(",\"dest\":{");
                        json.append("\"latencyMs\":").append(String.format("%.1f", results.destBench.latencyMs));
                        json.append(",\"throughputMBps\":").append(String.format("%.1f", results.destBench.throughputMBps));
                        json.append(",\"itemsPerSec\":").append(String.format("%.0f", results.destBench.itemsPerSec));
                        json.append("}");
                    }
                    
                    if (results.ioBench != null && results.ioBench.success) {
                        json.append(",\"io\":{");
                        json.append("\"diskWriteMBps\":").append(String.format("%.1f", results.ioBench.diskWriteMBps));
                        json.append(",\"diskReadMBps\":").append(String.format("%.1f", results.ioBench.diskReadMBps));
                        json.append(",\"h2InsertRowsPerSec\":").append(String.format("%.0f", results.ioBench.h2InsertRowsPerSec));
                        json.append("}");
                    }
                    
                    // Recommendation
                    ConfigAutoDetector detector = new ConfigAutoDetector();
                    String profile = detector.recommendProfile(results);
                    json.append(",\"recommendedProfile\":\"").append(profile).append("\"");
                    
                    json.append("}");
                }
                
                json.append("}");
                sendJson(exchange, 200, json.toString());
                
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }
    
    // ========================================================================
    // MIGRATION HANDLER
    // ========================================================================
    
    private class MigrationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            
            if ("POST".equals(method) && path.equals("/api/migration/start")) {
                sendJson(exchange, 410,
                        "{\"error\":\"Legacy endpoint disabled. Use /api/operation/start with mode=migration and explicit configFile.\"}");
                return;

            } else if ("POST".equals(method) && path.equals("/api/migration/stop")) {
                Thread thread = migrationThread.get();
                if (thread != null && thread.isAlive()) {
                    thread.interrupt();
                    sendJson(exchange, 200, "{\"success\":true,\"message\":\"Stop signal sent\"}");
                } else {
                    sendJson(exchange, 400, "{\"error\":\"No migration running\"}");
                }
                
            } else if ("GET".equals(method) && path.equals("/api/migration/status")) {
                MigrationStats stats = currentStats.get();
                StringBuilder json = new StringBuilder("{");
                json.append("\"running\":").append(migrationRunning.get());
                
                if (stats != null) {
                    long total = stats.getTotalItems();
                    long processed = stats.getProcessedItems();
                    double percent = total > 0 ? (100.0 * processed / total) : 0;
                    long elapsed = System.currentTimeMillis() - stats.getStartTime();
                    double speed = elapsed > 0 ? (processed * 1000.0 / elapsed) : 0;
                    
                    long remaining = total - processed;
                    long etaSeconds = speed > 0 ? (long)(remaining / speed) : 0;
                    
                    json.append(",\"stats\":{");
                    json.append("\"total\":").append(total);
                    json.append(",\"processed\":").append(processed);
                    json.append(",\"success\":").append(stats.getSuccessItems());
                    json.append(",\"failed\":").append(stats.getFailedItems());
                    json.append(",\"skipped\":").append(stats.getSkippedItems());
                    json.append(",\"deleted\":").append(stats.getDeletedItems());
                    json.append(",\"percent\":").append(String.format("%.1f", percent));
                    json.append(",\"speed\":").append(String.format("%.1f", speed));
                    json.append(",\"elapsedMs\":").append(elapsed);
                    json.append(",\"etaSeconds\":").append(etaSeconds);
                    json.append("}");
                }
                
                json.append("}");
                sendJson(exchange, 200, json.toString());
                
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }
    
    // ========================================================================
    // DELETE HANDLER
    // ========================================================================

    private class DeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equals(method) && path.equals("/api/delete/start")) {
                sendJson(exchange, 410,
                        "{\"error\":\"Legacy endpoint disabled. Use /api/operation/start with mode=delete and explicit configFile.\"}");
                return;
            }

            sendError(exchange, 405, "Method Not Allowed");
        }
    }

    // ========================================================================
    // VERIFY HANDLER
    // ========================================================================

    private class VerifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equals(method) && path.equals("/api/verify/start")) {
                sendJson(exchange, 410,
                        "{\"error\":\"Legacy endpoint disabled. Use /api/operation/start with mode=verification and explicit configFile.\"}");
                return;
            }

            sendError(exchange, 405, "Method Not Allowed");
        }
    }

    // ========================================================================
    // HEALTH HANDLER (Public health check Endpunkt)
    // ========================================================================
    
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Round 13B: minimal payload only. No version, no timestamp, no
            // migration state — anything more leaks info to unauth probes.
            sendJson(exchange, 200, "{\"ok\":true}");
        }
    }
    
    // ========================================================================
    // STATUS HANDLER (Einfacher polling Endpunkt)
    // ========================================================================
    
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            MigrationStats stats = currentStats.get();
            
            StringBuilder json = new StringBuilder("{");
            json.append("\"migrationRunning\":").append(migrationRunning.get());
            json.append(",\"benchmarkRunning\":").append(benchmarkRunning.get());
            
            if (stats != null && migrationRunning.get()) {
                long total = stats.getTotalItems();
                long processed = stats.getProcessedItems();
                double percent = total > 0 ? (100.0 * processed / total) : 0;
                
                json.append(",\"percent\":").append(String.format("%.1f", percent));
                json.append(",\"processed\":").append(processed);
                json.append(",\"total\":").append(total);
            }
            
            json.append(",\"timestamp\":").append(System.currentTimeMillis());
            json.append("}");
            
            sendJson(exchange, 200, json.toString());
        }
    }
    
    // ========================================================================
    // ITEMTYPE HANDLER
    // ========================================================================
    
    private class ItemTypesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Gebe erstmal Platzhalter aus
            String json = "{\"itemTypes\":[ " +
                    "{\"name\":\"Rechnung\",\"count\":0}," +
                    "{\"name\":\"Vertrag\",\"count\":0}," +
                    "{\"name\":\"Lieferschein\",\"count\":0}" +
                    "],\"note\":\"Connect to CM server to get real item types\"}";
            
            sendJson(exchange, 200, json);
        }
    }
    
    // ========================================================================
    // EMBEDDED DASHBOARD (Fallback if no webapp/index.html)
    // ========================================================================
    
    private void serveEmbeddedDashboard(HttpExchange exchange) throws IOException {
        String html = generateEmbeddedDashboard();
        
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private String generateEmbeddedDashboard() {
        
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>CM Migrator WebGUI</title>" +
                "<meta http-equiv='refresh' content='2'>" +
                "<style>" +
                "body{font-family:Inter,system-ui,sans-serif;margin:0;padding:20px;background:#f6f7fb}" +
                ".container{max-width:1200px;margin:0 auto}" +
                "h1{color:#0f172a;margin-bottom:20px}" +
                ".card{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:20px;margin-bottom:20px}" +
                ".status{display:inline-block;padding:4px 12px;border-radius:99px;font-size:12px;font-weight:700}" +
                ".status.running{background:#dcfce7;color:#16a34a}" +
                ".status.stopped{background:#fee2e2;color:#dc2626}" +
                "button{background:#f97316;color:#fff;border:none;padding:10px 20px;border-radius:8px;cursor:pointer;font-weight:600}" +
                "button:hover{background:#ea580c}" +
                "button.secondary{background:#64748b}" +
                "</style></head><body>" +
                "<div class='container'>" +
                "<h1>🚀 CM Migrator v2.2.1 - WebGUI</h1>" +
                "<div class='card'>" +
                "<h3>Status</h3>" +
                "<p>Migration: <span class='status " + (migrationRunning.get() ? "running'>RUNNING" : "stopped'>STOPPED") + "</span></p>" +
                "<p>Benchmark: <span class='status " + (benchmarkRunning.get() ? "running'>RUNNING" : "stopped'>IDLE") + "</span></p>" +
                "</div>" +
                "<div class='card'>" +
                "<h3>Actions</h3>" +
                "<p><em>Full dashboard loading... If this persists, ensure webapp/index.html exists.</em></p>" +
                "<p><a href='/api/config'>View Current Config (JSON)</a></p>" +
                "<p><a href='/api/migration/status'>View Migration Status (JSON)</a></p>" +
                "</div>" +
                "</div></body></html>";
    }
    

    // ========================================================================
    // PROFILE / PROCESS UTILITY METHODS
    // ========================================================================

    private Path resolveConfigPath(String selector, boolean forWrite) throws IOException {
        String raw = selector == null ? "" : selector.trim();
        if (raw.isEmpty() || "migration".equalsIgnoreCase(raw) || "default".equalsIgnoreCase(raw)) {
            return Paths.get(DEFAULT_CONFIG_FILE).normalize();
        }

        raw = raw.replace('\\', '/');
        if (raw.contains("..")) {
            throw new IOException("Invalid profile/config path: " + raw);
        }

        Path candidate;
        if (raw.startsWith("conf/")) {
            candidate = Paths.get(raw).normalize();
        } else if (raw.endsWith(".properties")) {
            Path inProfiles = PROFILE_DIR.resolve(raw).normalize();
            Path inConf = Paths.get("conf").resolve(raw).normalize();
            if (Files.exists(inProfiles) || forWrite) candidate = inProfiles;
            else candidate = inConf;
        } else {
            String clean = sanitizeProfileName(raw);
            Path inProfiles = PROFILE_DIR.resolve(clean + ".properties").normalize();
            Path inConf = Paths.get("conf").resolve(clean + ".properties").normalize();
            if (Files.exists(inProfiles) || forWrite) candidate = inProfiles;
            else candidate = inConf;
        }

        if (!candidate.startsWith(Paths.get("conf").normalize())) {
            throw new IOException("Config path must stay under conf/: " + candidate);
        }
        return candidate;
    }

    private Path createRunConfigSnapshot(Path sourceConfig, String mode, String runId) throws IOException {
        return RunConfigSnapshot.create(
                sourceConfig,
                mode,
                runId,
                Paths.get("data", "webgui-runs"));
    }

    private String processStateJson(ProcessState state, boolean includeLog) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"running\":").append(migrationRunning.get()).append(',');
        json.append("\"process\":{");
        json.append("\"runId\":\"").append(escapeJson(state.runId)).append("\",");
        json.append("\"mode\":\"").append(escapeJson(state.mode)).append("\",");
        json.append("\"profile\":\"").append(escapeJson(state.profile)).append("\",");
        json.append("\"configFile\":\"").append(escapeJson(state.configFile)).append("\",");
        json.append("\"status\":\"").append(escapeJson(state.status)).append("\",");
        json.append("\"currentStep\":\"").append(escapeJson(state.currentStep)).append("\",");
        json.append("\"message\":\"").append(escapeJson(state.message)).append("\",");
        json.append("\"startedAtMs\":").append(state.startedAtMs).append(',');
        json.append("\"finishedAtMs\":").append(state.finishedAtMs).append(',');
        json.append("\"processUrl\":\"").append(escapeJson(state.processUrl)).append("\",");
        json.append("\"dashboardUrl\":\"").append(escapeJson(state.dashboardUrl)).append("\",");
        json.append("\"migrationReportUrl\":\"").append(escapeJson(state.migrationReportUrl)).append("\",");
        json.append("\"verificationReportUrl\":\"").append(escapeJson(state.verificationReportUrl)).append("\"");

        MigrationStats stats = currentStats.get();
        if (stats != null) {
            long total = stats.getTotalItems();
            long processed = stats.getProcessedItems();
            double percent = total > 0 ? (100.0 * processed / total) : 0.0;
            long elapsed = System.currentTimeMillis() - stats.getStartTime();
            double speed = elapsed > 0 ? (processed * 1000.0 / elapsed) : 0.0;
            long remaining = Math.max(0L, total - processed);
            long etaSeconds = speed > 0 ? (long) (remaining / speed) : 0L;

            json.append(",\"stats\":{");
            json.append("\"total\":").append(total).append(',');
            json.append("\"processed\":").append(processed).append(',');
            json.append("\"success\":").append(stats.getSuccessItems()).append(',');
            json.append("\"failed\":").append(stats.getFailedItems()).append(',');
            json.append("\"skipped\":").append(stats.getSkippedItems()).append(',');
            json.append("\"deleted\":").append(stats.getDeletedItems()).append(',');
            json.append("\"percent\":").append(String.format(java.util.Locale.US, "%.1f", percent)).append(',');
            json.append("\"speed\":").append(String.format(java.util.Locale.US, "%.1f", speed)).append(',');
            json.append("\"elapsedMs\":").append(elapsed).append(',');
            json.append("\"etaSeconds\":").append(etaSeconds);
            json.append("}");
        }

        if (includeLog) json.append(",\"log\":").append(state.logJson());
        json.append("}}");
        return json.toString();
    }

    private ProcessState resolveProcess(String runId) {
        if (runId != null && !runId.trim().isEmpty()) {
            return processRegistry.get(runId.trim());
        }
        return currentProcess.get();
    }

    private String makeRunId(String mode, String profile, Path configPath) {
        String ts = LocalDateTime.now().format(RUN_ID_TS);
        String base = profile == null || profile.trim().isEmpty()
                ? stripPropertiesSuffix(configPath.getFileName().toString())
                : profile.trim();
        return ts + "-" + sanitizeProfileName(mode == null ? "migration" : mode) + "-" + sanitizeProfileName(base);
    }

    private static String sanitizeProfileName(String value) {
        if (value == null || value.trim().isEmpty()) return "default";
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.endsWith(".properties")) cleaned = stripPropertiesSuffix(cleaned);
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    private static String stripPropertiesSuffix(String name) {
        if (name == null) return "";
        return name.endsWith(".properties") ? name.substring(0, name.length() - ".properties".length()) : name;
    }

    private String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String k = urlDecode(kv[0]);
            if (key.equals(k)) {
                return kv.length > 1 ? urlDecode(kv[1]) : "";
            }
        }
        return null;
    }

    private String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private String requestHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !host.trim().isEmpty()) return host.trim();
        return "localhost:" + port;
    }

    private String primaryIpOrHost() {
        String ip = getLocalIPAddress();
        return ip == null || ip.trim().isEmpty() ? "localhost" : ip.trim();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================
    

    private Properties loadPropertiesFile(Path path) throws IOException {
        Properties props = new Properties();

        if (path != null && Files.exists(path)) {
            try (InputStream is = Files.newInputStream(path)) {
                props.load(is);
            }
        }

        return props;
    }

    private boolean hasAnyPassword(Properties props) {
        if (props == null) return false;

        return hasText(props.getProperty("SOURCE_PASSWORD_CRYPT"))
                || hasText(props.getProperty("SOURCE_PASSWORD"))
                || hasText(props.getProperty("DEST_PASSWORD_CRYPT"))
                || hasText(props.getProperty("DEST_PASSWORD"))
                || hasText(props.getProperty("CONNECT_PASSWORD_CRYPT"))
                || hasText(props.getProperty("CONNECT_PASSWORD"));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Lädt die Authentifizierungskonfig aus migration.properties.
     */
    private Properties loadAuthConfig() {
        Properties props = new Properties();
    
        Path webguiConfigPath = Paths.get("conf/webgui.properties");
        Path fallbackConfigPath = Paths.get("conf/migration.properties");
    
        Path configPath = Files.exists(webguiConfigPath) ? webguiConfigPath : fallbackConfigPath;
    
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
                logger.info("Loaded WebGUI auth config from {}", configPath);
            } catch (IOException e) {
                logger.warn("Konnte WebGUI-Auth-Konfiguration nicht laden: {}", e.getMessage());
            }
        } else {
            logger.warn("No WebGUI auth config found. Expected conf/webgui.properties or conf/migration.properties");
        }
    
        return props;
    }
    
    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        sendJson(exchange, code, "{\"error\":\"" + escapeJson(message) + "\"}");
    }
    
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
    
    private String propertiesToJson(Properties props) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (String key : props.stringPropertyNames()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escapeJson(key)).append("\":\"")
                .append(escapeJson(props.getProperty(key))).append("\"");
        }
        json.append("}");
        return json.toString();
    }
    
    private Properties jsonToProperties(String json) {
        Properties props = new Properties();
        // Einfacher JSON parser key-value Objekte
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
        
        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replaceAll("^\"|\"$", "");
                String value = kv[1].trim().replaceAll("^\"|\"$", "");
                if (!key.isEmpty()) {
                    props.setProperty(key, value);
                }
            }
        }
        return props;
    }
    
    private Map<String, String> parseJsonParams(String json) {
        Map<String, String> map = new HashMap<>();
        Properties props = jsonToProperties(json);
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        return map;
    }
    
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    
    /**
     * Lese lokale IP-Adresse
     */
    private String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    // Prefer IPv4 and site-local addresses
                    if (addr.isSiteLocalAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
            // Fallback to any non-loopback address
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            logger.debug("Could not determine local IP: {}", e.getMessage());
            return null;
        }
    }
    
    // ========================================================================
    // MAIN METHOD
    // ========================================================================
    
    public static void main(String[] args) {
        int port = 8080;
        
        // Parse port aus args
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
            }
        }
        
        try {
            // Starte webserver
            WebServer server = new WebServer(port);
            server.start();
            
            // Füge shutdown hook hinzu
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            
            // Lasse den Server weiterlaufen
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.error("WebServer failed to start", e);
            System.exit(1);
        }
    }
}
