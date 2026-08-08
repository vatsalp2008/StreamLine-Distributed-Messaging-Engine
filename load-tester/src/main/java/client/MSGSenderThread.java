package client;

import bench.TestConfig;
import bench.Backoff;
import bench.model.ChatMessage;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Class MSGSenderThread for thread that sends msg
 */
public class MSGSenderThread implements Runnable {

    private static final int Retries = 5;
    private static final int Timeout_Response = 200;

    /** 40ms doubling per retry, capped so a long outage does not stall a thread. */
    private static final Backoff BACKOFF = new Backoff(40, 2000);

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

    /** Whether the last frame was an acknowledgement rather than a refusal. */
    private volatile boolean responseAccepted;

    /**
     * @param url -String, Representing URL
     * @param queue -BlockingQueue<ChatMessage>, Representing the queue for msg
     * @param messages -int, Representing the MSG Count
     * @param latch -CountDownLatch, Representing Latch Count
     * @param id -int, Representing Room ID
     */
    public MSGSenderThread(String url, BlockingQueue<ChatMessage> queue,
                           int messages, CountDownLatch latch, int id) {
        this.serverUrl = url;
        this.queue = queue;
        this.messagesToSend = messages;
        this.latch = latch;
        this.ThreadNumber = id;
        this.roomId = (id % TestConfig.rooms(20)) + 1;
    }

    //Empty callback methods so mainphase can ovverrides it
    public void onSuccess() {
    }

    public void onFail() {
    }

    public void onConnect() {
    }

    public void onReconnect() {
    }
    /**
     * run start the connections for threads for sending msg
     */
    @Override
    public void run() {
        try {
            // Connect with retry
            if (!connect()) {
                System.err.println("Thread-" + ThreadNumber + ": Failed to connect");
                return;
            }

            // Send messages
            int sent = 0;
            while (sent < messagesToSend) {
                ChatMessage msg = queue.poll(5, TimeUnit.SECONDS);

                // if not more msg
                if (msg == null) {
                    break;
                }

                // Use new method that wait for response
                if (sendMessageAndWait(msg)) {
                    onSuccess();
                } else {
                    onFail();
                }

                sent++;
            }
            System.out.println("Thread " + ThreadNumber + ": Sent " + sent + " messages");

        } catch (Throwable t) {
            // Throwable, not Exception: a worker that dies from an Error (a missing
            // method after a stale build, an OOM) otherwise vanishes silently,
            // because submit() parks it in a Future nobody reads and the run just
            // reports zero traffic with no explanation.
            System.err.println("Worker-" + ThreadNumber + " failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            cleanup();
            latch.countDown();
        }
    }

    private boolean connect() {
        // Try up to 3 times
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String url = TestConfig.withAuth(serverUrl + "/chat/" + roomId);
                URI uri = new URI(url);

                client = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        connected = true;
                        onConnect();
                    }

                    @Override
                    public void onMessage(String message) {
                        // Only a direct reply answers this client's message. Fan-out
                        // and presence frames arrive because of other clients and
                        // must not release a sender waiting for its own ack.
                        if (!bench.ServerResponse.isDirectReply(message)) {
                            return;
                        }
                        // A refusal is still a reply. Counting it as success made a
                        // run where every message was rejected report 100% throughput.
                        responseAccepted = bench.ServerResponse.isAccepted(message);
                        // Server response received - wake up waiting thread!
                        gotResponse = true;
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

                    // Send JOIN first
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

    /**
     * @param msg generated content, whose author is ignored
     * @return the same content attributed to this connection
     */
    private ChatMessage asThisSender(ChatMessage msg) {
        return msg.withAuthor(ThreadNumber, "user" + ThreadNumber);
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
     * Send message and WAIT for server response
     * KEY METHOD - This makes metrics accurate!
     */
    private boolean sendMessageAndWait(ChatMessage msg) {

        // Retry up to 5 time
        for (int attempt = 0; attempt < Retries; attempt++) {
            try {
                //check connections
                if (!connected) {
                    if (!connect()) {
                        return false;
                    }
                    onReconnect();
                }

                // Prepare to wait for response
                responseLatch = new CountDownLatch(1);
                gotResponse = false;
                responseAccepted = false;

                // Send the message under this connection's own identity
                client.send(asThisSender(msg).toJson());

                // wait for server to respond it will be lock until response or timeout
                boolean receivedResponse = responseLatch.await(
                        Timeout_Response,
                        TimeUnit.MILLISECONDS
                );

                // Check for the response if success then return true
                if (receivedResponse && gotResponse && responseAccepted) {
                    return true;
                }

            } catch (Exception e) {
                if (attempt < Retries - 1 && !BACKOFF.sleepForAttempt(attempt)) {
                    return false;
                }
            }
        }
        return false;
    }

    private void cleanup() {
        if (client != null) {
            try {
                client.closeBlocking();
            } catch (Exception e) {}
        }
    }
}