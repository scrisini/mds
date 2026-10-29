package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafeguardsTest {

    /** Everything production mode asks for. */
    private static final Map<String, String> SAFE = Map.ofEntries(
            Map.entry("mds.mode", "production"),
            Map.entry("mds.ssm.path", "/mds/prod/"),
            Map.entry("mds.vault.address", "https://vault.internal:8200"),
            Map.entry("mds.influx.token", "from-vault"),
            Map.entry("logging.structured.format.console", "ecs"),
            Map.entry("mds.stream.allowed-origins", "https://app.example.com"),
            Map.entry("mds.stream.allow-unthrottled", "false"),
            Map.entry("springdoc.api-docs.enabled", "false"),
            Map.entry("springdoc.swagger-ui.enabled", "false"));

    @Test
    void developmentModeAllowsLocalDefaults() {
        assertThat(ProductionSafeguards.violations(binder(Map.of("mds.influx.token", "mds-dev-token")))).isEmpty();
    }

    @Test
    void productionModeAcceptsSafeSettings() {
        assertThat(ProductionSafeguards.violations(binder(SAFE))).isEmpty();
    }

    @Test
    void productionModeNamesEveryUnsafeSetting() {
        List<String> violations = ProductionSafeguards.violations(binder(Map.of(
                "mds.mode", "PRODUCTION",
                "mds.influx.token", "mds-dev-token")));

        assertThat(violations).hasSize(7).anySatisfy(v -> assertThat(v).startsWith("mds.ssm.path"))
                .anySatisfy(v -> assertThat(v).startsWith("mds.vault.address"))
                .anySatisfy(v -> assertThat(v).startsWith("mds.influx.token"))
                .anySatisfy(v -> assertThat(v).contains("MDS_LOG_FORMAT=ecs"))
                .anySatisfy(v -> assertThat(v).startsWith("mds.stream.allowed-origins"))
                .anySatisfy(v -> assertThat(v).startsWith("mds.stream.allow-unthrottled"))
                .anySatisfy(v -> assertThat(v).contains("MDS_API_DOCS_ENABLED=false"));
        assertThat(String.join(" ", violations)).doesNotContain("mds-dev-token");
    }

    @Test
    void productionModeRefusesTheApiDocsOrSwaggerUi() {
        for (String key : List.of("springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled")) {
            Map<String, String> settings = new HashMap<>(SAFE);
            settings.put(key, "true");

            assertThat(ProductionSafeguards.violations(binder(settings))).as(key)
                    .singleElement().asString().startsWith("springdoc.api-docs.enabled");
        }
    }

    @Test
    void productionModeRefusesToStartWithUnsafeSettings() {
        Map<String, String> unsafe = new HashMap<>(SAFE);
        unsafe.put("mds.stream.allowed-origins", "*");
        MockEnvironment environment = new MockEnvironment();
        unsafe.forEach(environment::setProperty);

        assertThatThrownBy(() -> new ProductionSafeguards(environment))
                .hasMessageContaining("MDS-1015")
                .hasMessageContaining("mds.stream.allowed-origins")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("from-vault"));
    }

    @Test
    void productionModeStartsWithSafeSettings() {
        MockEnvironment environment = new MockEnvironment();
        SAFE.forEach(environment::setProperty);

        new ProductionSafeguards(environment);
    }

    private static Binder binder(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties));
    }
}
