package bench;

import bench.model.ChatMessage;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

/**
 * class MessageGenerator for Generating msg using threads
 */
public class GenerateMessage implements Runnable {

    private BlockingQueue<ChatMessage> queue;
    private int totalMessages;
    private Random random;

    // 50 predefine msg for msg generation
    private static final String[] messages = {
            "hey there", "what’s up?", "lol", "brb", "good morning",
            "any updates?", "sounds good", "same here", "no worries",
            "let’s go", "haha nice", "good one", "true that",
            "got it", "all set", "wait a sec", "hmm interesting",
            "I agree", "yeah right", "cool cool", "done",
            "on my way", "where are you?", "just checking",
            "good night", "see you", "hold on", "one sec",
            "that’s fine", "makes sense", "oh really?",
            "thanks!", "appreciate it", "no problem",
            "well played", "nice try", "try again", "exactly",
            "alright then", "take care", "later",
            "let me know", "working on it", "quick question",
            "maybe", "idk", "yes", "no", "yep", "ok cool",
            "alright", "same"
    };

    /**
     * @param queue -BlockingQueue<ChatMessage>, Representing CHatMessage Queue
     * @param total -int, Representing the Total MSG Count
     */
    public GenerateMessage(BlockingQueue<ChatMessage> queue, int total) {
        this.queue = queue;
        this.totalMessages = total;
        this.random = new Random();
    }

    @Override
    public void run() {

        System.out.println("Generating " + totalMessages + " messages in Total");

        try {
            for (int i = 0; i < totalMessages; i++) {
                ChatMessage msg = generateMessage();
                queue.put(msg);
            }
            System.out.println("-----Finished creating all messages-----");

        } catch (InterruptedException e) {
            System.err.println("!-!-!-!-!- Error While MSG Generating !-!-!-!-!-");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return -String, Representing Random Message Type
     */
    private String randomMessageType() {
        int rand = random.nextInt(100);
        if (rand < 90) {
            return "TEXT";
        } else if (rand < 95) {
            return "JOIN";
        } else {
            return "LEAVE";
        }
    }

    /**
     * Generate Random Message
     * @return -ChatMessage
     */
    private ChatMessage generateMessage() {

        // Random userId
        int userId = random.nextInt(100000) + 1;
        String username = "user" + userId;

        // Random message from Predefine MSG
        String msg = messages[random.nextInt(messages.length)];

        // timestamp
        String timestamp = Instant.now().toString();

        // Random Message type: 90% TEXT, 5% JOIN, 5% LEAVE
        String type = randomMessageType();

        return new ChatMessage(userId, username, msg, timestamp, type);
    }
}