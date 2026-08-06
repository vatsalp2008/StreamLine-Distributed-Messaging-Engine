package client;

/**
 * Exponential backoff delays for retrying a failed send.
 *
 * The delay was previously computed inline as Math.pow(2, attempt) * 40, which
 * grows without limit and could not be exercised without actually sleeping.
 * Here the progression is capped and the calculation is separated from the
 * sleeping, so it can be tested directly.
 */
public final class Backoff {

    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public Backoff(long baseDelayMillis, long maxDelayMillis) {
        if (baseDelayMillis <= 0) {
            throw new IllegalArgumentException("baseDelayMillis must be positive");
        }
        if (maxDelayMillis < baseDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must be at least baseDelayMillis");
        }

        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    /**
     * @param attempt zero-based retry number
     * @return how long to wait before the next attempt, capped at the maximum
     */
    public long delayForAttempt(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }

        // shift rather than Math.pow, and stop early so the doubling cannot overflow
        if (attempt >= Long.numberOfLeadingZeros(baseDelayMillis)) {
            return maxDelayMillis;
        }

        long delay = baseDelayMillis << attempt;
        return delay <= 0 ? maxDelayMillis : Math.min(delay, maxDelayMillis);
    }

    /**
     * Sleeps for the delay of this attempt.
     *
     * @return false if the thread was interrupted while waiting, in which case
     *         the interrupt flag is restored and the caller should stop retrying
     */
    public boolean sleepForAttempt(int attempt) {
        try {
            Thread.sleep(delayForAttempt(attempt));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
