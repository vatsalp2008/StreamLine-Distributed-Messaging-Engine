package client;

/**
 * Class WarmUpPhase, a smaller run used to warm the server before the main phase.
 */
public class WarmUpPhase {

    private static final int DEFAULT_THREADS = 32;
    private static final int DEFAULT_MESSAGES = 32000;

    public static void main(String[] args) {
        new BenchmarkRunner(
                "WarmUp Phase",
                TestConfig.serverUrl(),
                TestConfig.threads(DEFAULT_THREADS),
                TestConfig.totalMessages(DEFAULT_MESSAGES)
        ).run();
    }
}
