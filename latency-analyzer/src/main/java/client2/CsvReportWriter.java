package client2;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the two CSV reports produced by a latency run.
 *
 * Pulled out of MainPhase so the bucketing logic can be tested without running a
 * benchmark, and so the output directory is a parameter rather than a hardcoded
 * relative path. Writers are closed through try-with-resources, which the
 * previous inline version skipped whenever writing threw.
 */
public final class CsvReportWriter {

    static final String METRICS_FILE = "MessageMetrics.csv";
    static final String THROUGHPUT_FILE = "Throughput.csv";

    /** Width of a throughput bucket, in milliseconds. */
    static final long BUCKET_MILLIS = 10_000;

    private final Path outputDir;

    public CsvReportWriter(Path outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * Writes one row per measured message.
     *
     * @param data -List<MessageData>, the collected measurements
     * @return the file written
     */
    public Path writeMetrics(List<MessageData> data) throws IOException {
        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(METRICS_FILE);

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(target))) {
            writer.println("timestamp,messageType,latency,statusCode,roomId");
            for (MessageData row : data) {
                writer.println(row.toCsvRow());
            }
        }
        return target;
    }

    /**
     * Writes messages-per-second bucketed over the run, skipping empty buckets.
     *
     * @param data -List<MessageData>, the collected measurements
     * @return the file written, or null when there is nothing to report
     */
    public Path writeThroughput(List<MessageData> data) throws IOException {
        if (data.isEmpty()) {
            return null;
        }

        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(THROUGHPUT_FILE);
        int[] buckets = bucketCounts(data);

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(target))) {
            writer.println("Time_Seconds,Messages_Per_Second");
            for (int i = 0; i < buckets.length; i++) {
                if (buckets[i] > 0) {
                    long seconds = i * (BUCKET_MILLIS / 1000);
                    writer.println(seconds + "," + (buckets[i] / (BUCKET_MILLIS / 1000.0)));
                }
            }
        }
        return target;
    }

    /**
     * @param data -List<MessageData>, the collected measurements
     * @return message counts per fixed-width time bucket, indexed from the first sample
     */
    static int[] bucketCounts(List<MessageData> data) {
        if (data.isEmpty()) {
            return new int[0];
        }

        long firstTime = Long.MAX_VALUE;
        long lastTime = Long.MIN_VALUE;
        for (MessageData row : data) {
            firstTime = Math.min(firstTime, row.getTimestamp());
            lastTime = Math.max(lastTime, row.getTimestamp());
        }

        int bucketCount = (int) ((lastTime - firstTime) / BUCKET_MILLIS) + 1;
        int[] counts = new int[bucketCount];
        for (MessageData row : data) {
            counts[(int) ((row.getTimestamp() - firstTime) / BUCKET_MILLIS)]++;
        }
        return counts;
    }
}
