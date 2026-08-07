package server.configure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Requires the shared secret before a chat connection is opened.
 *
 * Rejecting at the handshake means an unauthenticated client never reaches the
 * message handler, so it cannot consume a session slot or a rate-limiter bucket.
 *
 * The token may arrive as a header or as a query parameter. Browsers cannot set
 * headers on a WebSocket handshake, so without the parameter the bundled client
 * could not connect at all.
 */
@Component
public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TokenHandshakeInterceptor.class);

    private final TokenAuthenticator authenticator;

    public TokenHandshakeInterceptor(TokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {

        if (!authenticator.isEnabled()) {
            return true;
        }

        if (authenticator.isAuthorised(presentedToken(request))) {
            return true;
        }

        log.warn("Rejected unauthenticated chat handshake for {}", request.getURI().getPath());
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {
        // nothing to clean up
    }

    /**
     * @return the token supplied on the handshake, or null when absent
     */
    private String presentedToken(ServerHttpRequest request) {
        String fromHeader = request.getHeaders().getFirst(authenticator.headerName());
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }

        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest()
                    .getParameter(authenticator.queryParamName());
        }
        return null;
    }
}
