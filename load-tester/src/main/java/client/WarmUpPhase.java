package client;

import client.model.ChatMessage;
import java.util.concurrent.*;

/**
 * Class WarmUpPhase for Test
 */
public class WarmUpPhase {


    private static final String Server_Url = TestConfig.serverUrl();
    private static final int Threads = TestConfig.threads(32);
    private static final int TotalMessages = TestConfig.totalMessages(32000);
    private static final int ThreadMessages = TotalMessages/Threads;

    // Data variables
    private static int successCount = 0;
    private static int failCount = 0;
    private static int connectionCount = 0;
    private static int reconnectCount = 0;
    private static long startTime = 0;
    private static long endTime = 0;

    public static void main(String[] args) {
        runPhase1();
    }

    private static synchronized void addSuccess() {
        successCount++;
    }

    private static synchronized void addFail() {
        failCount++;
    }

    private static synchronized void addConnection() {
        connectionCount++;
    }

    private static synchronized void addReconnect() {
        reconnectCount++;
    }

    private static void startTimer() {
        startTime = System.currentTimeMillis();
    }

    private static void stopTimer() {
        endTime = System.currentTimeMillis();
    }

    private static long getDuration() {
        return endTime - startTime;
    }

    private static double getThroughput() {
        long duration = getDuration();
        if (duration <= 0) return 0.0;
        return (successCount * 1000.0) / duration;
    }

    /**
     * Phase 1 Run for Load Testing
     */
    private static void runPhase1() {
        System.out.println("Configuration Detail:");
        System.out.println("  Final Threads: " + Threads);
        System.out.println("  Messages per thread: " + ThreadMessages);
        System.out.println("  Total messages: " + TotalMessages);

        // Create message queue
        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(100000);
        // Create latch to wait for Threads
        CountDownLatch latch = new CountDownLatch(Threads);

        // Starting message generator
        Thread generator = new Thread(new GenerateMessage(queue, TotalMessages));
        generator.start();

        try {
            System.out.println("Building up message queue...");
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Start capturing data
        startTimer();

        // Create worker threads
        System.out.println("\nStarting " + Threads + " worker threads...\n");

        ExecutorService pool = Executors.newFixedThreadPool(Threads);

        for (int i = 1; i <= Threads; i++) {
            MSGSenderThread thread = new MSGSenderThread(Server_Url, queue, ThreadMessages, latch, i) {
                @Override
                public void onSuccess() {
                    addSuccess();
                }

                @Override
                public void onFail() {
                    addFail();
                }

                @Override
                public void onConnect() {
                    addConnection();
                }

                @Override
                public void onReconnect() {
                    addReconnect();
                }
            };
            pool.submit(thread);
        }

        // Wait for Threads to complete sending
        try {
            System.out.println("Waiting for Threads to complete sending...\n");
            latch.await();

            // Stop capturing data
            stopTimer();
            // Wait for generator
            generator.join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }

        System.out.println("\n---------  WarmUp Phase ----------");
        System.out.println("Successful messages: " + successCount);
        System.out.println("Failed messages: " + failCount);
        System.out.println("Total runtime: " + getDuration() + " ms");
        System.out.println("Throughput: " + String.format("%.2f", getThroughput()) + " msg/sec");
        System.out.println("Connections: " + connectionCount);
        System.out.println("Reconnections: " + reconnectCount);
        System.out.println("-----------------------------------------\n");
    }
}