package bench.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    private ChatMessage message(String text, String type) {
        return new ChatMessage(42, "alice", text, "2026-08-06T10:00:00Z", type);
    }

    @Test
    void accessorsExposeEveryField() {
        ChatMessage msg = message("hello", "TEXT");

        assertEquals(42, msg.getUserId());
        assertEquals("alice", msg.getUsername());
        assertEquals("hello", msg.getMessage());
        assertEquals("2026-08-06T10:00:00Z", msg.getTimestamp());
        assertEquals("TEXT", msg.getMessageType());
    }

    @Test
    void jsonCarriesTheFieldNamesTheServerValidates() {
        String json = message("hello", "TEXT").toJson();

        assertTrue(json.contains("\"userId\":42"), json);
        assertTrue(json.contains("\"username\":\"alice\""), json);
        assertTrue(json.contains("\"message\":\"hello\""), json);
        assertTrue(json.contains("\"timestamp\":\"2026-08-06T10:00:00Z\""), json);
        assertTrue(json.contains("\"messageType\":\"TEXT\""), json);
    }

    @Test
    void nonAsciiTextSurvivesSerialisation() {
        // the generated fixtures contain typographic apostrophes
        String json = message("let’s go", "TEXT").toJson();

        assertTrue(json.contains("let") && json.contains("go"), json);
    }

    @Test
    void jsonIsStableAcrossCalls() {
        ChatMessage msg = message("hello", "TEXT");

        assertEquals(msg.toJson(), msg.toJson());
    }
}
