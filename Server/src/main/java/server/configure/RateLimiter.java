package server.configure;

import java.util.function.LongSupplier;

/**
 * Token bucket limiting how fast one session may send messages.
 *
 * A bucket refills continuously at {@code permitsPerSecond} and holds at most
 * {@code burstSize} tokens, so a client may burst up to the bucket size and then
 * settles to the sustained rate. Refill is computed from elapsed time on each
 * call rather than by a background thread, which keeps one bucket per session
 * cheap even with tens of thousands of connections.
 *
 * Instances are per session and guarded by their own monitor, since a single
 * session's frames can be delivered on different container threads.
 */
public final class RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final double permitsPerSecond;
    private final double burstSize;
    private final LongSupplier nanoClock;

    private double availableTokens;
    private long lastRefillNanos;

    /**
     * @param permitsPerSecond sustained messages per second, must be positive
     * @param burstSize        maximum tokens the bucket holds, must be positive
     */
    public RateLimiter(double permitsPerSecond, int burstSize) {
        this(permitsPerSecond, burstSize, System::nanoTime);
    }

    /**
     * @param nanoClock source of monotonic time; tests supply their own so they
     *                  can exercise refill behaviour without sleeping
     */
    public RateLimiter(double permitsPerSecond, int burstSize, LongSupplier nanoClock) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive");
        }
        if (burstSize <= 0) {
            throw new IllegalArgumentException("burstSize must be positive");
        }

        this.permitsPerSecond = permitsPerSecond;
        this.burstSize = burstSize;
        this.nanoClock = nanoClock;
        this.availableTokens = burstSize;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    /**
     * @return true if a token was available and consumed, false if the caller
     *         has exceeded its allowance
     */
    public synchronized boolean tryAcquire() {
        refill();

        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * @return tokens currently available, for tests and diagnostics
     */
    public synchronized double availableTokens() {
        refill();
        return availableTokens;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }

        double refilled = (elapsed / (double) NANOS_PER_SECOND) * permitsPerSecond;
        availableTokens = Math.min(burstSize, availableTokens + refilled);
        lastRefillNanos = now;
    }
}
