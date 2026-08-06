package client2;

/**
 * One measured message: when it was sent, how long the acknowledgement took,
 * and how it was answered.
 *
 * Instances are produced by the sender threads and read by the reporting code,
 * so the state is immutable and reached through accessors rather than fields.
 */
public final class MessageData {

    private final long timestamp;
    private final String messageType;
    private final long latency;
    private final String statusCode;
    private final int roomId;

    /**
     * @param timestamp   -long, Representing when the message was sent
     * @param messageType -String, Representing msg Type
     * @param latency     -long, Representing Latency in ms
     * @param statusCode  -String, Representing Status
     * @param roomId      -int, Representing room id
     */
    public MessageData(long timestamp, String messageType, long latency, String statusCode, int roomId) {
        this.timestamp = timestamp;
        this.messageType = messageType;
        this.latency = latency;
        this.statusCode = statusCode;
        this.roomId = roomId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getMessageType() {
        return messageType;
    }

    public long getLatency() {
        return latency;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public int getRoomId() {
        return roomId;
    }

    /**
     * @return this row in the column order of the metrics CSV header
     */
    public String toCsvRow() {
        return timestamp + "," + messageType + "," + latency + "," + statusCode + "," + roomId;
    }
}
