package client2;

import bench.model.ChatMessage;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The latency client's sender thread against a real WebSocket server.
 *
 * Beyond the send/acknowledge cycle this also has to produce a MessageData row
 * per acknowledged message, which is the input to every reported percentile.
 */
class MSGSenderThreadTest {

    private StubServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(1000);
        }
    }

    private static final class StubServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<String> received = new CopyOnWriteArrayList<>();
        private volatile String replyStatus = "OK";
        private volatile String replyBody = "ack";
        private volatile boolean replyWithBroadcastOnly = false;
        private final AtomicInteger joins = new AtomicInteger();

        StubServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            received.add(message);

            if (message.contains("\"messageType\":\"JOIN\"")) {
                joins.incrementAndGet();
                conn.send(reply("OK", "joined"));
                return;
            }
            if (replyWithBroadcastOnly) {
                conn.send(reply("BROADCAST", "other traffic"));
                return;
            }
            conn.send(reply(replyStatus, replyBody));
        }

        private String reply(String status, String message) {
            return "{\"status\":\"" + status + "\",\"serverTimestamp\":\"2026-08-08T10:00:00Z\","
                    + "\"message\":\"" + message + "\"}";
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        String url() throws InterruptedException {
            assertTrue(started.await(10, TimeUnit.SECONDS), "stub server did not start");
            return "ws://127.0.0.1:" + getPort();
        }
    }

    private StubServer startServer() throws Exception {
        server = new StubServer();
        server.start();
        return server;
    }

    /** Runs one sender and returns the latency rows it recorded. */
    private ArrayList<MessageData> runSender(String url, int messages) throws Exception {
        BlockingQueue<ChatMessage> queue = new ArrayBlockingQueue<>(Math.max(messages, 1));
        for (int i = 0; i < messages; i++) {
            queue.put(new ChatMessage(99, "generated" + i, "hello there",
                    Instant.now().toString(), "TEXT"));
        }

        ArrayList<MessageData> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        MSGSenderThread sender = new MSGSenderThread(url, queue, messages, latch, 1, collected);
        new Thread(sender).start();
        assertTrue(latch.await(30, TimeUnit.SECONDS), "sender did not finish");
        return collected;
    }

    @Test
    void oneLatencyRowIsRecordedPerAcknowledgedMessage() throws Exception {
        StubServer stub = startServer();

        assertEquals(3, runSender(stub.url(), 3).size());
    }

    @Test
    void recordedLatenciesAreNotNegative() throws Exception {
        StubServer stub = startServer();

        for (MessageData row : runSender(stub.url(), 3)) {
            assertTrue(row.getLatency() >= 0, "negative latency: " + row.getLatency());
        }
    }

    @Test
    void recordedRowsCarryTheRoomAndType() throws Exception {
        StubServer stub = startServer();

        for (MessageData row : runSender(stub.url(), 2)) {
            assertEquals("TEXT", row.getMessageType());
            assertTrue(row.getRoomId() > 0, "room id not recorded");
        }
    }

    @Test
    void refusedMessagesProduceNoLatencyRows() throws Exception {
        StubServer stub = startServer();
        stub.replyStatus = "ERROR";

        // a refusal is not a measurement of successful delivery
        assertEquals(0, runSender(stub.url(), 3).size());
    }

    @Test
    void fanOutFromOtherClientsProducesNoLatencyRows() throws Exception {
        StubServer stub = startServer();
        stub.replyWithBroadcastOnly = true;

        assertEquals(0, runSender(stub.url(), 2).size());
    }

    @Test
    void messagesAreSentUnderTheConnectionsOwnUsername() throws Exception {
        StubServer stub = startServer();

        runSender(stub.url(), 2);

        for (String frame : stub.received) {
            assertTrue(frame.contains("\"username\":\"user1\""), "wrong author: " + frame);
            assertFalse(frame.contains("generated"), "generated author leaked: " + frame);
        }
    }

    @Test
    void theConnectionJoinsExactlyOnce() throws Exception {
        StubServer stub = startServer();

        runSender(stub.url(), 2);

        assertEquals(1, stub.joins.get());
    }

    @Test
    void anUnreachableServerRecordsNothingAndStops() throws Exception {
        assertEquals(0, runSender("ws://127.0.0.1:1", 2).size());
    }

    @Test
    void aMessageBodyMentioningErrorIsStillRecordedAsOk() throws Exception {
        StubServer stub = startServer();
        stub.replyBody = "alice: the build failed with ERROR 500";

        ArrayList<MessageData> rows = runSender(stub.url(), 1);

        // classification reads the status field, not the whole frame
        assertEquals(1, rows.size());
        assertEquals("OK", rows.get(0).getStatusCode());
    }
}
