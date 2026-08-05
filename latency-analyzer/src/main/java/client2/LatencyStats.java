package client2;

import java.util.Arrays;
import java.util.Collection;

/**
 * Summary statistics over a set of measured latencies.
 *
 * Percentiles use the nearest-rank method: the p-th percentile is the smallest
 * value at or below which at least p percent of the samples fall. The previous
 * inline calculation indexed with (int)(size * 0.95), which lands one position
 * too high and reports the maximum as p95 for small samples.
 */
public final class LatencyStats {

    private final long[] sorted;

    private LatencyStats(long[] sorted) {
        this.sorted = sorted;
    }

    /**
     * @param latencies -Collection<Long>, Representing the measured latencies in ms
     * @return -LatencyStats over a defensive, sorted copy of the input
     */
    public static LatencyStats of(Collection<Long> latencies) {
        long[] values = new long[latencies.size()];
        int i = 0;
        for (Long latency : latencies) {
            values[i++] = latency;
        }
        Arrays.sort(values);
        return new LatencyStats(values);
    }

    public boolean isEmpty() {
        return sorted.length == 0;
    }

    public int count() {
        return sorted.length;
    }

    public double mean() {
        if (isEmpty()) {
            return 0.0;
        }

        long sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return (double) sum / sorted.length;
    }

    public long min() {
        return isEmpty() ? 0 : sorted[0];
    }

    public long max() {
        return isEmpty() ? 0 : sorted[sorted.length - 1];
    }

    /**
     * @return the middle value, or the mean of the two middle values for an even count
     */
    public double median() {
        if (isEmpty()) {
            return 0.0;
        }

        int middle = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[middle];
        }
        return (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    /**
     * @param percent -double, in the range 0 to 100
     * @return -long, the nearest-rank percentile, or 0 when there are no samples
     */
    public long percentile(double percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percentile must be within 0..100, got " + percent);
        }
        if (isEmpty()) {
            return 0;
        }

        int rank = (int) Math.ceil((percent / 100.0) * sorted.length);
        int index = Math.min(Math.max(rank - 1, 0), sorted.length - 1);
        return sorted[index];
    }
}
