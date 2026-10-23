package com.mds.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Vault that knows one key/value v2 secret, a root token, a Kubernetes role and an AppRole, on
 * 127.0.0.1 over plain http. Records tokens that were revoked.
 */
public final class FakeVault implements AutoCloseable {

    public static final String ROOT_TOKEN = "root-token";
    public static final String LOGIN_TOKEN = "login-token";
    public static final String KUBERNETES_JWT = "pod-jwt";
    public static final String SECRET_ID = "the-secret-id";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Map<String, Object> secret;
    public final List<String> revoked = new CopyOnWriteArrayList<>();
    public final List<String> namespaces = new CopyOnWriteArrayList<>();

    /** Serves {@code secret} at {@code secret/data/<path>} for any path. */
    public FakeVault(Map<String, Object> secret) throws IOException {
        this.secret = secret;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String token = exchange.getRequestHeaders().getFirst("X-Vault-Token");
        String namespace = exchange.getRequestHeaders().getFirst("X-Vault-Namespace");
        if (namespace != null) {
            namespaces.add(namespace);
        }
        String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = JSON.readTree(text.isBlank() ? "{}" : text);
        if (path.equals("/v1/auth/kubernetes/login")) {
            boolean valid = body.path("role").asString("").equals("mds")
                    && body.path("jwt").asString("").equals(KUBERNETES_JWT);
            reply(exchange, valid ? 200 : 403, valid ? Map.of("auth", Map.of("client_token", LOGIN_TOKEN)) : denied());
        } else if (path.equals("/v1/auth/approle/login")) {
            boolean valid = body.path("role_id").asString("").equals("mds-role")
                    && body.path("secret_id").asString("").equals(SECRET_ID);
            reply(exchange, valid ? 200 : 403, valid ? Map.of("auth", Map.of("client_token", LOGIN_TOKEN)) : denied());
        } else if (path.equals("/v1/auth/token/revoke-self")) {
            revoked.add(token);
            reply(exchange, 204, null);
        } else if (path.startsWith("/v1/secret/data/")) {
            boolean valid = ROOT_TOKEN.equals(token) || LOGIN_TOKEN.equals(token);
            reply(exchange, valid ? 200 : 403,
                    valid ? Map.of("data", Map.of("data", secret, "metadata", Map.of())) : denied());
        } else {
            reply(exchange, 404, Map.of("errors", List.of()));
        }
    }

    private static Map<String, Object> denied() {
        return Map.of("errors", List.of("permission denied"));
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
