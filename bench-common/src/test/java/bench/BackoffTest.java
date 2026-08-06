package bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffTest {

    @Test
    void delayDoublesWithEachAttempt() {
        Backoff backoff = new Backoff(40, 10_000);

        assertEquals(40, backoff.delayForAttempt(0));
        assertEquals(80, backoff.delayForAttempt(1));
        assertEquals(160, backoff.delayForAttempt(2));
        assertEquals(320, backoff.delayForAttempt(3));
    }

    @Test
    void delayStopsGrowingAtTheCap() {
        Backoff backoff = new Backoff(40, 200);

        assertEquals(40, backoff.delayForAttempt(0));
        assertEquals(80, backoff.delayForAttempt(1));
        assertEquals(160, backoff.delayForAttempt(2));
        assertEquals(200, backoff.delayForAttempt(3));
        assertEquals(200, backoff.delayForAttempt(4));
    }

    @Test
    void aLargeAttemptNumberCannotOverflowIntoANegativeDelay() {
        Backoff backoff = new Backoff(40, 2000);

        // the old Math.pow(2, attempt) form grew without bound; shifting far
        // enough would wrap to a negative value and break Thread.sleep
        for (int attempt = 0; attempt < 200; attempt++) {
            long delay = backoff.delayForAttempt(attempt);
            assertTrue(delay > 0, "attempt " + attempt + " produced " + delay);
            assertTrue(delay <= 2000, "attempt " + attempt + " exceeded the cap: " + delay);
        }
    }

    @Test
    void aVeryLargeBaseStillRespectsTheCap() {
        Backoff backoff = new Backoff(Long.MAX_VALUE / 4, Long.MAX_VALUE);

        assertTrue(backoff.delayForAttempt(0) > 0);
        assertTrue(backoff.delayForAttempt(5) > 0);
        assertTrue(backoff.delayForAttempt(64) > 0);
    }

    @Test
    void negativeAttemptsAreRejected() {
        Backoff backoff = new Backoff(40, 2000);

        assertThrows(IllegalArgumentException.class, () -> backoff.delayForAttempt(-1));
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(-5, 100));
        // a cap below the base delay would silently shorten the first wait
        assertThrows(IllegalArgumentException.class, () -> new Backoff(100, 50));
    }

    @Test
    void capEqualToBaseIsAllowedAndGivesAFixedDelay() {
        Backoff backoff = new Backoff(50, 50);

        assertEquals(50, backoff.delayForAttempt(0));
        assertEquals(50, backoff.delayForAttempt(1));
        assertEquals(50, backoff.delayForAttempt(9));
    }

    @Test
    void sleepingWaitsAtLeastTheComputedDelay() {
        Backoff backoff = new Backoff(30, 30);

        long start = System.nanoTime();
        boolean completed = backoff.sleepForAttempt(0);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(completed, "sleep should report completion when not interrupted");
        // allow for timer granularity rather than demanding an exact 30ms
        assertTrue(elapsedMillis >= 25, "slept only " + elapsedMillis + "ms");
    }

    @Test
    void sleepingReportsInterruptionAndRestoresTheFlag() throws Exception {
        Backoff backoff = new Backoff(5000, 5000);
        boolean[] result = new boolean[1];
        boolean[] flagStillSet = new boolean[1];

        Thread worker = new Thread(() -> {
            result[0] = backoff.sleepForAttempt(0);
            flagStillSet[0] = Thread.currentThread().isInterrupted();
        });

        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(2000);

        // callers rely on the false return to stop retrying
        assertTrue(!result[0], "interrupted sleep should return false");
        assertTrue(flagStillSet[0], "interrupt flag should be restored for callers upstream");
    }
}
