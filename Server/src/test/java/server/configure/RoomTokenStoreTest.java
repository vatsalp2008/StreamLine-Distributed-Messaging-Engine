package server.configure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Room secrets that can change while the server is running.
 *
 * Rotating a token used to mean a restart, which drops every open connection.
 */
class RoomTokenStoreTest {

    @TempDir
    Path tempDir;

    private StreamlineProperties properties(Map<String, String> configured, Path file) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getAuth().setRoomTokens(new HashMap<>(configured));
        properties.getAuth().setRoomTokenFile(file == null ? "" : file.toString());
        return properties;
    }

    private Path writeTokens(String contents) throws IOException {
        Path file = tempDir.resolve("room-tokens.properties");
        Files.writeString(file, contents);
        return file;
    }

    @Test
    void configuredTokensAreServedWhenNoFileIsSet() {
        RoomTokenStore store = new RoomTokenStore(
                properties(Map.of("private", "configured-token"), null));

        assertThat(store.tokenFor("private")).isEqualTo("configured-token");
    }

    @Test
    void aRoomWithNoSecretReportsNothing() {
        RoomTokenStore store = new RoomTokenStore(properties(Map.of(), null));

        assertThat(store.tokenFor("general")).isNull();
        assertThat(store.tokenFor(null)).isNull();
    }

    @Test
    void tokensAreReadFromTheFile() throws IOException {
        Path file = writeTokens("private=file-token\n");
        RoomTokenStore store = new RoomTokenStore(properties(Map.of(), file));

        assertThat(store.tokenFor("private")).isEqualTo("file-token");
    }

    @Test
    void theFileOverridesConfiguration() throws IOException {
        Path file = writeTokens("private=file-token\n");
        RoomTokenStore store = new RoomTokenStore(
                properties(Map.of("private", "configured-token"), file));

        assertThat(store.tokenFor("private")).isEqualTo("file-token");
    }

    @Test
    void roomsAbsentFromTheFileStillUseConfiguration() throws IOException {
        Path file = writeTokens("other=file-token\n");
        RoomTokenStore store = new RoomTokenStore(
                properties(Map.of("private", "configured-token"), file));

        assertThat(store.tokenFor("private")).isEqualTo("configured-token");
    }

    @Test
    void aRotatedTokenTakesEffectOnReload() throws IOException {
        Path file = writeTokens("private=first-token\n");
        RoomTokenStore store = new RoomTokenStore(properties(Map.of(), file));
        assertThat(store.tokenFor("private")).isEqualTo("first-token");

        Files.writeString(file, "private=second-token\n");
        store.reload();

        // the point of the whole class: no restart needed
        assertThat(store.tokenFor("private")).isEqualTo("second-token");
    }

    @Test
    void aRoomRemovedFromTheFileFallsBackToConfiguration() throws IOException {
        Path file = writeTokens("private=file-token\n");
        RoomTokenStore store = new RoomTokenStore(
                properties(Map.of("private", "configured-token"), file));

        Files.writeString(file, "");
        store.reload();

        assertThat(store.tokenFor("private")).isEqualTo("configured-token");
    }

    @Test
    void aMissingFileLeavesThePreviousTokensInPlace() throws IOException {
        Path file = writeTokens("private=file-token\n");
        RoomTokenStore store = new RoomTokenStore(properties(Map.of(), file));

        Files.delete(file);
        store.reload();

        // dropping every secret on a transient read failure would quietly
        // downgrade a private room to the shared token
        assertThat(store.tokenFor("private")).isEqualTo("file-token");
    }

    @Test
    void anUnreadableFileAtStartupIsNotFatal() {
        RoomTokenStore store = new RoomTokenStore(
                properties(Map.of("private", "configured-token"),
                        tempDir.resolve("never-created.properties")));

        assertThat(store.tokenFor("private")).isEqualTo("configured-token");
    }

    @Test
    void blankEntriesAreIgnored() throws IOException {
        Path file = writeTokens("private=\nother=  \n");
        RoomTokenStore store = new RoomTokenStore(
                properties(Map.of("private", "configured-token"), file));

        // an empty value would otherwise mask the configured secret
        assertThat(store.tokenFor("private")).isEqualTo("configured-token");
        assertThat(store.reloadedCount()).isZero();
    }

    @Test
    void surroundingWhitespaceIsTrimmed() throws IOException {
        Path file = writeTokens("private=  spaced-token  \n");
        RoomTokenStore store = new RoomTokenStore(properties(Map.of(), file));

        assertThat(store.tokenFor("private")).isEqualTo("spaced-token");
    }

    @Test
    void severalRoomsAreLoadedTogether() throws IOException {
        Path file = writeTokens("alpha=one\nbeta=two\n");
        RoomTokenStore store = new RoomTokenStore(properties(Map.of(), file));

        assertThat(store.tokenFor("alpha")).isEqualTo("one");
        assertThat(store.tokenFor("beta")).isEqualTo("two");
        assertThat(store.reloadedCount()).isEqualTo(2);
    }

    @Test
    void theAuthenticatorHonoursARotatedToken() throws IOException {
        Path file = writeTokens("private=first-token-long-enough\n");
        StreamlineProperties properties = properties(Map.of(), file);
        properties.getAuth().setEnabled(true);
        properties.getAuth().setToken("shared-token-long-enough");
        RoomTokenStore store = new RoomTokenStore(properties);
        TokenAuthenticator auth = new TokenAuthenticator(properties, store);

        assertThat(auth.isAuthorisedForRoom("private", "first-token-long-enough")).isTrue();

        Files.writeString(file, "private=second-token-long-enough\n");
        store.reload();

        assertThat(auth.isAuthorisedForRoom("private", "first-token-long-enough")).isFalse();
        assertThat(auth.isAuthorisedForRoom("private", "second-token-long-enough")).isTrue();
    }
}
