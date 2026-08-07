package server.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import server.configure.StreamlineProperties;

/**
 * Describes the HTTP API served alongside the WebSocket endpoint.
 *
 * The document covers the REST surface only. The chat protocol itself is
 * WebSocket, which OpenAPI cannot express, so it stays documented in README.md.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "streamlineToken";

    private final StreamlineProperties properties;

    public OpenApiConfig(StreamlineProperties properties) {
        this.properties = properties;
    }

    @Bean
    public OpenAPI streamlineOpenApi() {
        OpenAPI api = new OpenAPI().info(new Info()
                .title("StreamLine API")
                .version("v1")
                .description("""
                        Read-only HTTP view of a StreamLine server: which rooms are \
                        active, and what has been said in them. Taking part in a \
                        conversation requires the WebSocket endpoint at /chat/{roomId}.""")
                .license(new License().name("MIT")));

        // Only advertise the scheme when it is actually enforced, so the document
        // describes this server rather than a hypothetical configuration of it.
        if (properties.getAuth().isEnabled()) {
            api.components(new Components().addSecuritySchemes(SCHEME_NAME,
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.APIKEY)
                                    .in(SecurityScheme.In.HEADER)
                                    .name(properties.getAuth().getHeader())
                                    .description("Shared secret. May also be supplied as a "
                                            + "query parameter, which is how a browser "
                                            + "authenticates a WebSocket handshake.")))
                    .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
        }

        return api;
    }
}
