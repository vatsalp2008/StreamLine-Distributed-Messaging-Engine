package server.configure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-address limit on the HTTP API.
 *
 * Search runs a LIKE scan per call, so an unthrottled caller could keep the
 * database busy indefinitely; the socket has been limited for a while.
 */
class ApiRateLimitFilterTest {

    private SimpleMeterRegistry registry;

    private StreamlineProperties properties(boolean enabled, double perSecond, int burst) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getRateLimit().setEnabled(enabled);
        properties.getRateLimit().setApiRequestsPerSecond(perSecond);
        properties.getRateLimit().setApiBurstSize(burst);
        return properties;
    }

    private ApiRateLimitFilter filter(StreamlineProperties properties) {
        registry = new SimpleMeterRegistry();
        return new ApiRateLimitFilter(properties, new ChatMetrics(registry),
                new ClientAddressResolver(properties));
    }

    private MockHttpServletRequest request(String path, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setRemoteAddr(address);
        return request;
    }

    private boolean passesThrough(ApiRateLimitFilter filter, MockHttpServletRequest request,
            MockHttpServletResponse response) throws Exception {
        boolean[] reached = {false};
        FilterChain chain = (req, res) -> reached[0] = true;
        filter.doFilter(request, response, chain);
        return reached[0];
    }

    @Test
    void requestsPassWhileTheBudgetLasts() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 3));

        for (int i = 0; i < 3; i++) {
            assertThat(passesThrough(filter, request("/api/rooms", "10.0.0.1"),
                    new MockHttpServletResponse())).as("request %d", i).isTrue();
        }
    }

    @Test
    void exceedingTheBudgetIsRefusedWith429() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 2));
        passesThrough(filter, request("/api/rooms", "10.0.0.1"), new MockHttpServletResponse());
        passesThrough(filter, request("/api/rooms", "10.0.0.1"), new MockHttpServletResponse());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(passesThrough(filter, request("/api/rooms", "10.0.0.1"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"status\":429");
    }

    @Test
    void oneCallerDoesNotConsumeAnothersBudget() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 1));
        passesThrough(filter, request("/api/rooms", "10.0.0.1"), new MockHttpServletResponse());

        // a shared bucket would let one busy client lock everyone else out
        assertThat(passesThrough(filter, request("/api/rooms", "10.0.0.2"),
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void refusalsAreCounted() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 1));
        passesThrough(filter, request("/api/rooms", "10.0.0.1"), new MockHttpServletResponse());
        passesThrough(filter, request("/api/rooms", "10.0.0.1"), new MockHttpServletResponse());

        assertThat(registry.get("streamline.messages.rate_limited").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void nothingIsLimitedWhenRateLimitingIsOff() throws Exception {
        ApiRateLimitFilter filter = filter(properties(false, 1, 1));

        for (int i = 0; i < 10; i++) {
            assertThat(passesThrough(filter, request("/api/rooms", "10.0.0.1"),
                    new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void probesAreNeverThrottled() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 1));

        // a load balancer polling these must not be told to slow down
        for (String path : new String[] {"/health", "/ready"}) {
            for (int i = 0; i < 5; i++) {
                assertThat(passesThrough(filter, request(path, "10.0.0.1"),
                        new MockHttpServletResponse()))
                        .as("%s request %d", path, i).isTrue();
            }
        }
    }

    @Test
    void theBrowserClientIsNotThrottled() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 1));

        for (int i = 0; i < 5; i++) {
            assertThat(passesThrough(filter, request("/app.js", "10.0.0.1"),
                    new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void aBucketIsKeptPerAddress() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 5));

        passesThrough(filter, request("/api/rooms", "10.0.0.1"), new MockHttpServletResponse());
        passesThrough(filter, request("/api/rooms", "10.0.0.2"), new MockHttpServletResponse());

        assertThat(filter.trackedClients()).isEqualTo(2);
    }

    @Test
    void aRequestWithNoAddressStillGetsABucket() throws Exception {
        ApiRateLimitFilter filter = filter(properties(true, 1, 1));
        MockHttpServletRequest request = request("/api/rooms", null);

        // must not throw on the way to deciding
        assertThat(passesThrough(filter, request, new MockHttpServletResponse())).isTrue();
    }

    // ---------- behind a proxy ----------

    @Test
    void callersBehindATrustedProxyGetTheirOwnBudgets() throws Exception {
        StreamlineProperties properties = properties(true, 1, 1);
        properties.getProxy().setTrusted(new java.util.ArrayList<>(java.util.List.of("10.0.0.1")));
        ApiRateLimitFilter filter = filter(properties);

        MockHttpServletRequest first = request("/api/rooms", "10.0.0.1");
        first.addHeader("X-Forwarded-For", "203.0.113.1");
        MockHttpServletRequest second = request("/api/rooms", "10.0.0.1");
        second.addHeader("X-Forwarded-For", "203.0.113.2");

        // sharing the proxy's address would let one caller throttle everyone
        assertThat(passesThrough(filter, first, new MockHttpServletResponse())).isTrue();
        assertThat(passesThrough(filter, second, new MockHttpServletResponse())).isTrue();
    }

    @Test
    void aSpoofedForwardingHeaderDoesNotEscapeTheLimit() throws Exception {
        // no proxy is trusted, so the header is somebody's invention
        ApiRateLimitFilter filter = filter(properties(true, 1, 1));

        MockHttpServletRequest first = request("/api/rooms", "203.0.113.9");
        first.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletRequest second = request("/api/rooms", "203.0.113.9");
        second.addHeader("X-Forwarded-For", "2.2.2.2");

        assertThat(passesThrough(filter, first, new MockHttpServletResponse())).isTrue();
        assertThat(passesThrough(filter, second, new MockHttpServletResponse())).isFalse();
    }
}
