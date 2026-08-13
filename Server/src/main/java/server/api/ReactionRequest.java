package server.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Who is reacting, and with what.
 *
 * The username is supplied rather than inferred: the API has no session, so
 * there is nothing to infer it from. That means it is a claim, not proof — the
 * same limitation edit and delete already have.
 *
 * @param username who is reacting
 * @param emoji    the reaction, short and bounded so this cannot become a
 *                 second message field
 */
public record ReactionRequest(
        @NotNull
        @Size(min = 3, max = 20)
        @Pattern(regexp = "^[a-zA-Z0-9]+$")
        String username,

        @NotNull
        @Size(min = 1, max = 16)
        String emoji) {
}
