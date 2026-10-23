package com.mds.configdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mds.cluster.Cluster;
import com.mds.config.MdsProperties;
import com.mds.support.FakeParameterStore;
import com.mds.support.FakeVault;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The check against a fake Parameter Store and a fake Vault. The environment is built the way
 * startup leaves it: the settings, then what each store held at startup, Vault taking precedence.
 */
class ConfigCheckTest {

    private static final String SSM_PATH = "/mds/test/";

    private final Map<String, Object> secret = new ConcurrentHashMap<>(Map.of("mds.influx.token", "token-v1"));
    private final Cluster cluster = mock(Cluster.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FakeParameterStore ssm;
    private FakeVault vault;

    @BeforeEach
    void start() throws IOException {
        ssm = new FakeParameterStore(Map.of(SSM_PATH + "mds/pairs", "BTC/USDT"));
        vault = new FakeVault(secret);
        when(cluster.memberCount()).thenReturn(1);
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @AfterEach
    void stop() {
        ssm.close();
        vault.close();
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void unchangedStoresWouldRestart() {
        ConfigCheck.Result result = check(FakeVault.ROOT_TOKEN, Map.of()).run();

        assertThat(result.restartable()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.changed()).isEmpty();
    }

    @Test
    void changedKeysAreReportedByNameOnly() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "mds/pairs", "ETH/USDT");
        ssm.parameters.put(SSM_PATH + "mds/exchanges", "kraken");
        secret.put("mds.influx.token", "token-v2");

        ConfigCheck.Result result = check.run();

        assertThat(result.restartable()).isTrue();
        assertThat(result.changed()).containsExactly("mds.exchanges", "mds.influx.token", "mds.pairs");
        assertThat(result.toString()).doesNotContain("token-v2").doesNotContain("ETH/USDT");
    }

    @Test
    void aValueThatWouldNotValidateStopsARestart() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "mds/decimal/scale", "99");

        ConfigCheck.Result result = check.run();

        assertThat(result.restartable()).isFalse();
        assertThat(result.problems()).singleElement().asString()
                .contains("mds.decimal.scale must be between 0 and 18");
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(registry.get("mds.config.restartable").gauge().value()).isZero();
    }

    @Test
    void aMalformedPairStopsARestart() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "mds/pairs", "BTCUSDT");

        assertThat(check.run().problems()).singleElement().asString().contains("Invalid pair 'BTCUSDT'");
    }

    @Test
    void aVaultLoginThatNoLongerWorksStopsARestartWithoutShowingTheToken() {
        ConfigCheck.Result result = check("revoked-token", Map.of()).run();

        assertThat(result.restartable()).isFalse();
        assertThat(result.problems()).singleElement().asString()
                .startsWith("Vault secret secret/mds/test: ")
                .contains("HTTP 403: permission denied")
                .doesNotContain("revoked-token");
    }

    @Test
    void productionModeRulesApplyToTheStoredConfiguration() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of(
                "mds.mode", "production",
                "logging.structured.format.console", "ecs",
                "mds.stream.allowed-origins", "https://app.example.com",
                "mds.stream.allow-unthrottled", "false"));
        assertThat(check.run().restartable()).isTrue();

        secret.remove("mds.influx.token");

        assertThat(check.run().problems()).singleElement().asString().startsWith("mds.influx.token");
    }

    @Test
    void aChangeToSharedSettingsStopsARestartOnlyWhileOtherMembersRun() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "mds/ohlc/zone", "Australia/Sydney");

        assertThat(check.run().restartable()).as("alone, it restarts with the new zone").isTrue();

        when(cluster.memberCount()).thenReturn(3);
        assertThat(check.run().problems()).singleElement().asString()
                .contains("zone=UTC").contains("zone=Australia/Sydney").contains("MDS-1009");
    }

    @Test
    void recoversOnceTheStoreIsFixed() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "mds/decimal/scale", "99");
        check.run();

        ssm.parameters.remove(SSM_PATH + "mds/decimal/scale");

        assertThat(check.run().restartable()).isTrue();
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("mds.config.restartable").gauge().value()).isEqualTo(1);
    }

    /** A check over an environment as startup left it, with the stores' current contents as its startup values. */
    private ConfigCheck check(String vaultToken, Map<String, Object> more) {
        Map<String, Object> settings = new HashMap<>(more);
        settings.put("mds.ssm.path", SSM_PATH);
        settings.put("mds.ssm.region", "eu-west-1");
        settings.put("mds.ssm.endpoint", ssm.endpoint());
        settings.put("mds.vault.address", vault.address());
        settings.put("mds.vault.path", "mds/test");
        settings.put("mds.vault.token", vaultToken);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("settings", settings));
        SsmSettings ssmSettings = Binder.get(environment).bindOrCreate("mds.ssm", SsmSettings.class);
        environment.getPropertySources().addBefore("settings",
                new MapPropertySource(SsmConfigData.sourceName(ssmSettings), SsmConfigData.read(ssmSettings)));
        environment.getPropertySources().addFirst(
                new MapPropertySource("mds-vault:secret/mds/test", new HashMap<>(secret)));
        MdsProperties running = Binder.get(environment).bindOrCreate("mds", MdsProperties.class);
        return new ConfigCheck(environment, running, cluster, registry,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    }
}
