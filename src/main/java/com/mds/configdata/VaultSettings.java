package com.mds.configdata;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Secrets from HashiCorp Vault's key/value engine, read once at startup ({@link VaultConfigData}). Off
 * while {@code address} is blank. Each key of the secret is a property name ({@code mds.influx.token}),
 * or nested objects that flatten to one. The call is made directly, like InfluxDB traffic: Vault must
 * be on the private network. Its address must use https, except on localhost for development.
 *
 * @param address    Vault's URL, e.g. {@code https://vault.internal:8200}; blank turns this off
 * @param kvMount    mount path of the key/value secrets engine
 * @param path       the secret's path within the mount, e.g. {@code mds/prod}
 * @param kvVersion  version of the key/value engine: 2 (versioned) or 1
 * @param namespace  Vault Enterprise namespace; blank for none
 * @param caCert     PEM file with the CA that signed Vault's certificate; blank for the JDK's trust store
 * @param timeout    limit for each request to Vault
 * @param auth       how mds logs in: {@code token}, {@code kubernetes} or {@code app-role}
 * @param token      the token, for {@code auth=token}; from an environment variable, never a file in git
 * @param kubernetes settings for {@code auth=kubernetes}
 * @param appRole    settings for {@code auth=app-role}
 */
@ConfigurationProperties("mds.vault")
public record VaultSettings(
        @DefaultValue("") String address,
        @DefaultValue("secret") String kvMount,
        @DefaultValue("mds") String path,
        @DefaultValue("2") int kvVersion,
        @DefaultValue("") String namespace,
        @DefaultValue("") String caCert,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("token") Auth auth,
        @DefaultValue("") String token,
        @DefaultValue Kubernetes kubernetes,
        @DefaultValue AppRole appRole) {

    public enum Auth { TOKEN, KUBERNETES, APP_ROLE }

    public VaultSettings {
        address = trimTrailingSlash(address);
        if (kvVersion != 1 && kvVersion != 2) {
            throw new IllegalArgumentException("mds.vault.kv-version must be 1 or 2, was " + kvVersion);
        }
        kvMount = trimSlashes(kvMount);
        path = trimSlashes(path);
    }

    public boolean enabled() {
        return !address.isEmpty();
    }

    /** Without the token, so the settings can appear in logs and error messages. */
    @Override
    public String toString() {
        return "VaultSettings[address=%s, kvMount=%s, path=%s, kvVersion=%d, auth=%s]"
                .formatted(address, kvMount, path, kvVersion, auth);
    }

    /**
     * @param role      Vault role bound to this service account
     * @param mount     mount path of the Kubernetes auth method
     * @param tokenFile the pod's service-account token
     */
    public record Kubernetes(
            @DefaultValue("") String role,
            @DefaultValue("kubernetes") String mount,
            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token") Path tokenFile) {
    }

    /**
     * @param roleId       the AppRole's role id
     * @param secretIdFile file holding the secret id, delivered by the platform (never in git)
     * @param mount        mount path of the AppRole auth method
     */
    public record AppRole(
            @DefaultValue("") String roleId,
            @DefaultValue("") String secretIdFile,
            @DefaultValue("approle") String mount) {
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String trimSlashes(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
