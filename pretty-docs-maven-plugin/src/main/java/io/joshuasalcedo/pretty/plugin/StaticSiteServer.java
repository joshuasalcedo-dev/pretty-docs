package io.joshuasalcedo.pretty.plugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal read-only HTTP server for a generated site directory (JDK built-in server, no dependencies).
 */
public final class StaticSiteServer implements AutoCloseable {

    /** Default port: high enough to avoid common dev servers (3000, 5173, 8080). */
    public static final int DEFAULT_PORT = 4400;

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff2", "font/woff2");

    private final Path root;
    private final HttpServer server;

    /**
     * Starts serving immediately.
     *
     * @throws IOException if the port is taken or the directory is missing
     */
    public StaticSiteServer(Path root, String host, int port) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.root.resolve("index.html"))) {
            throw new IOException("No site at " + this.root + " (index.html missing); run the generate goal first");
        }
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The actual port, useful when started with port 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "Method not allowed");
                return;
            }
            String path = URLDecoder.decode(URI.create(exchange.getRequestURI().getRawPath()).getPath(), StandardCharsets.UTF_8);
            Path file = root.resolve(path.replaceFirst("^/+", "")).normalize();
            if (!file.startsWith(root)) {
                send(exchange, 403, "Forbidden");
                return;
            }
            if (Files.isDirectory(file)) {
                file = file.resolve("index.html");
            }
            if (!Files.isRegularFile(file)) {
                send(exchange, 404, "Not found");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType(file));
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            long length = Files.size(file);
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, head ? -1 : length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    Files.copy(file, out);
                }
            }
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static void send(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
