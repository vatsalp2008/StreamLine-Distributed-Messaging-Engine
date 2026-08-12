package server.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * New body for a message being edited.
 *
 * The length bound matches the one the WebSocket protocol enforces, so a message
 * cannot be edited into a state it could never have been sent in.
 *
 * @param message the replacement text
 */
public record EditRequest(
        @NotNull
        @Size(min = 1, max = 500)
        String message) {
}
