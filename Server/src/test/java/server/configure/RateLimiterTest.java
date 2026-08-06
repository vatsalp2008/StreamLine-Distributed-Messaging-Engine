package server.configure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    /** Clock the test drives by hand, so nothing has to sleep. */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private long nanos;

        void advanceMillis(long millis) {
            nanos += millis * 1_000_000L;
        }

        @Override
        public long getAsLong() {
            return nanos;
        }
    }

    /** A limiter plus the clock driving it. */
    private record Fixture(RateLimiter limiter, FakeClock clock) {
        static Fixture of(double permitsPerSecond, int burstSize) {
            FakeClock clock = new FakeClock();
            return new Fixture(new RateLimiter(permitsPerSecond, burstSize, clock), clock);
        }

        boolean tryAcquire() {
            return limiter.tryAcquire();
        }

        double availableTokens() {
            return limiter.availableTokens();
        }

        void advanceMillis(long millis) {
            clock.advanceMillis(millis);
        }
    }

    @Test
    void aFreshBucketAllowsAFullBurst() {
        Fixture limiter = Fixture.of(10, 5);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire()).as("burst message %d", i).isTrue();
        }
    }

    @Test
    void theBucketIsExhaustedAfterTheBurst() {
        Fixture limiter = Fixture.of(10, 3);

        limiter.tryAcquire();
        limiter.tryAcquire();
        limiter.tryAcquire();

        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void tokensRefillOverTime() {
        Fixture limiter = Fixture.of(10, 2);
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.tryAcquire()).isFalse();

        // 10 per second means one token every 100ms
        limiter.advanceMillis(100);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void refillIsCappedAtTheBurstSize() {
        Fixture limiter = Fixture.of(10, 3);
        limiter.tryAcquire();
        limiter.tryAcquire();
        limiter.tryAcquire();

        // idle far longer than it takes to refill; the bucket must not overflow
        limiter.advanceMillis(60_000);

        assertThat(limiter.availableTokens()).isEqualTo(3.0);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void sustainedRateIsHonouredOverALongerWindow() {
        Fixture limiter = Fixture.of(5, 1);
        assertThat(limiter.tryAcquire()).isTrue();

        int allowed = 0;
        // one second in 100ms steps at 5/sec should permit about 5 more
        for (int i = 0; i < 10; i++) {
            limiter.advanceMillis(100);
            if (limiter.tryAcquire()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void fractionalRatesBelowOnePerSecondWork() {
        Fixture limiter = Fixture.of(0.5, 1);
        assertThat(limiter.tryAcquire()).isTrue();

        limiter.advanceMillis(1_000);
        assertThat(limiter.tryAcquire()).as("only half a token after 1s").isFalse();

        limiter.advanceMillis(1_000);
        assertThat(limiter.tryAcquire()).as("a full token after 2s").isTrue();
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new RateLimiter(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCallersNeverExceedTheBudget() throws Exception {
        int burst = 100;
        // no refill during the test window, so the burst is the whole budget
        RateLimiter limiter = new RateLimiter(0.0001, burst);

        int threads = 16;
        java.util.concurrent.atomic.AtomicInteger granted =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int attempt = 0; attempt < 50; attempt++) {
                        if (limiter.tryAcquire()) {
                            granted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        assertThat(granted.get()).isEqualTo(burst);
    }
}
