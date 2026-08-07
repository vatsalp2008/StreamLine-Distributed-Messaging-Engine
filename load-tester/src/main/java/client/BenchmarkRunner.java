package client;

import bench.GenerateMessage;
import bench.model.ChatMessage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Drives one benchmark phase: generate messages, fan them out across sender
 * threads, and report the result.
 *
 * The warm-up and main phases differ only in thread and message counts, so both
 * are configurations of this runner rather than separate copies of the logic.
 */
public class BenchmarkRunner {

    private static final int QUEUE_CAPACITY = 100000;
    private static final long QUEUE_FILL_MILLIS = 2000;

    private final String label;
    private final String serverUrl;
    private final int threads;
    private final int totalMessages;
    private final int messagesPerThread;

    // contended by every sender thread, so keep increments off a shared monitor
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failCount = new LongAdder();
    private final LongAdder connectionCount = new LongAdder();
    private final LongAdder reconnectCount = new LongAdder();

    private long startTime;
    private long endTime;

    /**
     * @param label         -String, name shown in the result banner
     * @param serverUrl     -String, WebSocket base URL of the server under test
     * @param threads       -int, number of concurrent senders
     * @param totalMessages -int, messages to send across all threads
     */
    public BenchmarkRunner(String label, String serverUrl, int threads, int totalMessages) {
        this.label = label;
        this.serverUrl = serverUrl;
        this.threads = threads;
        this.totalMessages = totalMessages;
        this.messagesPerThread = totalMessages / threads;
    }

    public BenchmarkResult run() {
        printConfiguration();

        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        CountDownLatch latch = new CountDownLatch(threads);

        Thread generator = new Thread(new GenerateMessage(queue, totalMessages));
        generator.start();

        // give the generator a head start so senders are not starved at the outset
        sleepQuietly(QUEUE_FILL_MILLIS);

        startTime = System.currentTimeMillis();
        System.out.println("\nStarting " + threads + " worker threads...\n");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 1; i <= threads; i++) {
            pool.submit(newSender(queue, latch, i));
        }

        try {
            System.out.println("Waiting for Threads to complete sending...\n");
            latch.await();
            endTime = System.currentTimeMillis();
            generator.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }

        BenchmarkResult result = result();
        printResults(result);
        return result;
    }

    /**
     * @return the counters gathered during the run
     */
    private BenchmarkResult result() {
        return new BenchmarkResult(label, successCount.sum(), failCount.sum(),
                connectionCount.sum(), reconnectCount.sum(), endTime - startTime);
    }

    private MSGSenderThread newSender(BlockingQueue<ChatMessage> queue, CountDownLatch latch, int id) {
        return new MSGSenderThread(serverUrl, queue, messagesPerThread, latch, id) {
            @Override
            public void onSuccess() {
                successCount.increment();
            }

            @Override
            public void onFail() {
                failCount.increment();
            }

            @Override
            public void onConnect() {
                connectionCount.increment();
            }

            @Override
            public void onReconnect() {
                reconnectCount.increment();
            }
        };
    }

    private void printConfiguration() {
        System.out.println("Configuration Detail:");
        System.out.println("  Server: " + serverUrl);
        System.out.println("  Threads: " + threads);
        System.out.println("  Messages per thread: " + messagesPerThread);
        System.out.println("  Total messages: " + totalMessages);
        System.out.println("Building up message queue...");
    }

    private void printResults(BenchmarkResult result) {
        System.out.println("\n---------  " + result.label() + " ----------");
        System.out.println("Successful messages sent: " + result.successes());
        System.out.println("Failed messages: " + result.failures());
        System.out.println("Total runtime: " + result.durationMillis() + " ms");
        System.out.println("Throughput: "
                + String.format("%.2f", result.throughputPerSecond()) + " msg/sec");
        System.out.println("Total Connections: " + result.connections());
        System.out.println("Reconnections: " + result.reconnects());
        System.out.println("-----------------------------------------\n");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
