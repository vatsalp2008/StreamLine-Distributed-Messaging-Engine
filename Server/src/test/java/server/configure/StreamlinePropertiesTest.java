package server.configure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StreamlinePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(StreamlineProperties.class)
    static class TestConfig {
    }

    @Test
    void defaultsAreUsableWithoutAnyConfiguration() {
        runner.run(context -> {
            StreamlineProperties properties = context.getBean(StreamlineProperties.class);

            assertThat(properties.getBroadcast().isEnabled()).isTrue();
            assertThat(properties.getPersistence().getCorePoolSize()).isEqualTo(8);
            assertThat(properties.getPersistence().getMaxPoolSize()).isEqualTo(32);
            assertThat(properties.getPersistence().getQueueCapacity()).isEqualTo(10000);
            assertThat(properties.getWs().getMaxTextBytes()).isEqualTo(8192);
            assertThat(properties.getWs().getIdleTimeoutMs()).isEqualTo(300000);
            assertThat(properties.getWs().getAllowedOrigins()).containsExactly("*");
        });
    }

    @Test
    void broadcastCanBeDisabledForBenchmarks() {
        runner.withPropertyValues("streamline.broadcast.enabled=false").run(context ->
                assertThat(context.getBean(StreamlineProperties.class).getBroadcast().isEnabled())
                        .isFalse());
    }

    @Test
    void persistencePoolIsConfigurable() {
        runner.withPropertyValues(
                "streamline.persistence.core-pool-size=4",
                "streamline.persistence.max-pool-size=16",
                "streamline.persistence.queue-capacity=500").run(context -> {
            StreamlineProperties.Persistence persistence =
                    context.getBean(StreamlineProperties.class).getPersistence();

            assertThat(persistence.getCorePoolSize()).isEqualTo(4);
            assertThat(persistence.getMaxPoolSize()).isEqualTo(16);
            assertThat(persistence.getQueueCapacity()).isEqualTo(500);
        });
    }

    @Test
    void allowedOriginsCanBeRestrictedToNamedHosts() {
        runner.withPropertyValues(
                "streamline.ws.allowed-origins=https://app.example.com,https://admin.example.com")
                .run(context -> assertThat(
                        context.getBean(StreamlineProperties.class).getWs().getAllowedOrigins())
                        .containsExactly("https://app.example.com", "https://admin.example.com"));
    }

    @Test
    void connectionLimitsAreConfigurable() {
        runner.withPropertyValues(
                "streamline.ws.max-text-bytes=2048",
                "streamline.ws.max-binary-bytes=4096",
                "streamline.ws.idle-timeout-ms=60000").run(context -> {
            StreamlineProperties.Ws ws = context.getBean(StreamlineProperties.class).getWs();

            assertThat(ws.getMaxTextBytes()).isEqualTo(2048);
            assertThat(ws.getMaxBinaryBytes()).isEqualTo(4096);
            assertThat(ws.getIdleTimeoutMs()).isEqualTo(60000);
        });
    }
}
