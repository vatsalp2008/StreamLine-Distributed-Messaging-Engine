package server.configure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id on every request so log lines can be tied together.
 *
 * With structured logging the id becomes a field, which is what makes it
 * possible to pull out every line belonging to one request. An inbound
 * X-Correlation-Id is honoured so a trace started upstream keeps its identity;
 * otherwise a fresh one is generated. The id is echoed back on the response so
 * a caller can quote it when reporting a problem.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Bounded so a hostile caller cannot push arbitrary volume into every log line. */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String correlationId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // container threads are pooled, so the value must not leak into the
            // next request handled by this thread
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * @param supplied the inbound header value, may be null
     * @return a safe id: the caller's when usable, otherwise a generated one
     */
    private String sanitise(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }

        // control characters would corrupt a log line, so only accept plain ids
        String trimmed = supplied.trim();
        if (trimmed.length() > MAX_LENGTH || !trimmed.matches("[A-Za-z0-9._-]+")) {
            return UUID.randomUUID().toString();
        }

        return trimmed;
    }
}
