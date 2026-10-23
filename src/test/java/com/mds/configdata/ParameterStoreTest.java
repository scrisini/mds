package com.mds.configdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

class ParameterStoreTest {

    @Test
    void readsEveryPageAndNamesPropertiesAfterThePath() {
        SsmClient client = mock(SsmClient.class);
        when(client.getParametersByPath(any(GetParametersByPathRequest.class))).thenAnswer(inv -> {
            GetParametersByPathRequest request = inv.getArgument(0);
            assertThat(request.path()).isEqualTo("/mds/prod/");
            assertThat(request.recursive()).isTrue();
            return request.nextToken() == null
                    ? GetParametersByPathResponse.builder().nextToken("page-2")
                            .parameters(parameter("/mds/prod/mds/pairs", "BTC/USD,ETH/USD")).build()
                    : GetParametersByPathResponse.builder()
                            .parameters(parameter("/mds/prod/mds.stream.max-subscribers", "2000")).build();
        });

        Map<String, Object> properties = new ParameterStore(client).read("/mds/prod/");

        assertThat(properties).containsExactly(Map.entry("mds.pairs", "BTC/USD,ETH/USD"),
                Map.entry("mds.stream.max-subscribers", "2000"));
    }

    private static Parameter parameter(String name, String value) {
        return Parameter.builder().name(name).value(value).build();
    }
}
