package server.configure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gauges reporting how much room capacity is in use.
 *
 * A counter says how much traffic has been served; these say how close the
 * server is to refusing new rooms, which no counter reveals.
 */
class RoomGaugesTest {

    private static final AtomicInteger IDS = new AtomicInteger();

    private MeterRegistry registry;
    private ChatServerWSHandler handler;
    private StreamlineProperties properties;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new StreamlineProperties();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true);

        new RoomGauges(registry, handler, properties);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private WebSocketSession join(String room, String username) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s" + IDS.incrementAndGet());
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));

        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"%s","message":"hello there","timestamp":"2026-08-09T10:00:00Z","messageType":"JOIN"}
                """.formatted(username)));
        return session;
    }

    @Test
    void anIdleServerReportsNoRooms() {
        assertThat(gauge("streamline.rooms.active")).isZero();
        assertThat(gauge("streamline.rooms.sessions")).isZero();
    }

    @Test
    void activeRoomsTrackJoins() throws IOException {
        join("one", "alice");
        join("two", "bob");

        assertThat(gauge("streamline.rooms.active")).isEqualTo(2);
    }

    @Test
    void sessionsCountEveryMemberNotEveryRoom() throws IOException {
        join("one", "alice");
        join("one", "bob");

        assertThat(gauge("streamline.rooms.active")).isEqualTo(1);
        assertThat(gauge("streamline.rooms.sessions")).isEqualTo(2);
    }

    @Test
    void gaugesFallWhenAConnectionCloses() throws IOException {
        WebSocketSession alice = join("one", "alice");
        assertThat(gauge("streamline.rooms.active")).isEqualTo(1);

        handler.afterConnectionClosed(alice, CloseStatus.NORMAL);

        // a gauge that only ever rises would never show capacity being released
        assertThat(gauge("streamline.rooms.active")).isZero();
        assertThat(gauge("streamline.rooms.sessions")).isZero();
    }

    @Test
    void theConfiguredCapsAreReported() {
        properties.getLimits().setMaxRooms(250);
        properties.getLimits().setMaxMembersPerRoom(40);

        // read through the live properties, so an alert can compare use to cap
        assertThat(gauge("streamline.rooms.limit")).isEqualTo(250);
        assertThat(gauge("streamline.rooms.members_limit")).isEqualTo(40);
    }

    @Test
    void anUnlimitedCapIsReportedAsZero() {
        properties.getLimits().setMaxRooms(0);

        assertThat(gauge("streamline.rooms.limit")).isZero();
    }

    @Test
    void everyGaugeCarriesADescription() {
        for (String name : new String[] {"streamline.rooms.active", "streamline.rooms.sessions",
                "streamline.rooms.limit", "streamline.rooms.members_limit"}) {
            assertThat(registry.get(name).gauge().getId().getDescription())
                    .as("%s should be self-describing in a metrics scrape", name)
                    .isNotBlank();
        }
    }
}
