package server.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the HTTP API served alongside the WebSocket endpoint.
 *
 * The document covers the REST surface only. The chat protocol itself is
 * WebSocket, which OpenAPI cannot express, so it stays documented in README.md.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI streamlineOpenApi() {
        return new OpenAPI().info(new Info()
                .title("StreamLine API")
                .version("v1")
                .description("""
                        Read-only HTTP view of a StreamLine server: which rooms are \
                        active, and what has been said in them. Taking part in a \
                        conversation requires the WebSocket endpoint at /chat/{roomId}.""")
                .license(new License().name("MIT")));
    }
}
