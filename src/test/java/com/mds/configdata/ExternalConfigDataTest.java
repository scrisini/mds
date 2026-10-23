package com.mds.configdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mds.support.FakeParameterStore;
import com.mds.support.FakeVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The imports in application.yml, through Spring Boot's own startup: Parameter Store and Vault feed
 * the environment, in order, before any bean exists. Against a fake SSM endpoint and a fake Vault.
 */
class ExternalConfigDataTest {

    /** No beans: only the environment matters here. */
    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    private FakeParameterStore ssm;
    private FakeVault vault;

    @BeforeEach
    void start() throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("/mds/test/mds/pairs", "SOL/USD");
        parameters.put("/mds/test/mds/exchanges", "kraken");
        parameters.put("/mds/test/mds.influx.token", "from-ssm");
        ssm = new FakeParameterStore(parameters);
        vault = new FakeVault(Map.of("mds.influx.token", "from-vault"));
        // the AWS SDK's default credentials chain reads these
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
    void parameterStoreThenVaultThenTheCommandLine() {
        try (ConfigurableApplicationContext context = run(FakeVault.ROOT_TOKEN, "--mds.exchanges=bitstamp")) {
            Environment environment = context.getEnvironment();

            assertThat(environment.getProperty("mds.pairs")).as("from Parameter Store").isEqualTo("SOL/USD");
            assertThat(environment.getProperty("mds.influx.token")).as("Vault over Parameter Store").isEqualTo("from-vault");
            assertThat(environment.getProperty("mds.exchanges")).as("command line over both").isEqualTo("bitstamp");
            assertThat(ssm.targets).containsExactly("AmazonSSM.GetParametersByPath");
        }
    }

    @Test
    void aSecretThatCantBeReadStopsStartupWithoutPrintingTheToken() {
        assertThatThrownBy(() -> run("not-the-token").close())
                .hasMessageContaining("MDS-1013")
                .hasMessageContaining("HTTP 403: permission denied")
                .satisfies(e -> {
                    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                        assertThat(cause.getMessage()).doesNotContain("not-the-token");
                    }
                });
    }

    @Test
    void doesNothingWhileNotConfigured() {
        SpringApplication application = new SpringApplication(NoBeans.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getEnvironment().getPropertySources().stream().map(s -> s.getName()))
                    .noneMatch(name -> name.startsWith("mds-ssm:") || name.startsWith("mds-vault:"));
        }
        assertThat(ssm.targets).isEmpty();
    }

    private ConfigurableApplicationContext run(String vaultToken, String... more) {
        SpringApplication application = new SpringApplication(NoBeans.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        String[] args = {
                "--mds.ssm.path=/mds/test/",
                "--mds.ssm.region=eu-west-1",
                "--mds.ssm.endpoint=" + ssm.endpoint(),
                "--mds.vault.address=" + vault.address(),
                "--mds.vault.path=mds/test",
                "--mds.vault.token=" + vaultToken};
        String[] all = new String[args.length + more.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(more, 0, all, args.length, more.length);
        return application.run(all);
    }
}
