package server.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounds on the replacement text of an edit.
 *
 * These mirror what the WebSocket protocol accepts, so a message cannot be
 * edited into a state it could never have been sent in.
 */
class EditRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private boolean isValid(String message) {
        return validator.validate(new EditRequest(message)).isEmpty();
    }

    @Test
    void ordinaryTextIsAccepted() {
        assertThat(isValid("corrected wording")).isTrue();
    }

    @Test
    void aSingleCharacterIsAccepted() {
        assertThat(isValid("x")).isTrue();
    }

    @Test
    void theMaximumLengthIsAccepted() {
        assertThat(isValid("x".repeat(500))).isTrue();
    }

    @Test
    void anEmptyMessageIsRejected() {
        // an edit to nothing is a deletion, and that has its own endpoint
        assertThat(isValid("")).isFalse();
    }

    @Test
    void aMissingMessageIsRejected() {
        assertThat(isValid(null)).isFalse();
    }

    @Test
    void anOverlongMessageIsRejected() {
        // the same 500-character bound the socket enforces
        assertThat(isValid("x".repeat(501))).isFalse();
    }

    @Test
    void whitespaceIsTreatedAsContent() {
        // the socket accepts it too; trimming here would diverge from that
        assertThat(isValid(" ")).isTrue();
    }
}
