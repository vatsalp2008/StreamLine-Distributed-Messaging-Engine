package server.configure;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Captures what the MDC held while the request was being handled. */
    private AtomicReference<String> runAndCapture() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);
        return seen;
    }

    @Test
    void generatesAnIdWhenTheCallerSuppliesNone() throws Exception {
        String duringRequest = runAndCapture().get();

        assertThat(duringRequest).isNotBlank();
        assertThat(UUID.fromString(duringRequest)).isNotNull();
    }

    @Test
    void echoesTheIdBackOnTheResponse() throws Exception {
        String duringRequest = runAndCapture().get();

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(duringRequest);
    }

    @Test
    void honoursAnIdSuppliedByTheCaller() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "upstream-trace-7");

        assertThat(runAndCapture().get()).isEqualTo("upstream-trace-7");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("upstream-trace-7");
    }

    @Test
    void trimsSurroundingWhitespace() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "  trace-9  ");

        assertThat(runAndCapture().get()).isEqualTo("trace-9");
    }

    @Test
    void blankSuppliedIdFallsBackToAGeneratedOne() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "   ");

        assertThat(UUID.fromString(runAndCapture().get())).isNotNull();
    }

    @Test
    void anIdContainingControlCharactersIsReplaced() throws Exception {
        // a newline would let a caller forge extra log lines
        request.addHeader(CorrelationIdFilter.HEADER, "bad\ninjected ERROR fake");

        String used = runAndCapture().get();

        assertThat(used).doesNotContain("\n").doesNotContain("injected");
        assertThat(UUID.fromString(used)).isNotNull();
    }

    @Test
    void anOverlongIdIsReplaced() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "x".repeat(500));

        String used = runAndCapture().get();

        assertThat(used).hasSizeLessThanOrEqualTo(64);
        assertThat(UUID.fromString(used)).isNotNull();
    }

    @Test
    void theMdcIsClearedAfterTheRequest() throws Exception {
        runAndCapture();

        // container threads are reused, so a leftover value would mislabel the next request
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void theMdcIsClearedEvenWhenTheChainThrows() {
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("handler blew up");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, exploding))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void eachRequestGetsItsOwnGeneratedId() throws Exception {
        String first = runAndCapture().get();

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        String second = runAndCapture().get();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theFilterRunsOncePerRequest() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        org.mockito.Mockito.verify(chain).doFilter(request, response);
    }
}
