package client;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sender only accepts the reply that answers its own message.
 *
 * Matching by arrival order instead let an unrelated frame release a waiting
 * sender, so a message could be counted as acknowledged before the server had
 * even processed it.
 */
class CorrelationMatchingTest {

    private StubServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(1000);
        }
    }

    /** Server whose reply behaviour each test chooses. */
    private static final class StubServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<String> received = new CopyOnWriteArrayList<>();

        /** How to answer each inbound frame, given its correlation id. */
        private volatile java.util.function.Function<String, String> reply =
                id -> ack(id, "OK");

        StubServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
            setReuseAddr(true);
        }

        static String ack(String clientId, String status) {
            String id = clientId == null ? "" : ",\"clientId\":\"" + clientId + "\"";
            return "{\"status\":\"" + status + "\",\"message\":\"ack\"" + id + "}";
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            received.add(message);
            String answer = reply.apply(clientIdOf(message));
            if (answer != null) {
                conn.send(answer);
            }
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

    private StubServer startServer() throws Exception {
        server = new StubServer();
        server.start();
        return server;
    }

    @Test
    void everySentFrameCarriesACorrelationId() throws Exception {
        StubServer stub = startServer();

        new BenchmarkRunner("test", stub.url(), 1, 5).run();

        assertTrue(stub.received.stream().allMatch(f -> f.contains("\"clientId\":\"")),
                "every frame should be tagged: " + stub.received);
    }

    @Test
    void correlationIdsAreUniquePerConnection() throws Exception {
        StubServer stub = startServer();

        new BenchmarkRunner("test", stub.url(), 1, 5).run();

        List<String> ids = stub.received.stream().map(StubServer::clientIdOf).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "ids repeated: " + ids);
    }

    @Test
    void aReplyCarryingTheWrongIdDoesNotAcknowledgeTheMessage() throws Exception {
        StubServer stub = startServer();
        // always answer with someone else's id
        stub.reply = id -> StubServer.ack("not-your-message", "OK");

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 1, 4).run();

        // the sender must wait for its own reply, so these all time out
        assertEquals(0, result.successes());
        assertEquals(4, result.failures());
    }

    @Test
    void aReplyCarryingTheRightIdAcknowledgesTheMessage() throws Exception {
        StubServer stub = startServer();

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 1, 4).run();

        assertEquals(4, result.successes());
        assertEquals(0, result.failures());
    }

    @Test
    void anUntaggedReplyStillAcknowledges() throws Exception {
        StubServer stub = startServer();
        // a server that does not echo the id at all, as older builds did
        stub.reply = id -> "{\"status\":\"OK\",\"message\":\"ack\"}";

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 1, 4).run();

        // matching must not break against a server that predates correlation ids
        assertEquals(0, result.successes() + result.failures() - 4);
        assertEquals(0, result.failures());
    }

    @Test
    void aRefusalCarryingTheRightIdIsCountedAsAFailure() throws Exception {
        StubServer stub = startServer();
        stub.reply = id -> StubServer.ack(id, "ERROR");

        BenchmarkResult result = new BenchmarkRunner("test", stub.url(), 1, 4).run();

        assertEquals(0, result.successes());
        assertEquals(4, result.failures());
    }
}
