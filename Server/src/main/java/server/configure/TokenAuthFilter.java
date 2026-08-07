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

        if (!authenticator.isAuthorised(presented)) {
            log.warn("Rejected unauthenticated {} {}", request.getMethod(), request.getRequestURI());
            writeUnauthorised(response);
            return;
        }

        chain.doFilter(request, response);
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
