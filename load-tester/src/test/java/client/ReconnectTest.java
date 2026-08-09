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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sender does when the connection drops mid-run.
 *
 * The send path is covered elsewhere; this covers the branch that only runs
 * after a socket dies, which is exactly the branch a benchmark depends on when
 * the server it is hammering starts closing connections.
 */
class ReconnectTest {

    private FlakyServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(1000);
        }
    }

    /** Server that closes the first N connections instead of answering. */
    private static final class FlakyServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicInteger connectionsToDrop = new AtomicInteger();

        FlakyServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            opened.incrementAndGet();
            if (connectionsToDrop.getAndDecrement() > 0) {
                conn.close();
            }
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            if (!conn.isOpen()) {
                return;
            }
            String id = clientIdOf(message);
            conn.send("{\"status\":\"OK\",\"message\":\"ack\""
                    + (id == null ? "" : ",\"clientId\":\"" + id + "\"") + "}");
        }

        private static String clientIdOf(String frame) {
            int at = frame.indexOf("\"clientId\":\"");
            if (at < 0) {
                return null;
            }
            int from = at + "\"clientId\":\"".length();
            return frame.substring(from, frame.indexOf('"', from));
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

    private FlakyServer startServer() throws Exception {
        server = new FlakyServer();
        server.start();
        return server;
    }

    @Test
    void aRunSurvivesTheFirstConnectionBeingDropped() throws Exception {
        FlakyServer stub = startServer();
        stub.connectionsToDrop.set(1);

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 1, 4).run();

        // the sender reconnects rather than abandoning its share of the load
        assertTrue(stub.opened.get() > 1,
                "expected a reconnect, only saw " + stub.opened.get() + " connection(s)");
        assertEquals(4, result.successes() + result.failures());
    }

    @Test
    void reconnectsAreReported() throws Exception {
        FlakyServer stub = startServer();
        stub.connectionsToDrop.set(1);

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 1, 4).run();

        // a run that silently reconnected would look identical to a clean one
        assertTrue(result.reconnects() >= 1,
                "reconnects should be counted, got " + result.reconnects());
    }

    @Test
    void aHealthyRunReportsNoReconnects() throws Exception {
        FlakyServer stub = startServer();

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 2, 20).run();

        assertEquals(0, result.reconnects());
        assertEquals(20, result.successes());
    }

    // A server that refuses every connection is covered deterministically by
    // BenchmarkRunnerTest against a port with nothing listening. Asserting it
    // here by closing on open is racy: the close is asynchronous, so a frame
    // sent into the window before it lands is still answered.
}
