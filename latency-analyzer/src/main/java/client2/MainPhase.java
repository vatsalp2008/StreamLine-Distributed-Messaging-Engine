package client2;

import client2.model.ChatMessage;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * Class MainPhase for 500000 MSG Test with Analytics
 */
public class MainPhase {

    private static final String Server_Url = "ws://52.36.65.70:8080";
    private static final int Threads = 80;
    private static final int TotalMessages = 500000;
    private static final int ThreadMessages = TotalMessages / Threads;

    // Data Variables
    private static int successCount = 0;
    private static int failCount = 0;
    private static int connectionCount = 0;
    private static int reconnectCount = 0;
    private static long startTime = 0;
    private static long endTime = 0;

    //ArrayList to store data for csv
    private static ArrayList<MessageData> allMessageData = new ArrayList<>();

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
        double throughput = (successCount * 1000.0) / totalTime;

        System.out.println(" ------------ RESULTS ---------------");
        System.out.println("Successful: " + successCount);
        System.out.println("Failed: " + failCount);
        System.out.println("Runtime: " + totalTime + " ms");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " msg/sec");
        System.out.println("Connections: " + connectionCount);
        System.out.println("Reconnections: " + reconnectCount);

        // Calculating  statistics from latencies
        // Calculate statistics from message data
        if (!allMessageData.isEmpty()) {

            // Extract latencies from MessageData objects
            ArrayList<Long> latencies = new ArrayList<>();
            for (int i = 0; i < allMessageData.size(); i++) {
                latencies.add(allMessageData.get(i).latency);
            }

            // Sort for percentile calculations
            Collections.sort(latencies);

            // Calculate mean
            long sum = 0;
            for (int i = 0; i < latencies.size(); i++) {
                sum += latencies.get(i);
            }
            double mean = (double) sum / latencies.size();

            // Get values
            int size = latencies.size();
            long median = latencies.get(size / 2);
            long p95 = latencies.get((int)(size * 0.95));
            long p99 = latencies.get((int)(size * 0.99));
            long min = latencies.get(0);
            long max = latencies.get(size - 1);

            System.out.println("\n-------- Statistical Analysis ------");
            System.out.println("  Mean: " + String.format("%.2f", mean) + " ms");
            System.out.println("  Median: " + median + " ms");
            System.out.println("  95th percentile: " + p95 + " ms");
            System.out.println("  99th percentile: " + p99 + " ms");
            System.out.println("  Min: " + min + " ms");
            System.out.println("  Max: " + max + " ms");
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