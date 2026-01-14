package client2;

import client2.model.ChatMessage;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Thread that sends messages and tracks latency
 */
public class MSGSenderThread implements Runnable {

    private static final int Retries = 5;
    private static final int Timeout_Response = 200;

    private String serverUrl;
    private BlockingQueue<ChatMessage> queue;
    private int messagesToSend;
    private CountDownLatch latch;
    private int ThreadNumber;

    private WebSocketClient client;
    private boolean connected = false;
    private int roomId;

    private CountDownLatch responseLatch;
    private volatile boolean gotResponse;
    private volatile String serverResponse;
    private ArrayList<MessageData> messageDataList;

    public MSGSenderThread(String url, BlockingQueue<ChatMessage> queue,
                           int messages, CountDownLatch latch, int id,
                           ArrayList<MessageData> dataList) {
        this.serverUrl = url;
        this.queue = queue;
        this.messagesToSend = messages;
        this.latch = latch;
        this.ThreadNumber = id;
        this.roomId = (id % 20) + 1;
        this.messageDataList = dataList;    }

    // Callbacks
    public void onSuccess() {}
    public void onFail() {}
    public void onConnect() {}
    public void onReconnect() {}

    @Override
    public void run() {
        try {
            if (!connect()) {
                System.err.println("Thread-" + ThreadNumber + ": Failed to connect");
                return;
            }

            int sent = 0;
            while (sent < messagesToSend) {
                ChatMessage msg = queue.poll(5, TimeUnit.SECONDS);

                if (msg == null) {
                    break;
                }

                // Send and get data
                MessageData data = sendMessageAndWait(msg);

                if (data != null && data.latency >= 0) {
                    onSuccess();
                    synchronized(messageDataList) {
                        messageDataList.add(data);
                    }
                } else {
                    onFail();
                }

                sent++;
            }

            System.out.println("Thread " + ThreadNumber + ": Sent " + sent + " messages");

        } catch (Exception e) {
            System.err.println("Thread-" + ThreadNumber + " error: " + e.getMessage());
        } finally {
            cleanup();
            latch.countDown();
        }
    }

    private boolean connect() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String url = serverUrl + "/chat/" + roomId;
                URI uri = new URI(url);

                client = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        connected = true;
                        onConnect();
                    }

                    @Override
                    public void onMessage(String message) {
                        gotResponse = true;
                        serverResponse = message;
                        if (responseLatch != null) {
                            responseLatch.countDown();
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        connected = false;
                    }

                    @Override
                    public void onError(Exception ex) {
                        connected = false;
                    }
                };

                client.connectBlocking(3, TimeUnit.SECONDS);

                if (connected) {
                    if (attempt > 1) {
                        onReconnect();
                    }
                    sendJoinMessage();
                    return true;
                }

            } catch (Exception e) {
                if (attempt < 3) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void sendJoinMessage() {
        ChatMessage join = new ChatMessage(
                ThreadNumber,
                "user" + ThreadNumber,
                "Joining",
                java.time.Instant.now().toString(),
                "JOIN"
        );
        sendMessageAndWait(join);
    }

    /**
     * Send and measure latency - returns latency in ms or -1 if failed
     */
    private MessageData sendMessageAndWait(ChatMessage msg) {
        // Retry up to 5 times
        for (int attempt = 0; attempt < Retries; attempt++) {
            try {
                // Check connection
                if (!connected) {
                    if (!connect()) {
                        return null;
                    }
                    onReconnect();
                }

                // Prepare to wait for response
                responseLatch = new CountDownLatch(1);
                gotResponse = false;
                serverResponse = null;

                // Record START timestamp
                long timestamp = System.currentTimeMillis();

                // Send message
                client.send(msg.toJson());

                // Wait for server response
                boolean got = responseLatch.await(
                        Timeout_Response,
                        TimeUnit.MILLISECONDS
                );

                // Calculate latency
                long latency = System.currentTimeMillis() - timestamp;

                // Check if got response
                if (got && gotResponse) {
                    // Extract status from server response
                    String status = "OK";
                    if (serverResponse != null && serverResponse.contains("ERROR")) {
                        status = "ERROR";
                    }

                    // Return complete data object
                    return new MessageData(
                            timestamp,
                            msg.getMessageType(),
                            latency,
                            status,
                            roomId
                    );
                }

                // No response - will retry

            } catch (Exception e) {
                // Error occurred - retry with backoff
                if (attempt < Retries - 1) {
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 40);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        return null;  // Failed after all retries
    }

    private void cleanup() {
        if (client != null) {
            try {
                client.closeBlocking();
            } catch (Exception e) {}
        }
    }
}