package com.mds.configdata;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration from AWS Systems Manager Parameter Store, read once at startup ({@link SsmConfigData}).
 * Off while {@code path} is blank. Credentials and, unless {@code region} is set, the region come from
 * the AWS SDK's default chain (environment, IRSA on EKS, ECS task role, instance profile). The call is
 * made directly, like InfluxDB traffic: reach SSM through a VPC endpoint, not the outbound proxy.
 *
 * @param path     parameter hierarchy, e.g. {@code /mds/prod/}; parameter {@code /mds/prod/mds/pairs}
 *                 (or {@code /mds/prod/mds.pairs}) becomes the property {@code mds.pairs}
 * @param region   AWS region; blank for the SDK's default
 * @param endpoint endpoint override, e.g. a VPC endpoint URL or LocalStack; blank for the default
 * @param timeout  limit for reading the whole hierarchy
 */
@ConfigurationProperties("mds.ssm")
public record SsmSettings(
        @DefaultValue("") String path,
        @DefaultValue("") String region,
        @DefaultValue("") String endpoint,
        @DefaultValue("10s") Duration timeout) {

    public SsmSettings {
        path = path == null ? "" : path.trim();
        if (!path.isEmpty()) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("mds.ssm.path must start with '/', was '" + path + "'");
            }
            path = path.endsWith("/") ? path : path + "/";
        }
        region = region == null ? "" : region.trim();
        endpoint = endpoint == null ? "" : endpoint.trim();
    }

    public boolean enabled() {
        return !path.isEmpty();
    }
}
