package bench;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestConfigTest {

    private static final String URL = "streamline.url";
    private static final String THREADS = "streamline.threads";
    private static final String MESSAGES = "streamline.messages";
    private static final String ROOMS = "streamline.rooms";
    private static final String RESULT_DIR = "streamline.result.dir";

    @AfterEach
    void clearProperties() {
        System.clearProperty(URL);
        System.clearProperty(THREADS);
        System.clearProperty(MESSAGES);
        System.clearProperty(ROOMS);
        System.clearProperty(RESULT_DIR);
    }

    @Test
    void defaultsToLocalhostWhenNothingIsSet() {
        assertEquals("ws://localhost:8080", TestConfig.serverUrl());
    }

    @Test
    void systemPropertyOverridesTheDefaultUrl() {
        System.setProperty(URL, "ws://example.test:9000");

        assertEquals("ws://example.test:9000", TestConfig.serverUrl());
    }

    @Test
    void blankValuesFallBackToTheDefault() {
        System.setProperty(URL, "   ");

        assertEquals("ws://localhost:8080", TestConfig.serverUrl());
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        System.setProperty(URL, "  ws://trimmed.test:8080  ");

        assertEquals("ws://trimmed.test:8080", TestConfig.serverUrl());
    }

    @Test
    void numericOverridesAreApplied() {
        System.setProperty(THREADS, "12");
        System.setProperty(MESSAGES, "3400");
        System.setProperty(ROOMS, "5");

        assertEquals(12, TestConfig.threads(100));
        assertEquals(3400, TestConfig.totalMessages(500000));
        assertEquals(5, TestConfig.rooms(20));
    }

    @Test
    void callerDefaultsApplyWhenUnset() {
        assertEquals(100, TestConfig.threads(100));
        assertEquals(500000, TestConfig.totalMessages(500000));
        assertEquals(20, TestConfig.rooms(20));
    }

    @Test
    void nonNumericValueFallsBackInsteadOfCrashingTheRun() {
        System.setProperty(THREADS, "not-a-number");

        assertEquals(64, TestConfig.threads(64));
    }

    @Test
    void nonPositiveValuesAreRejected() {
        // zero threads would mean a division by zero when sizing per-thread work
        System.setProperty(THREADS, "0");
        assertEquals(64, TestConfig.threads(64));

        System.setProperty(THREADS, "-4");
        assertEquals(64, TestConfig.threads(64));
    }

    @Test
    void resultDirDefaultsToResult() {
        assertEquals("Result", TestConfig.resultDir());
    }

    @Test
    void resultDirCanBeOverridden() {
        System.setProperty(RESULT_DIR, "/tmp/streamline-reports");

        assertEquals("/tmp/streamline-reports", TestConfig.resultDir());
    }

    @Test
    void blankResultDirFallsBackToTheDefault() {
        System.setProperty(RESULT_DIR, "   ");

        assertEquals("Result", TestConfig.resultDir());
    }
}
