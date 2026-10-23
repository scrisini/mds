package com.mds.configdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.mds.support.FakeVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultClientTest {

    @TempDir
    Path dir;

    private FakeVault vault;

    @BeforeEach
    void start() throws Exception {
        vault = new FakeVault(Map.of(
                "mds.influx.token", "influx-secret",
                "mds", Map.of("socks-proxy", Map.of("host", "proxy.internal")),
                "mds.exchanges", List.of("kraken", "bitstamp")));
    }

    @AfterEach
    void stop() {
        vault.close();
    }

    @Test
    void readsASecretWithATokenAndFlattensNestedKeys() {
        Map<String, Object> secrets = new VaultClient(settings(VaultSettings.Auth.TOKEN, FakeVault.ROOT_TOKEN, "",
                "")).readSecret();

        assertThat(secrets).containsEntry("mds.influx.token", "influx-secret")
                .containsEntry("mds.socks-proxy.host", "proxy.internal")
                .containsEntry("mds.exchanges", "kraken,bitstamp");
        assertThat(vault.revoked).as("a token it was given isn't its to revoke").isEmpty();
    }

    @Test
    void logsInWithTheKubernetesServiceAccountAndRevokesTheTokenAfterwards() throws Exception {
        Path jwt = Files.writeString(dir.resolve("token"), FakeVault.KUBERNETES_JWT + "\n");

        Map<String, Object> secrets = new VaultClient(settings(VaultSettings.Auth.KUBERNETES, "", jwt.toString(), ""))
                .readSecret();

        assertThat(secrets).containsEntry("mds.influx.token", "influx-secret");
        assertThat(vault.revoked).containsExactly(FakeVault.LOGIN_TOKEN);
    }

    @Test
    void logsInWithAnAppRole() throws Exception {
        Path secretId = Files.writeString(dir.resolve("secret-id"), FakeVault.SECRET_ID);

        assertThat(new VaultClient(settings(VaultSettings.Auth.APP_ROLE, "", "", secretId.toString())).readSecret())
                .containsEntry("mds.influx.token", "influx-secret");
        assertThat(vault.revoked).containsExactly(FakeVault.LOGIN_TOKEN);
    }

    @Test
    void aRefusalSaysWhyWithoutTheToken() {
        VaultClient client = new VaultClient(settings(VaultSettings.Auth.TOKEN, "wrong-token", "", ""));

        assertThatThrownBy(client::readSecret)
                .hasMessageContaining("HTTP 403: permission denied")
                .hasMessageNotContaining("wrong-token");
    }

    @Test
    void refusesPlainHttpExceptToThisMachine() {
        assertThatThrownBy(() -> VaultClient.requireSecureAddress("http://vault.internal:8200"))
                .hasMessageContaining("must use https");
        VaultClient.requireSecureAddress("https://vault.internal:8200");
        VaultClient.requireSecureAddress("http://localhost:8200");
        VaultClient.requireSecureAddress("http://127.0.0.1:8200");
    }

    @Test
    void settingsNeverPrintTheToken() {
        assertThat(settings(VaultSettings.Auth.TOKEN, "s3cr3t-token", "", "").toString()).doesNotContain("s3cr3t-token");
    }

    private VaultSettings settings(VaultSettings.Auth auth, String token, String jwtFile, String secretIdFile) {
        return new VaultSettings(vault.address(), "secret", "mds/test", 2, "", "", Duration.ofSeconds(5), auth, token,
                new VaultSettings.Kubernetes("mds", "kubernetes", Path.of(jwtFile.isEmpty() ? "/none" : jwtFile)),
                new VaultSettings.AppRole("mds-role", secretIdFile, "approle"));
    }
}
