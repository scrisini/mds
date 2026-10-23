package com.mds.config;

import java.util.ArrayList;
import java.util.List;

import com.mds.logging.LogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production mode ({@code mds.mode=production}) refuses settings that are fine on a laptop but unsafe
 * in production. Checked when the instance starts, which any violation stops
 * ({@link LogEvent#PRODUCTION_SETTINGS_UNSAFE}), and by {@code ConfigCheck} against the stored
 * configuration a restart would read. Outside production mode nothing is checked.
 */
@Slf4j
@Component
public class ProductionSafeguards {

    /** {@code application.yml}'s token for the local InfluxDB; deployed instances get theirs from Vault. */
    static final String DEVELOPMENT_INFLUX_TOKEN = "mds-dev-token";

    public ProductionSafeguards(Environment environment) {
        List<String> violations = violations(Binder.get(environment));
        if (!violations.isEmpty()) {
            LogEvent.PRODUCTION_SETTINGS_UNSAFE.at(log).with("violations", violations).log();
            throw new IllegalStateException("%s: %s".formatted(
                    LogEvent.PRODUCTION_SETTINGS_UNSAFE.message(), String.join("; ", violations)));
        }
    }

    /**
     * What production mode refuses in these settings, each naming the setting to change; empty when
     * they're safe or the mode isn't production. Names settings, never their values.
     */
    public static List<String> violations(Binder binder) {
        MdsProperties mds = binder.bindOrCreate("mds", MdsProperties.class);
        if (mds.mode() != MdsProperties.Mode.PRODUCTION) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        if (binder.bind("mds.ssm.path", String.class).orElse("").isBlank()) {
            violations.add("mds.ssm.path is not set: production settings come from SSM Parameter Store (MDS_SSM_PATH)");
        }
        if (binder.bind("mds.vault.address", String.class).orElse("").isBlank()) {
            violations.add("mds.vault.address is not set: production secrets come from Vault (MDS_VAULT_ADDRESS)");
        }
        String influxToken = binder.bind("mds.influx.token", String.class).orElse("");
        if (influxToken.isBlank() || influxToken.equals(DEVELOPMENT_INFLUX_TOKEN)) {
            violations.add("mds.influx.token is the development token: Vault must supply the production one");
        }
        if (!"ecs".equals(binder.bind("logging.structured.format.console", String.class).orElse(""))) {
            violations.add("logs are plain text: set MDS_LOG_FORMAT=ecs for JSON logs");
        }
        if (mds.stream().allowedOrigins().contains("*")) {
            violations.add("mds.stream.allowed-origins accepts every browser origin: list the sites allowed "
                    + "to open WebSockets (MDS_STREAM_ALLOWED_ORIGINS)");
        }
        if (mds.stream().allowUnthrottled()) {
            violations.add("mds.stream.allow-unthrottled lets clients ask for every change: set "
                    + "MDS_STREAM_ALLOW_UNTHROTTLED=false");
        }
        return violations;
    }
}
