package com.mds.configdata;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/configcheck}: {@code GET} returns the latest {@link ConfigCheck} outcome,
 * {@code POST} checks now, for example before a planned restart or after changing a parameter or
 * secret. Inside the network only, like the rest of {@code /actuator}.
 */
@Component
@Endpoint(id = "configcheck")
public class ConfigCheckEndpoint {

    private final ConfigCheck check;

    public ConfigCheckEndpoint(ConfigCheck check) {
        this.check = check;
    }

    @ReadOperation
    public ConfigCheck.Result last() {
        return check.last();
    }

    @WriteOperation
    public ConfigCheck.Result run() {
        return check.run();
    }
}
