package server.configure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The DELIVERED frame confirming a message reached storage.
 *
 * An OK says the server accepted a message; the write happens afterwards on
 * another thread and can still fail, so only this frame means durable.
 */
class DeliveryReceiptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private Validator validator;
    private ChatService chatService;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        chatService = mock(ChatService.class);
        registry = new SimpleMeterRegistry();
        when(chatService.saveMessage(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(77L));
    }

    private ChatServerWSHandler handler(boolean receiptsEnabled) {
        return new ChatServerWSHandler(validator, chatService, true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(),
                new ChatMetrics(registry),
                new StreamlineProperties.Identity(),
                new StreamlineProperties.Limits(),
                receiptsEnabled);
    }

    private WebSocketSession session(String room) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s" + IDS.incrementAndGet());
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));
        return session;
    }

    private void send(ChatServerWSHandler handler, WebSocketSession session, String type,
            String clientId) throws IOException {
        String correlation = clientId == null ? "" : ",\"clientId\":\"%s\"".formatted(clientId);
        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"alice","message":"hello there","timestamp":"2026-08-10T10:00:00Z","messageType":"%s"%s}
                """.formatted(type, correlation)));
    }

    private List<JsonNode> framesTo(WebSocketSession session) throws IOException {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());

        List<JsonNode> frames = new ArrayList<>();
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            frames.add(MAPPER.readTree(frame.getPayload().toString()));
        }
        return frames;
    }

    private List<JsonNode> framesOfStatus(WebSocketSession session, String status)
            throws IOException {
        return framesTo(session).stream()
                .filter(f -> status.equals(f.get("status").asText()))
                .toList();
    }

    @Test
    void aStoredMessageProducesAReceipt() throws IOException {
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-1");

        List<JsonNode> receipts = framesOfStatus(session, "DELIVERED");
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).get("message").asText()).isEqualTo("77");
    }

    @Test
    void theReceiptCarriesTheSendersCorrelationId() throws IOException {
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-42");

        // without it the sender cannot tell which message was stored
        assertThat(framesOfStatus(session, "DELIVERED").get(0).get("clientId").asText())
                .isEqualTo("m-42");
    }

    @Test
    void theReceiptFollowsTheAcknowledgement() throws IOException {
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-1");

        List<String> order = framesTo(session).stream()
                .map(f -> f.get("status").asText())
                .filter(s -> "OK".equals(s) || "DELIVERED".equals(s))
                .toList();

        // accepted first, durable second; the reverse would be a lie
        assertThat(order).endsWith("OK", "DELIVERED");
    }

    @Test
    void noReceiptIsSentWhenTheFeatureIsOff() throws IOException {
        ChatServerWSHandler handler = handler(false);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-1");

        assertThat(framesOfStatus(session, "DELIVERED")).isEmpty();
    }

    @Test
    void aFailedWriteIsReportedRatherThanLeavingTheSenderWaiting() throws IOException {
        when(chatService.saveMessage(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-1");

        List<JsonNode> errors = framesOfStatus(session, "ERROR");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("message").asText()).isEqualTo("Message was not stored");
        assertThat(errors.get(0).get("clientId").asText()).isEqualTo("m-1");
    }

    @Test
    void controlFramesProduceNoReceipt() throws IOException {
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");

        send(handler, session, "JOIN", "j-1");
        send(handler, session, "LEAVE", "l-1");

        // JOIN and LEAVE are never stored, so there is nothing to confirm
        assertThat(framesOfStatus(session, "DELIVERED")).isEmpty();
    }

    @Test
    void aRefusedMessageProducesNoReceipt() throws IOException {
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");

        // TEXT before JOIN is refused, so nothing is written
        send(handler, session, "TEXT", "m-1");

        assertThat(framesOfStatus(session, "DELIVERED")).isEmpty();
    }

    @Test
    void receiptsAreCounted() throws IOException {
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-1");
        send(handler, session, "TEXT", "m-2");

        assertThat(registry.get("streamline.receipts.sent").counter().count()).isEqualTo(2.0);
    }

    @Test
    void aBrokenPersistenceLayerDoesNotFailTheMessage() throws IOException {
        // a future that fails, rather than one completing with null
        when(chatService.saveMessage(any(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("pool gone")));
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-1");

        // the sender still gets its OK; tracking the write must not break it
        assertThat(framesOfStatus(session, "OK")).isNotEmpty();
    }

    @Test
    void aNullFutureDoesNotFailTheMessage() throws IOException {
        // defensive: a persistence layer returning nothing at all
        when(chatService.saveMessage(any(), anyString())).thenReturn(null);
        ChatServerWSHandler handler = handler(true);
        WebSocketSession session = session("general");
        send(handler, session, "JOIN", null);

        send(handler, session, "TEXT", "m-1");

        assertThat(framesOfStatus(session, "OK")).isNotEmpty();
    }
}
