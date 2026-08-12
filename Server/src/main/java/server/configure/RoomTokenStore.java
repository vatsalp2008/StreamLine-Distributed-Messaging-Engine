package server.configure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Room secrets, reloadable while the server is running.
 *
 * Tokens configured in application properties are fixed for the life of the
 * process, so rotating one meant a restart and dropping every open connection.
 * When a file is configured its contents are re-read periodically and take
 * precedence, so a room's secret can be changed in place.
 *
 * The file is a plain properties file of roomId=token. A room absent from it
 * falls back to whatever was configured statically.
 */
@Component
public class RoomTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RoomTokenStore.class);

    private final Map<String, String> configured;
    private final Path source;

    /** Swapped wholesale on reload, so readers never see a half-applied file. */
    private final AtomicReference<Map<String, String>> fromFile =
            new AtomicReference<>(Map.of());

    private final AtomicReference<String> lastError = new AtomicReference<>();

    public RoomTokenStore(StreamlineProperties properties) {
        this.configured = properties.getAuth().getRoomTokens();
        String path = properties.getAuth().getRoomTokenFile();
        this.source = (path == null || path.isBlank()) ? null : Path.of(path);

        if (source != null) {
            reload();
        }
    }

    /**
     * @param roomId the room being entered
     * @return that room's secret, or null when it has none
     */
    public String tokenFor(String roomId) {
        if (roomId == null) {
            return null;
        }

        String reloaded = fromFile.get().get(roomId);
        return reloaded != null ? reloaded : configured.get(roomId);
    }

    /**
     * Re-reads the token file, if one is configured.
     *
     * A missing or unreadable file leaves the previous tokens in place rather
     * than dropping them: losing every room secret because of a transient read
     * failure would silently downgrade a private room to the shared token.
     */
    @Scheduled(fixedDelayString = "${streamline.auth.room-token-reload-ms:30000}")
    public void reload() {
        if (source == null) {
            return;
        }

        try {
            Properties loaded = new Properties();
            try (var reader = Files.newBufferedReader(source)) {
                loaded.load(reader);
            }

            Map<String, String> next = new HashMap<>();
            loaded.forEach((key, value) -> {
                String token = String.valueOf(value).trim();
                if (!token.isEmpty()) {
                    next.put(String.valueOf(key), token);
                }
            });

            Map<String, String> previous = fromFile.getAndSet(Map.copyOf(next));
            lastError.set(null);

            if (!previous.equals(next)) {
                log.info("Reloaded {} room token(s) from {}", next.size(), source);
            }
        } catch (IOException | RuntimeException e) {
            // keep serving the tokens we already have
            if (lastError.getAndSet(e.getMessage()) == null) {
                log.warn("Could not read room tokens from {}: {}", source, e.getMessage());
            }
        }
    }

    /** @return how many rooms currently have a secret from the file */
    public int reloadedCount() {
        return fromFile.get().size();
    }

    /** @return true when a token file is configured at all */
    public boolean isFileConfigured() {
        return source != null;
    }

    /**
     * @return why the last reload failed, or null when the last one worked
     */
    public String lastError() {
        return lastError.get();
    }
}
