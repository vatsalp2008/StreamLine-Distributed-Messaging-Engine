package server.configure;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Handshake-level access control.
 *
 * The integration test proves this is wired in; these cover the branches that
 * are awkward to reach over a real socket, such as header/parameter precedence.
 */
class TokenHandshakeInterceptorTest {

    private static final String TOKEN = "s3cret-token-long-enough";

    private StreamlineProperties properties(boolean enabled) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getAuth().setEnabled(enabled);
        properties.getAuth().setToken(TOKEN);
        return properties;
    }

    private TokenHandshakeInterceptor interceptor(StreamlineProperties properties) {
        return new TokenHandshakeInterceptor(new TokenAuthenticator(properties));
    }

    /** Runs the handshake check and reports whether it was allowed. */
    private boolean handshake(TokenHandshakeInterceptor interceptor,
            MockHttpServletRequest request, MockHttpServletResponse response) {

        ServerHttpRequest req = new ServletServerHttpRequest(request);
        ServerHttpResponse res = new ServletServerHttpResponse(response);
        return interceptor.beforeHandshake(req, res, mock(WebSocketHandler.class), new HashMap<>());
    }

    private MockHttpServletRequest chatRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/1");
        request.setRequestURI("/chat/1");
        return request;
    }

    @Test
    void everyHandshakeIsAllowedWhenAuthIsDisabled() {
        assertThat(handshake(interceptor(properties(false)), chatRequest(),
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void aHandshakeWithoutATokenIsRefused() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(handshake(interceptor(properties(true)), chatRequest(), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void theTokenIsAcceptedInAHeader() {
        MockHttpServletRequest request = chatRequest();
        request.addHeader("X-Streamline-Token", TOKEN);

        assertThat(handshake(interceptor(properties(true)), request,
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void theTokenIsAcceptedAsAQueryParameter() {
        MockHttpServletRequest request = chatRequest();
        request.setParameter("token", TOKEN);

        // browsers cannot set handshake headers, so this path must work
        assertThat(handshake(interceptor(properties(true)), request,
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void aWrongTokenIsRefused() {
        MockHttpServletRequest request = chatRequest();
        request.setParameter("token", "not-the-right-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(handshake(interceptor(properties(true)), request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void theHeaderWinsWhenBothAreSupplied() {
        MockHttpServletRequest request = chatRequest();
        request.addHeader("X-Streamline-Token", TOKEN);
        request.setParameter("token", "a-different-wrong-token");

        assertThat(handshake(interceptor(properties(true)), request,
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void aBlankHeaderFallsBackToTheQueryParameter() {
        MockHttpServletRequest request = chatRequest();
        request.addHeader("X-Streamline-Token", "   ");
        request.setParameter("token", TOKEN);

        // an empty header should not shadow a valid parameter
        assertThat(handshake(interceptor(properties(true)), request,
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void customHeaderAndParameterNamesAreHonoured() {
        StreamlineProperties properties = properties(true);
        properties.getAuth().setHeader("X-Custom-Auth");
        properties.getAuth().setQueryParam("access_token");

        MockHttpServletRequest viaHeader = chatRequest();
        viaHeader.addHeader("X-Custom-Auth", TOKEN);
        assertThat(handshake(interceptor(properties), viaHeader,
                new MockHttpServletResponse())).isTrue();

        MockHttpServletRequest viaParam = chatRequest();
        viaParam.setParameter("access_token", TOKEN);
        assertThat(handshake(interceptor(properties), viaParam,
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void theDefaultParameterIsIgnoredWhenACustomOneIsConfigured() {
        StreamlineProperties properties = properties(true);
        properties.getAuth().setQueryParam("access_token");

        MockHttpServletRequest request = chatRequest();
        request.setParameter("token", TOKEN);

        assertThat(handshake(interceptor(properties), request,
                new MockHttpServletResponse())).isFalse();
    }

    @Test
    void afterHandshakeDoesNotThrow() {
        MockHttpServletRequest request = chatRequest();

        interceptor(properties(true)).afterHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                new IllegalStateException("handshake blew up"));
    }
}
