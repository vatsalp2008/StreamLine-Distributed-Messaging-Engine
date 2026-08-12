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

    // ---------- direct replies ----------

    @Test
    void acknowledgementsAndRefusalsAreDirectReplies() {
        assertTrue(ServerResponse.isDirectReply(frame("OK", "alice: hello")));
        assertTrue(ServerResponse.isDirectReply(frame("ERROR", "Validation failed")));
    }

    @Test
    void unsolicitedPushesAreNotDirectReplies() {
        // these arrive because of other clients, not because of what we sent
        assertFalse(ServerResponse.isDirectReply(frame("BROADCAST", "bob: hi")));
        assertFalse(ServerResponse.isDirectReply(frame("HISTORY", "carol: earlier")));
        assertFalse(ServerResponse.isDirectReply(frame("PRESENCE", "alice,bob")));
    }

    @Test
    void malformedFramesAreNotDirectReplies() {
        assertFalse(ServerResponse.isDirectReply("not json"));
        assertFalse(ServerResponse.isDirectReply(null));
    }

    // ---------- correlation ids ----------

    @Test
    void theCorrelationIdIsExtracted() {
        String withId = "{\"status\":\"OK\",\"clientId\":\"m-42\",\"message\":\"ack\"}";

        assertEquals("m-42", ServerResponse.clientIdOf(withId));
    }

    @Test
    void aFrameWithoutACorrelationIdReportsNull() {
        assertNull(ServerResponse.clientIdOf(frame("OK", "ack")));
        assertNull(ServerResponse.clientIdOf("not json"));
        assertNull(ServerResponse.clientIdOf(null));
    }

    @Test
    void aRefusalStillCarriesItsCorrelationId() {
        String refusal = "{\"status\":\"ERROR\",\"clientId\":\"m-7\",\"message\":\"nope\"}";

        assertEquals("m-7", ServerResponse.clientIdOf(refusal));
        assertFalse(ServerResponse.isAccepted(refusal));
    }

    // ---------- delivery receipts ----------

    @Test
    void aReceiptIsNotADirectReply() {
        // DELIVERED arrives after the OK, once the write lands. Treating it as
        // the reply would let a sender count one message twice.
        assertFalse(ServerResponse.isDirectReply(frame("DELIVERED", "77")));
    }

    @Test
    void aReceiptStillCountsAsAccepted() {
        assertTrue(ServerResponse.isAccepted(frame("DELIVERED", "77")));
    }

    @Test
    void aReceiptCarriesTheCorrelationIdItConfirms() {
        String receipt = "{\"status\":\"DELIVERED\",\"clientId\":\"m-9\",\"message\":\"77\"}";

        assertEquals("m-9", ServerResponse.clientIdOf(receipt));
    }

    // ---------- redaction ----------

    @Test
    void aRedactionIsNotADirectReply() {
        // REDACTED arrives because somebody deleted a message, not because of
        // anything this client sent; releasing a waiting sender on it would
        // count a success that never happened
        assertFalse(ServerResponse.isDirectReply(frame("REDACTED", "77")));
    }

    @Test
    void aRedactionIsNotAFailure() {
        // it reports an event, not a refusal, so it must not read as an error
        assertTrue(ServerResponse.isAccepted(frame("REDACTED", "77")));
    }

    @Test
    void anUnknownFutureStatusIsNotADirectReply() {
        // the protocol gains frame types over time; anything unrecognised must
        // default to "not my acknowledgement" rather than releasing a sender
        assertFalse(ServerResponse.isDirectReply(frame("SOMETHING_NEW", "x")));
    }

    // ---------- edits ----------

    @Test
    void anEditIsNotADirectReply() {
        // EDITED arrives because somebody changed a stored message, not in
        // answer to anything this client sent
        assertFalse(ServerResponse.isDirectReply(frame("EDITED", "77:new text")));
    }

    @Test
    void anEditIsNotAFailure() {
        assertTrue(ServerResponse.isAccepted(frame("EDITED", "77:new text")));
    }
}
