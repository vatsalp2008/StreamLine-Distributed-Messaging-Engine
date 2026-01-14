package client2;

/**
 * class MessageData to store message tracking data
 */
public class MessageData {

    long timestamp;
    String messageType;
    long latency;
    String statusCode;
    int roomId;

    /**
     * @param timestamps -long, Representing Timestamp
     * @param msgType -String, Representing msg Type
     * @param latency -long, Representing Latency
     * @param status -String, Representing Status
     * @param room -int, Representing room id
     */
    public MessageData(long timestamps, String msgType, long latency, String status, int room) {
        this.timestamp = timestamps;
        this.messageType = msgType;
        this.latency = latency;
        this.statusCode = status;
        this.roomId = room;
    }
}