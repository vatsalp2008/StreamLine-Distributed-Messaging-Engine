package server.configure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters describing chat traffic.
 *
 * Actuator already reports JVM and HTTP metrics, but nothing about what the
 * server is actually doing. These make the interesting rates visible: how much
 * traffic is being rejected, and how much fan-out each message causes.
 */
@Component
public class ChatMetrics {

    private final Counter messagesAccepted;
    private final Counter messagesRejected;
    private final Counter rateLimited;
    private final Counter broadcastsSent;
    private final Counter identityRejected;

    public ChatMetrics(MeterRegistry registry) {
        this.messagesAccepted = Counter.builder("streamline.messages.accepted")
                .description("Frames that passed validation and were processed")
                .register(registry);

        this.messagesRejected = Counter.builder("streamline.messages.rejected")
                .description("Frames refused for validation or protocol reasons")
                .register(registry);

        this.rateLimited = Counter.builder("streamline.messages.rate_limited")
                .description("Frames dropped because the session exceeded its send rate")
                .register(registry);

        this.identityRejected = Counter.builder("streamline.messages.identity_rejected")
                .description("Frames refused because they did not match the session's identity")
                .register(registry);

        this.broadcastsSent = Counter.builder("streamline.broadcasts.sent")
                .description("Copies fanned out to other members of a room")
                .register(registry);
    }

    public void recordAccepted() {
        messagesAccepted.increment();
    }

    /**
     * A frame was refused for claiming the wrong username, or for taking a name
     * already in the room. Counted separately from ordinary validation failures
     * because a rising rate here means someone is probing, not fat-fingering.
     */
    public void recordIdentityRejected() {
        identityRejected.increment();
        messagesRejected.increment();
    }

    public void recordRejected() {
        messagesRejected.increment();
    }

    public void recordRateLimited() {
        rateLimited.increment();
    }

    /**
     * @param recipients how many peers received a copy; zero is not recorded so
     *                   the counter reflects delivered copies rather than calls
     */
    public void recordBroadcast(int recipients) {
        if (recipients > 0) {
            broadcastsSent.increment(recipients);
        }
    }
}
