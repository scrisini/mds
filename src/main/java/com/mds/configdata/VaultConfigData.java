package com.mds.configdata;

import java.util.List;
import java.util.Map;

import com.mds.logging.LogEvent;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResource;
import org.springframework.core.env.MapPropertySource;

/**
 * {@code spring.config.import=mds-vault:} loads the secret {@link VaultSettings#path} from HashiCorp
 * Vault at startup, before any bean exists. Imported after {@code mds-ssm:}, so its values override
 * Parameter Store and {@code application.yml}; environment variables and command-line arguments still
 * override them. Does nothing while {@code mds.vault.address} is blank. A configured secret that can't
 * be read stops startup, so the instance never runs with a default credential.
 */
public final class VaultConfigData {

    static final String PREFIX = "mds-vault:";

    private VaultConfigData() {
    }

    /** The secret to read, from {@code mds.vault.*} as known when imports are resolved. */
    static final class Resource extends ConfigDataResource {

        private final VaultSettings settings;

        Resource(VaultSettings settings) {
            this.settings = settings;
        }

        VaultSettings settings() {
            return settings;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Resource other && settings.equals(other.settings);
        }

        @Override
        public int hashCode() {
            return settings.hashCode();
        }

        @Override
        public String toString() {
            return PREFIX + settings.kvMount() + "/" + settings.path();
        }
    }

    public static final class Resolver implements ConfigDataLocationResolver<Resource> {

        @Override
        public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
            return location.hasPrefix(PREFIX);
        }

        @Override
        public List<Resource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
            VaultSettings settings = context.getBinder().bindOrCreate("mds.vault", VaultSettings.class);
            return settings.enabled() ? List.of(new Resource(settings)) : List.of();
        }
    }

    public static final class Loader implements ConfigDataLoader<Resource> {

        @Override
        public ConfigData load(ConfigDataLoaderContext context, Resource resource) {
            try {
                Map<String, Object> secrets = new VaultClient(resource.settings()).readSecret();
                return new ConfigData(List.of(new MapPropertySource(resource.toString(), secrets)));
            } catch (RuntimeException e) {
                throw new IllegalStateException("%s: Vault secret %s: %s"
                        .formatted(LogEvent.CONFIG_LOAD_FAILED.message(), resource, e.getMessage()), e);
            }
        }
    }
}
