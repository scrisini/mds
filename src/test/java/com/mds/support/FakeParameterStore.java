package com.mds.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * An SSM endpoint on 127.0.0.1 that answers {@code GetParametersByPath} with {@link #parameters}
 * (full parameter names to values), in one page, whatever the path. Tests may change the parameters
 * between calls. The AWS SDK needs credentials: set {@code aws.accessKeyId} and
 * {@code aws.secretAccessKey} system properties.
 */
public final class FakeParameterStore implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public final Map<String, String> parameters = Collections.synchronizedMap(new LinkedHashMap<>());
    /** The {@code X-Amz-Target} of each call, e.g. {@code AmazonSSM.GetParametersByPath}. */
    public final List<String> targets = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public FakeParameterStore(Map<String, String> parameters) throws IOException {
        this.parameters.putAll(parameters);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        targets.add(exchange.getRequestHeaders().getFirst("X-Amz-Target"));
        List<Map<String, String>> page = new ArrayList<>();
        synchronized (parameters) {
            parameters.forEach((name, value) -> page.add(Map.of("Name", name, "Value", value, "Type", "String")));
        }
        byte[] body = JSON.writeValueAsBytes(Map.of("Parameters", page));
        exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.1");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
