package client2;

import client2.model.ChatMessage;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Class MainPhase for 500000 MSG Test with Analytics
 */
public class MainPhase {

    private static final String Server_Url = TestConfig.serverUrl();
    private static final int Threads = TestConfig.threads(80);
    private static final int TotalMessages = TestConfig.totalMessages(500000);
    private static final int ThreadMessages = TotalMessages / Threads;

    // Data Variables.
    // LongAdder rather than a synchronized counter: every sender thread increments
    // these on the hot path, and a shared monitor would serialise the very threads
    // whose throughput is being measured.
    private static final LongAdder successCount = new LongAdder();
    private static final LongAdder failCount = new LongAdder();
    private static final LongAdder connectionCount = new LongAdder();
    private static final LongAdder reconnectCount = new LongAdder();
    private static long startTime = 0;
    private static long endTime = 0;

    //ArrayList to store data for csv
    private static ArrayList<MessageData> allMessageData = new ArrayList<>();

    public static void main(String[] args) {
        runPhase1();
    }

    private static void addSuccess() {
        successCount.increment();
    }

    private static void addFail() {
        failCount.increment();
    }

    private static void addConnection() {
        connectionCount.increment();
    }

    private static void addReconnect() {
        reconnectCount.increment();
    }

    private static void runPhase1() {

        System.out.println("Configuration Detail:");
        System.out.println("  Threads: " + Threads);
        System.out.println("  Messages per thread: " + ThreadMessages);
        System.out.println("  Total messages: " + TotalMessages + "\n");

        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(100000);
        CountDownLatch latch = new CountDownLatch(Threads);

        Thread msgGenerate = new Thread(new GenerateMessage(queue, TotalMessages));
        msgGenerate.start();

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        startTime = System.currentTimeMillis();

        System.out.println("Starting " + Threads + " threads...\n");

        ExecutorService pool = Executors.newFixedThreadPool(Threads);

        for (int i = 1; i <= Threads; i++) {
            MSGSenderThread thread = new MSGSenderThread(
                    Server_Url, queue, ThreadMessages, latch, i, allMessageData
            ) {
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

        try {
            System.out.println("Waiting for threads to finish...\n");
            latch.await();
            endTime = System.currentTimeMillis();
            msgGenerate.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }

        dataAnalytics();
    }

    private static void dataAnalytics() {
        long totalTime = endTime - startTime;
        // a run that finishes inside the clock resolution would otherwise divide by zero
        long successes = successCount.sum();
        double throughput = totalTime > 0 ? (successes * 1000.0) / totalTime : 0.0;

        System.out.println(" ------------ RESULTS ---------------");
        System.out.println("Successful: " + successes);
        System.out.println("Failed: " + failCount.sum());
        System.out.println("Runtime: " + totalTime + " ms");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " msg/sec");
        System.out.println("Connections: " + connectionCount.sum());
        System.out.println("Reconnections: " + reconnectCount.sum());

        // Calculate statistics from message data
        ArrayList<Long> latencies = new ArrayList<>();
        for (int i = 0; i < allMessageData.size(); i++) {
            latencies.add(allMessageData.get(i).latency);
        }

        LatencyStats stats = LatencyStats.of(latencies);
        if (!stats.isEmpty()) {
            System.out.println("\n-------- Statistical Analysis ------");
            System.out.println("  Mean: " + String.format("%.2f", stats.mean()) + " ms");
            System.out.println("  Median: " + String.format("%.2f", stats.median()) + " ms");
            System.out.println("  95th percentile: " + stats.percentile(95) + " ms");
            System.out.println("  99th percentile: " + stats.percentile(99) + " ms");
            System.out.println("  Min: " + stats.min() + " ms");
            System.out.println("  Max: " + stats.max() + " ms");
        }

        //Genearting CSV File For timestamp, msgtypoe, latency, stauscode and roomID
        writeCSV();
        throughputChartCSV();
    }

    /**
     * CSV File For timestamp, msgtypoe, latency, stauscode and roomID
     */
    private static void writeCSV() {
        try {
            java.io.File dir = new java.io.File("Result");
            if (!dir.exists()) {
                dir.mkdir();
            }

            PrintWriter writer = new PrintWriter(new FileWriter("Result/MessageMetrics.csv"));

            // Header
            writer.println("timestamp,messageType,latency,statusCode,roomId");

            // Data
            for (int i = 0; i < allMessageData.size(); i++) {
                MessageData d = allMessageData.get(i);
                writer.println(d.timestamp + "," +
                        d.messageType + "," +
                        d.latency + "," +
                        d.statusCode + "," +
                        d.roomId);
            }
            writer.close();
        } catch (Exception e) {
            System.err.println("Eroor while Creating CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void throughputChartCSV() {

        if (allMessageData.isEmpty()) {
            System.out.println("NO Data --");
            return;
        }

        try {
            // Find first and last message time
            long firstTime = allMessageData.get(0).timestamp;
            long lastTime = allMessageData.get(0).timestamp;

            for (int i = 0; i < allMessageData.size(); i++) {
                long timestamp = allMessageData.get(i).timestamp;
                if (timestamp < firstTime) firstTime = timestamp;
                if (timestamp > lastTime) lastTime = timestamp;
            }

            // Calculating how many 10 second buckets
            long totalSeconds = (lastTime - firstTime) / 1000;
            int numBuckets = (int)(totalSeconds / 10) + 1;

            // Counting messages in each bucket
            int[] bucketCounts = new int[numBuckets];

            for (int i = 0; i < allMessageData.size(); i++) {
                long elapsed = allMessageData.get(i).timestamp - firstTime;
                int bucketNum = (int)(elapsed / 10000);

                if (bucketNum >= 0 && bucketNum < numBuckets) {
                    bucketCounts[bucketNum]++;
                }
            }

            java.io.File dir = new java.io.File("Result");
            if (!dir.exists()) {
                dir.mkdir();
            }

            PrintWriter writer = new PrintWriter(new FileWriter("Result/Throughput.csv"));
            writer.println("Time_Seconds,Messages_Per_Second");

            for (int i = 0; i < numBuckets; i++) {
                int timeInSeconds = i * 10;
                double messagesPerSec = bucketCounts[i] / 10.0;

                if (bucketCounts[i] > 0) {
                    writer.println(timeInSeconds + "," + messagesPerSec);
                }
            }

            writer.close();
        } catch (Exception e) {
            System.err.println("Error creating throughput CSV: " + e.getMessage());
        }
    }
}