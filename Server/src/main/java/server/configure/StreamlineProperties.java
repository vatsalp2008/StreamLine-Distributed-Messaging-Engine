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
    private final Auth auth = new Auth();
    private final Identity identity = new Identity();
    private final Limits limits = new Limits();
    private final Retention retention = new Retention();
    private final Receipts receipts = new Receipts();

    public Broadcast getBroadcast() {
        return broadcast;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Auth getAuth() {
        return auth;
    }

    public Identity getIdentity() {
        return identity;
    }

    public Limits getLimits() {
        return limits;
    }

    public Retention getRetention() {
        return retention;
    }

    public Receipts getReceipts() {
        return receipts;
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

    /**
     * Shared-secret access control.
     *
     * Off by default: turning it on without warning would break every existing
     * deployment and both benchmark clients. Enable it, set a token, and every
     * WebSocket handshake and API call must present that token.
     */
    public static class Auth {
        private boolean enabled = false;

        /**
         * The shared secret. Required when auth is enabled; startup fails rather
         * than silently accepting everything if it is missing.
         */
        private String token = "";

        /** Header carrying the token on REST calls and WebSocket handshakes. */
        private String header = "X-Streamline-Token";

        /**
         * Query parameter accepted as a fallback on the WebSocket handshake.
         * Browsers cannot set headers on a WebSocket handshake, so the parameter
         * is the only way a browser client can authenticate.
         */
        private String queryParam = "token";

        /** Leave the probes open so a load balancer can reach them unauthenticated. */
        private boolean protectActuator = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public String getQueryParam() {
            return queryParam;
        }

        public void setQueryParam(String queryParam) {
            this.queryParam = queryParam;
        }

        public boolean isProtectActuator() {
            return protectActuator;
        }

        public void setProtectActuator(boolean protectActuator) {
            this.protectActuator = protectActuator;
        }
    }

    /**
     * How strictly a session is held to the username it joined with.
     *
     * On by default: this is a protocol correctness property rather than a
     * deployment policy. Without it a single connection can claim a different
     * author on every frame, so nothing in a room's history can be trusted.
     */
    public static class Identity {
        /** Reject frames whose username differs from the one used to JOIN. */
        private boolean strict = true;

        /** Reject a JOIN for a username already present in the room. */
        private boolean uniqueUsernames = true;

        public boolean isStrict() {
            return strict;
        }

        public void setStrict(boolean strict) {
            this.strict = strict;
        }

        public boolean isUniqueUsernames() {
            return uniqueUsernames;
        }

        public void setUniqueUsernames(boolean uniqueUsernames) {
            this.uniqueUsernames = uniqueUsernames;
        }
    }

    /**
     * Caps on how much room state one server will hold.
     *
     * Room ids come from the connection URL, so without a cap any client can
     * make the server allocate an unbounded number of rooms simply by connecting
     * to new paths. Zero disables a limit.
     */
    public static class Limits {
        /** Maximum rooms that may exist at once; 0 for unlimited. */
        private int maxRooms = 1000;

        /** Maximum joined sessions in a single room; 0 for unlimited. */
        private int maxMembersPerRoom = 500;

        public int getMaxRooms() {
            return maxRooms;
        }

        public void setMaxRooms(int maxRooms) {
            this.maxRooms = maxRooms;
        }

        public int getMaxMembersPerRoom() {
            return maxMembersPerRoom;
        }

        public void setMaxMembersPerRoom(int maxMembersPerRoom) {
            this.maxMembersPerRoom = maxMembersPerRoom;
        }
    }

    /**
     * How long stored messages are kept.
     *
     * Off by default (0 = keep everything): switching an existing deployment to
     * a finite window without being asked would delete history it still has.
     */
    public static class Retention {
        /** Days of history to keep; 0 or less keeps everything. */
        private int days = 0;

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }
    }

    /**
     * Confirmation that a message reached durable storage.
     *
     * An OK acknowledges that a message was accepted, not that it was written:
     * persistence is asynchronous, so the two can differ. A receipt is a second
     * frame sent once the row exists.
     *
     * Off by default because it doubles server-to-client frames for senders that
     * do not care whether a message was durable.
     */
    public static class Receipts {
        private boolean enabled = false;

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
