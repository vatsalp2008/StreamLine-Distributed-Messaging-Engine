package client2.model;

/**
 * Stores per-message performance metrics
 */
public class Metric {
    private long timestamp;        // When message was sent
    private String messageType;    // TEXT, JOIN, LEAVE
    private long latencyMs;        // Time to get response
    private String statusCode;     // OK or ERROR
    private int roomId;            // Which room

    public Metric(long timestamp, String messageType, long latencyMs,
                         String statusCode, int roomId) {
        this.timestamp = timestamp;
        this.messageType = messageType;
        this.latencyMs = latencyMs;
        this.statusCode = statusCode;
        this.roomId = roomId;
    }

    // Getters
    public long getTimestamp() { return timestamp; }
    public String getMessageType() { return messageType; }
    public long getLatencyMs() { return latencyMs; }
    public String getStatusCode() { return statusCode; }
    public int getRoomId() { return roomId; }

    // CSV format
    public String toCSV() {
        return timestamp + "," + messageType + "," + latencyMs + "," +
                statusCode + "," + roomId;
    }
}