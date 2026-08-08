package bench;

import bench.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateMessageTest {

    private List<ChatMessage> generate(int count) throws InterruptedException {
        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>();
        Thread generator = new Thread(new GenerateMessage(queue, count));
        generator.start();
        generator.join(10_000);

        List<ChatMessage> produced = new ArrayList<>();
        queue.drainTo(produced);
        return produced;
    }

    @Test
    void producesExactlyTheRequestedNumberOfMessages() throws InterruptedException {
        assertEquals(250, generate(250).size());
    }

    @Test
    void everyMessageSatisfiesTheServerValidationRules() throws InterruptedException {
        for (ChatMessage msg : generate(400)) {
            assertTrue(msg.getUserId() >= 1 && msg.getUserId() <= 100_000,
                    "userId out of range: " + msg.getUserId());

            assertTrue(msg.getUsername().matches("^[a-zA-Z0-9]+$"),
                    "username is not alphanumeric: " + msg.getUsername());
            assertTrue(msg.getUsername().length() >= 3 && msg.getUsername().length() <= 20,
                    "username length invalid: " + msg.getUsername());

            assertTrue(!msg.getMessage().isEmpty() && msg.getMessage().length() <= 500,
                    "message length invalid: " + msg.getMessage());

            assertNotNull(Instant.parse(msg.getTimestamp()));

            assertEquals("TEXT", msg.getMessageType(),
                    "unexpected type: " + msg.getMessageType());
        }
    }

    @Test
    void onlyContentFramesAreGenerated() throws InterruptedException {
        Map<String, Integer> counts = new HashMap<>();
        for (ChatMessage msg : generate(2000)) {
            counts.merge(msg.getMessageType(), 1, Integer::sum);
        }

        // JOIN and LEAVE belong to the connection lifecycle. Generating them
        // mid-stream drops the session and every later message is refused.
        assertEquals(2000, counts.getOrDefault("TEXT", 0), "expected only TEXT: " + counts);
        assertEquals(0, counts.getOrDefault("JOIN", 0), "JOIN must not be generated");
        assertEquals(0, counts.getOrDefault("LEAVE", 0), "LEAVE must not be generated");
    }

    @Test
    void generatingZeroMessagesTerminatesCleanly() throws InterruptedException {
        assertEquals(0, generate(0).size());
    }

    @Test
    void aFullQueueBlocksRatherThanDroppingMessages() throws Exception {
        BlockingQueue<ChatMessage> queue = new ArrayBlockingQueue<>(5);
        Thread generator = new Thread(new GenerateMessage(queue, 20));
        generator.start();

        int drained = 0;
        while (drained < 20) {
            if (queue.poll(5, TimeUnit.SECONDS) == null) {
                break;
            }
            drained++;
        }
        generator.join(10_000);

        // back pressure must not cost messages
        assertEquals(20, drained);
    }

    @Test
    void interruptingTheGeneratorStopsIt() throws Exception {
        BlockingQueue<ChatMessage> queue = new ArrayBlockingQueue<>(1);
        Thread generator = new Thread(new GenerateMessage(queue, 1_000_000));
        generator.start();

        Thread.sleep(50);
        generator.interrupt();
        generator.join(5_000);

        assertTrue(!generator.isAlive(), "generator should exit once interrupted");
    }
}
