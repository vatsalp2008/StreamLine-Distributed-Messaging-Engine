package server.configure;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import server.service.ChatService;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Send-side state is released when a session goes away.
 *
 * Each open session holds a queueing decorator. Entries are normally dropped in
 * afterConnectionClosed, but that callback is not guaranteed for every abnormal
 * termination, and a missed one leaks for the life of the process.
 */
class SenderSweepTest {

    private static final AtomicInteger IDS = new AtomicInteger();

    private ChatServerWSHandler handler;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(),
                new ChatMetrics(new SimpleMeterRegistry()));
    }

    private WebSocketSession session(boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(open);
        when(session.getId()).thenReturn("s" + IDS.incrementAndGet());
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/general"));
        return session;
    }

    @Test
    void connectingRegistersSendSideState() {
        handler.afterConnectionEstablished(session(true));

        assertThat(handler.trackedSenders()).isEqualTo(1);
    }

    @Test
    void closingNormallyReleasesIt() {
        WebSocketSession session = session(true);
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.trackedSenders()).isZero();
    }

    @Test
    void theSweepDropsSessionsThatClosedWithoutNotice() {
        // a session whose close callback never arrived
        handler.afterConnectionEstablished(session(false));
        assertThat(handler.trackedSenders()).isEqualTo(1);

        handler.sweepClosedSenders();

        assertThat(handler.trackedSenders()).isZero();
    }

    @Test
    void theSweepLeavesOpenSessionsAlone() {
        handler.afterConnectionEstablished(session(true));

        handler.sweepClosedSenders();

        assertThat(handler.trackedSenders()).isEqualTo(1);
    }

    @Test
    void theSweepKeepsTheLiveOnesAndDropsTheRest() {
        handler.afterConnectionEstablished(session(true));
        handler.afterConnectionEstablished(session(false));
        handler.afterConnectionEstablished(session(false));

        handler.sweepClosedSenders();

        assertThat(handler.trackedSenders()).isEqualTo(1);
    }

    @Test
    void sweepingAnEmptyMapIsHarmless() {
        handler.sweepClosedSenders();

        assertThat(handler.trackedSenders()).isZero();
    }

    @Test
    void aSessionWithNoIdIsNotTracked() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/general"));

        // must not throw on the way in, and nothing to key an entry on
        handler.afterConnectionEstablished(session);

        assertThat(handler.trackedSenders()).isZero();
    }
}
