package com.mds.config;

import com.mds.math.Decimal;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The title and overview of the REST API's OpenAPI description, served at {@code /v3/api-docs}
 * with Swagger UI at {@code /swagger-ui.html} unless {@code MDS_API_DOCS_ENABLED=false}. Paths,
 * parameters and models come from the controllers.
 */
@Configuration
public class OpenApiConfig {

    static {
        // Decimal is written as a JSON number (DecimalJsonSerializer); without this the docs show its getters
        SpringDocUtils.getConfig().replaceWithSchema(Decimal.class,
                new NumberSchema().description("Fixed-point decimal with mds.decimal.scale digits after the point"));
    }

    private static final String DESCRIPTION = """
            Live and archived crypto prices aggregated across exchanges, and OHLC candles.

            - **Pairs** are written `BASE-QUOTE` in paths, e.g. `BTC-USDT`.
            - **Prices and volumes** are JSON numbers with the service's fixed decimal precision.
            - **Errors** are RFC 9457 problem details (`application/problem+json`) with a `requestId`;
              every response also carries it in `X-Request-Id`. Quote it to support.
            - **Real-time:** `/api/v1/stream/prices/{pair}` is Server-Sent Events. The WebSocket API
              (`/api/v1/ws/prices`) and the gRPC API (port 9090) aren't described here: see the README.
            """;

    /** Every error body, built by {@code ApiExceptionHandler}. */
    private static final String PROBLEM = "Problem";

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("mds market data API")
                        .version("v1")
                        .description(DESCRIPTION))
                .components(new Components().addSchemas(PROBLEM, problemSchema()));
    }

    /** Documents the problem body as each operation's response for any status it doesn't list. */
    @Bean
    OperationCustomizer problemResponses() {
        ApiResponse problem = new ApiResponse()
                .description("Error: an RFC 9457 problem with the requestId to quote to support")
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
        return (operation, handler) -> {
            operation.getResponses().addApiResponse("default", problem);
            return operation;
        };
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("RFC 9457 problem details")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Not Found"))
                .addProperty("status", new IntegerSchema().example(404))
                .addProperty("detail", new StringSchema()
                        .example("Pair NOPE/PAIR is not subscribed; available: [BTC/USDT, ETH/USDT]"))
                .addProperty("instance", new StringSchema().example("/api/v1/prices/NOPE-PAIR"))
                .addProperty("requestId", new StringSchema().example("6670c6a4-0cc9-4a2e-b1f2-8f0b7f1d5a3e"));
    }
}
