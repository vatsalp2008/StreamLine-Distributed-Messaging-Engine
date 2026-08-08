package bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerResponseTest {

    private static String frame(String status, String message) {
        return "{\"status\":\"%s\",\"serverTimestamp\":\"2026-08-08T10:00:00Z\",\"message\":\"%s\"}"
                .formatted(status, message);
    }

    @Test
    void anAcknowledgementCountsAsAccepted() {
        assertTrue(ServerResponse.isAccepted(frame("OK", "alice: hello")));
    }

    @Test
    void anErrorDoesNotCountAsAccepted() {
        assertFalse(ServerResponse.isAccepted(frame("ERROR", "Validation failed")));
    }

    @Test
    void theOtherServerFramesCountAsAccepted() {
        // history, fan-out and presence all mean the connection is working
        assertTrue(ServerResponse.isAccepted(frame("BROADCAST", "bob: hi")));
        assertTrue(ServerResponse.isAccepted(frame("HISTORY", "carol: earlier")));
        assertTrue(ServerResponse.isAccepted(frame("PRESENCE", "alice,bob")));
    }

    @Test
    void fieldOrderDoesNotMatter() {
        // the server builds the body from a HashMap, so order is not guaranteed
        String reordered = "{\"message\":\"nope\",\"status\":\"ERROR\"}";

        assertFalse(ServerResponse.isAccepted(reordered));
    }

    @Test
    void aMessageBodyMentioningErrorIsNotMistakenForOne() {
        // a naive substring check would fail this
        assertTrue(ServerResponse.isAccepted(frame("OK", "alice: status ERROR happened")));
    }

    @Test
    void malformedJsonIsNotTreatedAsSuccess() {
        assertFalse(ServerResponse.isAccepted("not json at all"));
        assertFalse(ServerResponse.isAccepted("{\"status\""));
    }

    @Test
    void aFrameWithoutAStatusIsNotTreatedAsSuccess() {
        assertFalse(ServerResponse.isAccepted("{\"message\":\"hello\"}"));
    }

    @Test
    void emptyAndNullInputAreNotTreatedAsSuccess() {
        assertFalse(ServerResponse.isAccepted(null));
        assertFalse(ServerResponse.isAccepted(""));
    }

    @Test
    void statusIsExposedForReporting() {
        assertEquals("OK", ServerResponse.statusOf(frame("OK", "x")));
        assertEquals("ERROR", ServerResponse.statusOf(frame("ERROR", "x")));
        assertNull(ServerResponse.statusOf("{}"));
    }

    @Test
    void aJsonArrayIsHandledWithoutThrowing() {
        assertFalse(ServerResponse.isAccepted("[1,2,3]"));
    }
}
