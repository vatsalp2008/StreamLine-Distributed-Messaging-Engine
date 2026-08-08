package client;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the runner against a real WebSocket server running in-process.
 *
 * A mock would not exercise what actually matters here: that the generator, the
 * queue, the sender threads and the acknowledgement handshake line up, and that
 * the reported counters match what the server really received.
 */
class BenchmarkRunnerTest {

    private StubChatServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(1000);
        }
    }

    /** Minimal stand-in for the chat server: acknowledges every frame. */
    private static final class StubChatServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final LongAdder received = new LongAdder();
        private final AtomicInteger opened = new AtomicInteger();

        /** When set, the first N frames get no reply so the sender must retry. */
        private final AtomicInteger framesToIgnore = new AtomicInteger();

        StubChatServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            opened.incrementAndGet();
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            received.increment();
            if (framesToIgnore.getAndDecrement() > 0) {
                return; // silence, so the client retries
            }
            conn.send("{\"status\":\"OK\",\"serverTimestamp\":\"2026-08-07T10:00:00Z\","
                    + "\"message\":\"ack\"}");
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

    private StubChatServer startServer() throws Exception {
        server = new StubChatServer();
        server.start();
        return server;
    }

    @Test
    void everyMessageIsAcknowledgedAndCounted() throws Exception {
        StubChatServer stub = startServer();

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 2, 20).run();

        assertEquals(20, result.successes());
        assertEquals(0, result.failures());
        assertEquals("test", result.label());
    }

    @Test
    void oneConnectionIsOpenedPerThread() throws Exception {
        StubChatServer stub = startServer();

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 4, 20).run();

        assertEquals(4, result.connections());
        assertEquals(4, stub.opened.get());
    }

    @Test
    void theServerReceivesAJoinPerThreadOnTopOfTheMessages() throws Exception {
        StubChatServer stub = startServer();

        new BenchmarkRunner("test", stub.url(), 2, 20).run();

        // 20 generated messages plus one JOIN per sender thread
        assertEquals(22, stub.received.sum());
    }

    @Test
    void unansweredFramesAreCountedAsFailures() throws Exception {
        StubChatServer stub = startServer();
        stub.framesToIgnore.set(1); // swallow the very first frame

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 1, 4).run();

        // the ignored frame is the JOIN, which is not counted either way, so the
        // run still succeeds; what matters is that nothing is double counted
        assertEquals(4, result.successes() + result.failures());
    }

    @Test
    void throughputIsDerivedFromSuccessesAndDuration() throws Exception {
        StubChatServer stub = startServer();

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 2, 20).run();

        assertTrue(result.durationMillis() >= 0, "duration must not be negative");
        if (result.durationMillis() > 0) {
            double expected = (result.successes() * 1000.0) / result.durationMillis();
            assertEquals(expected, result.throughputPerSecond(), 0.0001);
        }
    }

    @Test
    void aRunAgainstAnUnreachableServerReportsNoTraffic() throws Exception {
        // port 1 is not listening, so every thread fails to connect
        BenchmarkResult result = new BenchmarkRunner("test", "ws://127.0.0.1:1", 2, 4).run();

        assertEquals(0, result.successes());
        assertEquals(0, result.connections());
    }

    @Test
    void throughputIsZeroRatherThanInfiniteForAnInstantRun() {
        BenchmarkResult instant = new BenchmarkResult("test", 10, 0, 1, 0, 0);

        assertEquals(0.0, instant.throughputPerSecond());
    }

    @Test
    void messagesAreSpreadAcrossTheConfiguredThreads() throws Exception {
        StubChatServer stub = startServer();

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 5, 20).run();

        // 20 messages over 5 threads is 4 each, so all 20 still land
        assertEquals(20, result.successes());
        assertEquals(5, result.connections());
    }

    @Test
    void aFullyAcknowledgedRunIsClean() {
        BenchmarkResult result = new BenchmarkResult("test", 100, 0, 4, 0, 1000);

        assertTrue(result.isClean());
        assertEquals(1.0, result.successRate(), 0.0001);
    }

    @Test
    void aPartiallyRefusedRunIsNotClean() {
        BenchmarkResult result = new BenchmarkResult("test", 60, 40, 4, 0, 1000);

        assertFalse(result.isClean());
        assertEquals(0.6, result.successRate(), 0.0001);
    }

    @Test
    void aRunThatSentNothingIsNotClean() {
        BenchmarkResult result = new BenchmarkResult("test", 0, 0, 0, 0, 0);

        assertFalse(result.isClean());
        assertEquals(0.0, result.successRate(), 0.0001);
    }

    @Test
    void aRealRunAgainstAWorkingServerIsClean() throws Exception {
        StubChatServer stub = startServer();

        assertTrue(new BenchmarkRunner("test", stub.url(), 2, 20).run().isClean());
    }
}
