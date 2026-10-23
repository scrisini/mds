package com.mds.configdata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The few Vault HTTP API calls mds needs: log in, read one key/value secret, revoke the login token.
 * Error messages carry Vault's own error text and HTTP status, never a token or a secret value.
 */
final class VaultClient {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final VaultSettings settings;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    VaultClient(VaultSettings settings) {
        this.settings = settings;
        requireSecureAddress(settings.address());
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(settings.timeout());
        if (!settings.caCert().isEmpty()) {
            builder.sslContext(trusting(Path.of(settings.caCert())));
        }
        this.http = builder.build();
    }

    /** Logs in, reads the secret as flat properties, then revokes a token it logged in for. */
    Map<String, Object> readSecret() {
        String token = login();
        try {
            return read(token);
        } finally {
            if (settings.auth() != VaultSettings.Auth.TOKEN) {
                revoke(token);
            }
        }
    }

    private String login() {
        return switch (settings.auth()) {
            case TOKEN -> require(settings.token(), "mds.vault.token");
            case KUBERNETES -> loginWith(settings.kubernetes().mount(), Map.of(
                    "role", require(settings.kubernetes().role(), "mds.vault.kubernetes.role"),
                    "jwt", readFile(settings.kubernetes().tokenFile())));
            case APP_ROLE -> loginWith(settings.appRole().mount(), Map.of(
                    "role_id", require(settings.appRole().roleId(), "mds.vault.app-role.role-id"),
                    "secret_id", readFile(Path.of(require(settings.appRole().secretIdFile(),
                            "mds.vault.app-role.secret-id-file")))));
        };
    }

    private String loginWith(String mount, Map<String, String> credentials) {
        JsonNode response = send("POST", "/v1/auth/" + mount + "/login", null, credentials, "login");
        String token = response.path("auth").path("client_token").asString("");
        if (token.isEmpty()) {
            throw new IllegalStateException("Vault login at auth/" + mount + " returned no token");
        }
        return token;
    }

    private Map<String, Object> read(String token) {
        boolean versioned = settings.kvVersion() == 2;
        String location = settings.kvMount() + (versioned ? "/data/" : "/") + settings.path();
        JsonNode response = send("GET", "/v1/" + location, token, null, "read of " + location);
        JsonNode data = versioned ? response.path("data").path("data") : response.path("data");
        if (!data.isObject()) {
            throw new IllegalStateException("Vault secret " + location + " has no data");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        flatten("", data, properties);
        return properties;
    }

    private void revoke(String token) {
        try {
            send("POST", "/v1/auth/token/revoke-self", token, null, "token revocation");
        } catch (RuntimeException e) {
            // the token expires on its own; startup doesn't depend on revoking it
        }
    }

    private JsonNode send(String method, String path, String token, Object body, String operation) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.address() + path))
                .timeout(settings.timeout())
                .header("Accept", "application/json");
        if (token != null) {
            request.header("X-Vault-Token", token);
        }
        if (!settings.namespace().isEmpty()) {
            request.header("X-Vault-Namespace", settings.namespace());
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Vault %s failed: HTTP %d%s"
                        .formatted(operation, response.statusCode(), errors(response.body())));
            }
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Vault %s failed: %s".formatted(operation, e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vault " + operation + " was interrupted", e);
        }
    }

    /** Vault's {@code errors} array, e.g. {@code : permission denied}; empty if the body has none. */
    private String errors(String body) {
        try {
            StringJoiner messages = new StringJoiner("; ", ": ", "").setEmptyValue("");
            json.readTree(body).path("errors").forEach(error -> messages.add(error.asString()));
            return messages.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Nested objects become dotted names; arrays become comma-separated lists. */
    private static void flatten(String prefix, JsonNode node, Map<String, Object> properties) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                flatten(prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), field.getValue(),
                        properties);
            }
        } else if (node.isArray()) {
            StringJoiner list = new StringJoiner(",");
            node.forEach(element -> list.add(element.asString()));
            properties.put(prefix, list.toString());
        } else {
            properties.put(prefix, node.asString());
        }
    }

    /** Plain http would send the token and the secrets in clear text; only allowed to this machine. */
    static void requireSecureAddress(String address) {
        URI uri = URI.create(address);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && LOOPBACK.contains(uri.getHost());
        if (!https && !localHttp) {
            throw new IllegalArgumentException(
                    "mds.vault.address must use https (http only for localhost), was " + address);
        }
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must be set for mds.vault.auth");
        }
        return value;
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static SSLContext trusting(Path caCert) {
        try (InputStream in = Files.newInputStream(caCert)) {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int i = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trust.setCertificateEntry("vault-ca-" + i++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot use mds.vault.ca-cert " + caCert + ": " + e.getMessage(), e);
        }
    }
}
