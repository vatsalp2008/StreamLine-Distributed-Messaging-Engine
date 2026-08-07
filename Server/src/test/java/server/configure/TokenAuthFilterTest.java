package server.configure;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TokenAuthFilterTest {

    private static final String TOKEN = "s3cret-token-long-enough";

    private StreamlineProperties properties(boolean enabled) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getAuth().setEnabled(enabled);
        properties.getAuth().setToken(TOKEN);
        return properties;
    }

    private TokenAuthFilter filter(StreamlineProperties properties) {
        return new TokenAuthFilter(new TokenAuthenticator(properties));
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    /** Runs the filter and reports whether the request reached the chain. */
    private boolean passesThrough(TokenAuthFilter filter, MockHttpServletRequest request,
            MockHttpServletResponse response) throws Exception {
        boolean[] reached = {false};
        FilterChain chain = (req, res) -> reached[0] = true;
        filter.doFilter(request, response, chain);
        return reached[0];
    }

    // ---------- disabled ----------

    @Test
    void everyRequestPassesWhenAuthIsDisabled() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filter(properties(false)), request("/api/rooms"), response))
                .isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---------- protected paths ----------

    @Test
    void anApiCallWithoutATokenIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filter(properties(true)), request("/api/rooms"), response))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"status\":401");
    }

    @Test
    void anApiCallWithTheCorrectHeaderIsAllowed() throws Exception {
        MockHttpServletRequest request = request("/api/rooms");
        request.addHeader("X-Streamline-Token", TOKEN);

        assertThat(passesThrough(filter(properties(true)), request, new MockHttpServletResponse()))
                .isTrue();
    }

    @Test
    void anApiCallWithTheWrongTokenIsRejected() throws Exception {
        MockHttpServletRequest request = request("/api/rooms");
        request.addHeader("X-Streamline-Token", "not-the-right-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filter(properties(true)), request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void theTokenMayBeSuppliedAsAQueryParameter() throws Exception {
        MockHttpServletRequest request = request("/api/rooms");
        request.setParameter("token", TOKEN);

        assertThat(passesThrough(filter(properties(true)), request, new MockHttpServletResponse()))
                .isTrue();
    }

    @Test
    void theApiDocsAreProtected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // the spec describes the whole surface, so it should not be public
        assertThat(passesThrough(filter(properties(true)), request("/v3/api-docs"), response))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ---------- paths that stay open ----------

    @Test
    void livenessStaysOpenSoProbesKeepWorking() throws Exception {
        assertThat(passesThrough(filter(properties(true)), request("/health"),
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void readinessStaysOpenSoProbesKeepWorking() throws Exception {
        // a load balancer has no token; locking it out would look like an outage
        assertThat(passesThrough(filter(properties(true)), request("/ready"),
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void theBrowserClientStaysPublic() throws Exception {
        assertThat(passesThrough(filter(properties(true)), request("/"),
                new MockHttpServletResponse())).isTrue();
    }

    // ---------- actuator ----------

    @Test
    void actuatorIsProtectedByDefault() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filter(properties(true)), request("/actuator/metrics"), response))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void actuatorCanBeLeftOpenDeliberately() throws Exception {
        StreamlineProperties properties = properties(true);
        properties.getAuth().setProtectActuator(false);

        assertThat(passesThrough(filter(properties), request("/actuator/metrics"),
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void actuatorWithTheCorrectTokenIsAllowed() throws Exception {
        MockHttpServletRequest request = request("/actuator/metrics");
        request.addHeader("X-Streamline-Token", TOKEN);

        assertThat(passesThrough(filter(properties(true)), request, new MockHttpServletResponse()))
                .isTrue();
    }

    // ---------- configuration ----------

    @Test
    void aCustomHeaderNameIsHonoured() throws Exception {
        StreamlineProperties properties = properties(true);
        properties.getAuth().setHeader("X-Custom-Auth");

        MockHttpServletRequest request = request("/api/rooms");
        request.addHeader("X-Custom-Auth", TOKEN);

        assertThat(passesThrough(filter(properties), request, new MockHttpServletResponse()))
                .isTrue();
    }

    @Test
    void theDefaultHeaderIsIgnoredWhenACustomOneIsConfigured() throws Exception {
        StreamlineProperties properties = properties(true);
        properties.getAuth().setHeader("X-Custom-Auth");

        MockHttpServletRequest request = request("/api/rooms");
        request.addHeader("X-Streamline-Token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filter(properties), request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aRejectedRequestNeverReachesTheChain() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        filter(properties(true)).doFilter(request("/api/rooms"),
                new MockHttpServletResponse(), chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
