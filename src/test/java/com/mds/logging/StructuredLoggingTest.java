package com.mds.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mds.exchange.MarketStreamManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The logging standard end to end, in ECS JSON mode: a caller's request id reaches the coded log
 * line and comes back in the error response, and every line on stdout is JSON for log processors.
 * InfluxDB is unreachable, so startup also logs the coded candle-reload failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "logging.structured.format.console=ecs",
        "mds.pairs=BTC/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1"})
@ExtendWith(OutputCaptureExtension.class)
@Import(StructuredLoggingTest.FailingController.class)
class StructuredLoggingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An endpoint that fails the way an unhandled bug would. */
    @RestController
    static class FailingController {
        @GetMapping("/test/fail")
        String fail() {
            throw new IllegalStateException("something nobody expected");
        }
    }

    @LocalServerPort
    private int port;

    /**
     * Spring Boot configures logging once per JVM, so a context another test class started first
     * would leave text logging in place for this one. Resetting the logging system makes this
     * context configure it as ECS JSON, and afterwards lets the next context configure its own.
     */
    @BeforeAll
    static void reconfigureLoggingForThisContext() {
        resetLogging();
    }

    @AfterAll
    static void reconfigureLoggingForTheNextContext() {
        resetLogging();
    }

    private static void resetLogging() {
        LoggingSystem.get(StructuredLoggingTest.class.getClassLoader()).cleanUp();
    }

    @MockitoBean
    private MarketStreamManager streams;

    @Test
    void anUnexpectedFailureIsCodedTracedToTheRequestAndEveryLineIsJson(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/test/fail", "support-ticket-7");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue(RequestIds.HEADER)).contains("support-ticket-7");
        assertThat(JSON.readValue(response.body(), Map.class)).containsEntry("requestId", "support-ticket-7");

        // The test framework logs while bootstrapping, before this context configures logging;
        // from the application's first line on, every line must be JSON
        List<Map<String, Object>> lines = output.getOut().lines()
                .filter(line -> !line.isBlank())
                .dropWhile(line -> !line.startsWith("{"))
                .map(StructuredLoggingTest::parseFlattened)
                .toList();

        Map<String, Object> failure = withCode(lines, LogEvent.UNEXPECTED_ERROR.code());
        assertThat(failure)
                .containsEntry("log.level", "ERROR")
                .containsEntry("http.request.id", "support-ticket-7")
                .containsEntry("event.reference", LogEvent.UNEXPECTED_ERROR.reference())
                .containsEntry("mds.path", "/test/fail")
                .containsEntry("error.type", IllegalStateException.class.getName());
        assertThat((String) failure.get("message")).startsWith("[" + LogEvent.UNEXPECTED_ERROR.code() + "] ");

        assertThat(withCode(lines, LogEvent.SERVICE_STARTED.code())).containsEntry("mds.port", port);
        assertThat(withCode(lines, LogEvent.DECIMAL_PRECISION.code())).containsEntry("mds.scale", 8);
        assertThat(withCode(lines, LogEvent.CANDLE_RELOAD_FAILED.code())).containsKey("error.type");
    }

    @Test
    void springsOwnProblemsCarryTheRequestId() throws Exception {
        HttpResponse<String> unknown = get("/api/v1/nothing-here", "bad id with spaces");

        assertThat(unknown.statusCode()).isEqualTo(404);
        String generated = unknown.headers().firstValue(RequestIds.HEADER).orElseThrow();
        assertThat(generated).isNotEqualTo("bad id with spaces").matches("[0-9a-f-]{36}");
        assertThat(JSON.readValue(unknown.body(), Map.class)).containsEntry("requestId", generated);
    }

    private HttpResponse<String> get(String path, String requestId) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header(RequestIds.HEADER, requestId).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> withCode(List<Map<String, Object>> lines, String code) {
        return lines.stream().filter(line -> code.equals(line.get("event.code"))).findFirst()
                .orElseThrow(() -> new AssertionError("no log line with event.code " + code));
    }

    /** Parses one stdout line; fails if it isn't JSON. Nested ECS objects become dotted keys. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFlattened(String line) {
        Map<String, Object> flat = new HashMap<>();
        flatten("", JSON.readValue(line, Map.class), flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> json, Map<String, Object> flat) {
        json.forEach((key, value) -> {
            String name = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                flatten(name, (Map<String, Object>) nested, flat);
            } else {
                flat.put(name, value);
            }
        });
    }
}
