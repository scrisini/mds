package com.mds.configdata;

import java.net.URI;
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
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;

/**
 * {@code spring.config.import=mds-ssm:} loads {@link SsmSettings#path} from AWS SSM Parameter Store at
 * startup, before any bean exists. Its properties override {@code application.yml}; environment
 * variables and command-line arguments still override them. Does nothing while {@code mds.ssm.path}
 * is blank. A configured path that can't be read stops startup: the instance never runs on defaults
 * it wasn't meant to have.
 */
public final class SsmConfigData {

    static final String PREFIX = "mds-ssm:";

    private SsmConfigData() {
    }

    /** Every parameter under the configured path, as properties. */
    static Map<String, Object> read(SsmSettings settings) {
        try (SsmClient client = client(settings)) {
            return new ParameterStore(client).read(settings.path());
        }
    }

    /** The name of the property source holding them, e.g. {@code mds-ssm:/mds/prod/}. */
    static String sourceName(SsmSettings settings) {
        return PREFIX + settings.path();
    }

    private static SsmClient client(SsmSettings settings) {
        SsmClientBuilder builder = SsmClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(settings.timeout()).socketTimeout(settings.timeout()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(settings.timeout()).build());
        if (!settings.region().isEmpty()) {
            builder.region(Region.of(settings.region()));
        }
        if (!settings.endpoint().isEmpty()) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        return builder.build();
    }

    /** The path to read, from {@code mds.ssm.*} as known when imports are resolved. */
    static final class Resource extends ConfigDataResource {

        private final SsmSettings settings;

        Resource(SsmSettings settings) {
            this.settings = settings;
        }

        SsmSettings settings() {
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
            return sourceName(settings);
        }
    }

    public static final class Resolver implements ConfigDataLocationResolver<Resource> {

        @Override
        public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
            return location.hasPrefix(PREFIX);
        }

        @Override
        public List<Resource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
            SsmSettings settings = context.getBinder().bindOrCreate("mds.ssm", SsmSettings.class);
            return settings.enabled() ? List.of(new Resource(settings)) : List.of();
        }
    }

    public static final class Loader implements ConfigDataLoader<Resource> {

        @Override
        public ConfigData load(ConfigDataLoaderContext context, Resource resource) {
            SsmSettings settings = resource.settings();
            try {
                return new ConfigData(List.of(new MapPropertySource(sourceName(settings), read(settings))));
            } catch (RuntimeException e) {
                throw new IllegalStateException("%s: AWS SSM Parameter Store path %s: %s"
                        .formatted(LogEvent.CONFIG_LOAD_FAILED.message(), settings.path(), e.getMessage()), e);
            }
        }
    }
}
