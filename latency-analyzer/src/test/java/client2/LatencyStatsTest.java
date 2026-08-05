package client2;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyStatsTest {

    private static LatencyStats of(long... values) {
        List<Long> list = new ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return LatencyStats.of(list);
    }

    @Test
    void emptyInputReportsZeroesInsteadOfFailing() {
        LatencyStats stats = of();

        assertTrue(stats.isEmpty());
        assertEquals(0, stats.count());
        assertEquals(0.0, stats.mean());
        assertEquals(0.0, stats.median());
        assertEquals(0, stats.min());
        assertEquals(0, stats.max());
        assertEquals(0, stats.percentile(99));
    }

    @Test
    void unsortedInputIsHandled() {
        LatencyStats stats = of(50, 10, 30, 20, 40);

        assertEquals(10, stats.min());
        assertEquals(50, stats.max());
        assertEquals(30.0, stats.median());
        assertEquals(30.0, stats.mean());
    }

    @Test
    void medianAveragesTheTwoMiddleValuesForEvenCounts() {
        assertEquals(25.0, of(10, 20, 30, 40).median());
    }

    @Test
    void percentilesUseNearestRank() {
        // 1..100, so the p-th percentile is exactly p
        long[] values = new long[100];
        for (int i = 0; i < 100; i++) {
            values[i] = i + 1;
        }
        LatencyStats stats = of(values);

        assertEquals(50, stats.percentile(50));
        assertEquals(95, stats.percentile(95));
        assertEquals(99, stats.percentile(99));
        assertEquals(100, stats.percentile(100));
    }

    @Test
    void p95IsNotTheMaximumForSmallSamples() {
        // the old (int)(size * 0.95) indexing returned the max here
        LatencyStats stats = of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 9999);

        assertEquals(19, stats.percentile(95));
        assertEquals(9999, stats.max());
    }

    @Test
    void singleSampleIsItsOwnPercentile() {
        LatencyStats stats = of(7);

        assertEquals(7, stats.percentile(1));
        assertEquals(7, stats.percentile(99));
        assertEquals(7.0, stats.median());
    }

    @Test
    void outOfRangePercentileIsRejected() {
        LatencyStats stats = of(1, 2, 3);

        assertThrows(IllegalArgumentException.class, () -> stats.percentile(-1));
        assertThrows(IllegalArgumentException.class, () -> stats.percentile(101));
    }

    @Test
    void inputCollectionIsNotMutated() {
        List<Long> input = new ArrayList<>(List.of(3L, 1L, 2L));
        LatencyStats.of(input);

        assertEquals(List.of(3L, 1L, 2L), input);
    }
}
