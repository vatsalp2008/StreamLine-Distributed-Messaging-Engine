package client2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvReportWriterTest {

    @TempDir
    Path tempDir;

    private static MessageData sample(long timestamp, long latency) {
        return new MessageData(timestamp, "TEXT", latency, "OK", 3);
    }

    @Test
    void metricsFileHasAHeaderAndOneRowPerMessage() throws IOException {
        Path file = new CsvReportWriter(tempDir).writeMetrics(List.of(
                sample(1000, 12),
                sample(2000, 34)));

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertEquals("timestamp,messageType,latency,statusCode,roomId", lines.get(0));
        assertEquals("1000,TEXT,12,OK,3", lines.get(1));
        assertEquals("2000,TEXT,34,OK,3", lines.get(2));
    }

    @Test
    void metricsFileIsStillWrittenForAnEmptyRun() throws IOException {
        Path file = new CsvReportWriter(tempDir).writeMetrics(List.of());

        assertEquals(List.of("timestamp,messageType,latency,statusCode,roomId"),
                Files.readAllLines(file));
    }

    @Test
    void outputDirectoryIsCreatedIfMissing() throws IOException {
        Path nested = tempDir.resolve("nested/results");

        new CsvReportWriter(nested).writeMetrics(List.of(sample(1, 1)));

        assertTrue(Files.exists(nested.resolve(CsvReportWriter.METRICS_FILE)));
    }

    @Test
    void throughputReportIsSkippedWhenThereIsNoData() throws IOException {
        assertNull(new CsvReportWriter(tempDir).writeThroughput(List.of()));
    }

    @Test
    void messagesAreBucketedByTenSecondWindows() {
        int[] buckets = CsvReportWriter.bucketCounts(List.of(
                sample(0, 1),
                sample(5_000, 1),
                sample(9_999, 1),
                sample(10_000, 1),
                sample(35_000, 1)));

        // 3 in the first window, 1 in the second, an idle third, 1 in the fourth
        assertArrayEquals(new int[]{3, 1, 0, 1}, buckets);
    }

    @Test
    void bucketsAreRelativeToTheEarliestSampleNotZero() {
        int[] buckets = CsvReportWriter.bucketCounts(List.of(
                sample(1_000_000, 1),
                sample(1_005_000, 1)));

        assertArrayEquals(new int[]{2}, buckets);
    }

    @Test
    void unorderedTimestampsStillBucketCorrectly() {
        int[] buckets = CsvReportWriter.bucketCounts(List.of(
                sample(20_000, 1),
                sample(0, 1),
                sample(10_000, 1)));

        assertArrayEquals(new int[]{1, 1, 1}, buckets);
    }

    @Test
    void throughputFileSkipsEmptyBucketsAndReportsPerSecondRates() throws IOException {
        Path file = new CsvReportWriter(tempDir).writeThroughput(List.of(
                sample(0, 1),
                sample(1_000, 1),
                sample(25_000, 1)));

        List<String> lines = Files.readAllLines(file);
        assertEquals("Time_Seconds,Messages_Per_Second", lines.get(0));
        // 2 messages in the first 10s window, 1 in the third; the empty one is omitted
        assertEquals("0,0.2", lines.get(1));
        assertEquals("20,0.1", lines.get(2));
        assertEquals(3, lines.size());
    }
}
