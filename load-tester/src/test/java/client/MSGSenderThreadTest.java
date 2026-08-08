package client;

import bench.model.ChatMessage;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
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
 * The sender thread against a real WebSocket server.
 *
 * These cover the parts that only appear over a live connection: that the
 * connection joins before sending, that a refusal is counted as a failure
 * rather than a success, and that fan-out from other clients does not release
 * a sender waiting for its own acknowledgement.
 */
class MSGSenderThreadTest {

    private StubServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(1000);
        }
    }

    /** Configurable stand-in for the chat server. */
    private static final class StubServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<String> received = new CopyOnWriteArrayList<>();

        /** Status returned for every frame after the JOIN. */
        private volatile String replyStatus = "OK";

        /** When true, an unsolicited BROADCAST is sent instead of a reply. */
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
                conn.send(reply("BROADCAST", "someone else said something"));
                return;
            }
            conn.send(reply(replyStatus, "ack"));
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

    /** Runs one sender and reports how many messages it counted as successes. */
    private int runSender(String url, int messages) throws Exception {
        BlockingQueue<ChatMessage> queue = new ArrayBlockingQueue<>(Math.max(messages, 1));
        for (int i = 0; i < messages; i++) {
            queue.put(new ChatMessage(99, "generated" + i, "hello there",
                    Instant.now().toString(), "TEXT"));
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        MSGSenderThread sender = new MSGSenderThread(url, queue, messages, latch, 1) {
            @Override
            public void onSuccess() {
                successes.incrementAndGet();
            }
        };

        new Thread(sender).start();
        assertTrue(latch.await(30, TimeUnit.SECONDS), "sender did not finish");
        return successes.get();
    }

    @Test
    void theConnectionJoinsBeforeSendingAnything() throws Exception {
        StubServer stub = startServer();

        runSender(stub.url(), 2);

        assertEquals(1, stub.joins.get(), "expected exactly one JOIN per connection");
        assertTrue(stub.received.get(0).contains("\"messageType\":\"JOIN\""),
                "the first frame must be the JOIN");
    }

    @Test
    void everyMessageIsSentUnderTheConnectionsOwnUsername() throws Exception {
        StubServer stub = startServer();

        runSender(stub.url(), 3);

        // the generated author is discarded; the connection's identity is used
        for (String frame : stub.received) {
            assertTrue(frame.contains("\"username\":\"user1\""),
                    "frame did not carry the sender identity: " + frame);
            assertFalse(frame.contains("generated"), "generated author leaked: " + frame);
        }
    }

    @Test
    void acknowledgedMessagesCountAsSuccesses() throws Exception {
        StubServer stub = startServer();

        assertEquals(3, runSender(stub.url(), 3));
    }

    @Test
    void refusedMessagesCountAsFailuresNotSuccesses() throws Exception {
        StubServer stub = startServer();
        stub.replyStatus = "ERROR";

        // a refusal is still a reply; it must not be counted as throughput
        assertEquals(0, runSender(stub.url(), 3));
    }

    @Test
    void fanOutFromOtherClientsDoesNotSatisfyASender() throws Exception {
        StubServer stub = startServer();
        stub.replyWithBroadcastOnly = true;

        // BROADCAST is someone else's traffic, so these messages went unanswered
        assertEquals(0, runSender(stub.url(), 2));
    }

    @Test
    void aSenderStopsCleanlyWhenTheServerIsUnreachable() throws Exception {
        // port 1 is not listening; the sender must finish rather than hang
        assertEquals(0, runSender("ws://127.0.0.1:1", 2));
    }

    @Test
    void anEmptyQueueEndsTheRunWithoutSending() throws Exception {
        StubServer stub = startServer();

        assertEquals(0, runSender(stub.url(), 0));
        assertEquals(1, stub.joins.get(), "the connection still joins");
    }
}
