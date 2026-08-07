package client;

/**
 * What a benchmark phase measured.
 *
 * The runner previously only printed its counters, so nothing could assert on
 * the outcome and the numbers were unavailable to any caller.
 *
 * @param label            name of the phase
 * @param successes        messages acknowledged by the server
 * @param failures         messages that exhausted their retries
 * @param connections      successful connections opened
 * @param reconnects       reconnections after a dropped connection
 * @param durationMillis   wall-clock time of the send phase
 */
public record BenchmarkResult(
        String label,
        long successes,
        long failures,
        long connections,
        long reconnects,
        long durationMillis) {

    /**
     * @return acknowledged messages per second, or 0 when the run was too short
     *         to measure
     */
    public double throughputPerSecond() {
        return durationMillis > 0 ? (successes * 1000.0) / durationMillis : 0.0;
    }
}
