package server.api;

import java.time.Instant;

/**
 * Error body returned by the HTTP API.
 *
 * @param status    HTTP status code
 * @param error     short reason phrase
 * @param message   what the caller can act on
 * @param timestamp when the failure was produced
 */
public record ApiError(int status, String error, String message, Instant timestamp) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(status, error, message, Instant.now());
    }
}
