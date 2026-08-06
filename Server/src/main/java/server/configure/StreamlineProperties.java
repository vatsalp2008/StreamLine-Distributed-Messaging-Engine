package server.configure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every streamline.* setting in one place.
 *
 * Previously these were read through @Value expressions spread across the
 * handler, the async config, and the WebSocket config, which meant the defaults
 * lived in string literals in three different files.
 */
@ConfigurationProperties(prefix = "streamline")
public class StreamlineProperties {

    private final Broadcast broadcast = new Broadcast();
    private final Persistence persistence = new Persistence();
    private final Ws ws = new Ws();
    private final RateLimit rateLimit = new RateLimit();

    public Broadcast getBroadcast() {
        return broadcast;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public Ws getWs() {
        return ws;
    }

    /** Fan-out of messages to the other members of a room. */
    public static class Broadcast {
        /** Disable for benchmarks so measurements only see the direct ack. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Per-session send rate limit. */
    public static class RateLimit {
        /** Off by default so existing benchmarks are unaffected. */
        private boolean enabled = false;

        /** Sustained messages per second allowed per session. */
        private double messagesPerSecond = 20.0;

        /** How far a session may burst above the sustained rate. */
        private int burstSize = 40;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMessagesPerSecond() {
            return messagesPerSecond;
        }

        public void setMessagesPerSecond(double messagesPerSecond) {
            this.messagesPerSecond = messagesPerSecond;
        }

        public int getBurstSize() {
            return burstSize;
        }

        public void setBurstSize(int burstSize) {
            this.burstSize = burstSize;
        }
    }

    /** Bounded pool backing async message persistence. */
    public static class Persistence {
        private int corePoolSize = 8;
        private int maxPoolSize = 32;
        private int queueCapacity = 10000;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /** Per-connection WebSocket limits. */
    public static class Ws {
        private int maxTextBytes = 8192;
        private int maxBinaryBytes = 8192;
        private long idleTimeoutMs = 300000;

        /** Origins permitted to open a chat connection; "*" allows any. */
        private String[] allowedOrigins = {"*"};

        public int getMaxTextBytes() {
            return maxTextBytes;
        }

        public void setMaxTextBytes(int maxTextBytes) {
            this.maxTextBytes = maxTextBytes;
        }

        public int getMaxBinaryBytes() {
            return maxBinaryBytes;
        }

        public void setMaxBinaryBytes(int maxBinaryBytes) {
            this.maxBinaryBytes = maxBinaryBytes;
        }

        public long getIdleTimeoutMs() {
            return idleTimeoutMs;
        }

        public void setIdleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
        }

        public String[] getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String[] allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
