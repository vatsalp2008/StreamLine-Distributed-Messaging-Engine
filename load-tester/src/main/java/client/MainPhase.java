package client;

/**
 * Class MainPhase for the full-load throughput test.
 */
public class MainPhase {

    private static final int DEFAULT_THREADS = 100;
    private static final int DEFAULT_MESSAGES = 500000;

    public static void main(String[] args) {
        new BenchmarkRunner(
                "Main Phase Client1",
                TestConfig.serverUrl(),
                TestConfig.threads(DEFAULT_THREADS),
                TestConfig.totalMessages(DEFAULT_MESSAGES)
        ).run();
    }
}
