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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how fast one caller may hit the HTTP API.
 *
 * The WebSocket side has been rate limited for a while, but the API was not:
 * search in particular runs a LIKE scan per request, so an unthrottled caller
 * could keep the database busy indefinitely.
 *
 * Buckets are keyed by client address, resolved through
 * {@link ClientAddressResolver} so callers behind a configured proxy are told
 * apart rather than sharing one bucket. With no trusted proxy configured the
 * socket address is used and forwarding headers are ignored, because a caller
 * can set those freely.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private final StreamlineProperties.RateLimit settings;
    private final ChatMetrics metrics;
    private final ClientAddressResolver addresses;
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(StreamlineProperties properties, ChatMetrics metrics,
            ClientAddressResolver addresses) {
        this.settings = properties.getRateLimit();
        this.metrics = metrics;
        this.addresses = addresses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!settings.isEnabled()) {
            return true;
        }

        // Probes stay unthrottled for the same reason they stay unauthenticated:
        // a load balancer polling them must never be told to slow down.
        String path = request.getRequestURI();
        return !path.startsWith("/api");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        RateLimiter limiter = limiters.computeIfAbsent(clientKey(request),
                key -> new RateLimiter(settings.getApiRequestsPerSecond(),
                        settings.getApiBurstSize()));

        if (!limiter.tryAcquire()) {
            metrics.recordRateLimited();
            log.warn("Rate limited {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"status":429,"error":"Too Many Requests","message":"Slow down"}""");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * @return the address a bucket is kept for, resolved through any trusted
     *         proxy so callers sharing one do not share a bucket
     */
    private String clientKey(HttpServletRequest request) {
        return addresses.resolve(request);
    }

    /** @return how many buckets are being tracked, for tests and diagnostics */
    int trackedClients() {
        return limiters.size();
    }
}
