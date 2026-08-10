package server.configure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires the shared secret on protected HTTP paths.
 *
 * Runs just after the correlation id filter, so a rejected request still gets an
 * id and still appears in the logs under it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthFilter.class);

    /** Path prefix under which endpoints address a single room. */
    private static final String ROOM_API_PREFIX = "/api/rooms/";

    private final TokenAuthenticator authenticator;

    public TokenAuthFilter(TokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!authenticator.isEnabled()) {
            return true;
        }

        String path = request.getRequestURI();

        // Liveness and readiness stay open: a load balancer probing them has no
        // token, and locking them out would make the server look permanently down.
        if (path.equals("/health") || path.equals("/ready")) {
            return true;
        }

        if (path.startsWith("/actuator")) {
            return !authenticator.protectsActuator();
        }

        // The browser client and its assets are public; the socket it opens is not.
        return !path.startsWith("/api") && !path.startsWith("/v3/api-docs")
                && !path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader(authenticator.headerName());
        if (presented == null) {
            presented = request.getParameter(authenticator.queryParamName());
        }

        // Room-scoped paths are checked against that room's token. Otherwise a
        // holder of the shared token could read a private room's history over
        // HTTP even though the socket refuses them.
        String roomId = roomIdOf(request.getRequestURI());

        if (!authenticator.isAuthorisedForRoom(roomId, presented)) {
            log.warn("Rejected unauthenticated {} {}", request.getMethod(), request.getRequestURI());
            writeUnauthorised(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Extracts the room from an /api/rooms/{roomId}/... path.
     *
     * @return the room id, or null when the path is not room-scoped
     */
    private String roomIdOf(String path) {
        if (path == null || !path.startsWith(ROOM_API_PREFIX)) {
            return null;
        }

        String rest = path.substring(ROOM_API_PREFIX.length());
        int nextSegment = rest.indexOf('/');
        // "/api/rooms" itself lists rooms rather than reading one, so it stays
        // on the shared token
        if (nextSegment < 0) {
            return null;
        }

        String roomId = rest.substring(0, nextSegment);
        return roomId.isBlank() ? null : java.net.URLDecoder.decode(roomId,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Answers in the same shape as the rest of the API so a client can parse one
     * error format. The body says nothing about why the token was rejected.
     */
    private void writeUnauthorised(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"status":401,"error":"Unauthorized","message":"A valid token is required"}""");
    }
}
