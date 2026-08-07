package server.configure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenAuthenticatorTest {

    private static final String VALID_TOKEN = "s3cret-token-long-enough";

    private TokenAuthenticator authenticator(boolean enabled, String token) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getAuth().setEnabled(enabled);
        properties.getAuth().setToken(token);
        return new TokenAuthenticator(properties);
    }

    // ---------- disabled ----------

    @Test
    void everythingIsAllowedWhenAuthIsDisabled() {
        TokenAuthenticator auth = authenticator(false, "");

        assertThat(auth.isEnabled()).isFalse();
        assertThat(auth.isAuthorised(null)).isTrue();
        assertThat(auth.isAuthorised("")).isTrue();
        assertThat(auth.isAuthorised("anything at all")).isTrue();
    }

    @Test
    void disabledAuthStartsWithoutAToken() {
        assertThatCode(() -> authenticator(false, "").validateConfiguration())
                .doesNotThrowAnyException();
    }

    // ---------- enabled ----------

    @Test
    void theCorrectTokenIsAccepted() {
        assertThat(authenticator(true, VALID_TOKEN).isAuthorised(VALID_TOKEN)).isTrue();
    }

    @Test
    void aWrongTokenIsRejected() {
        TokenAuthenticator auth = authenticator(true, VALID_TOKEN);

        assertThat(auth.isAuthorised("wrong-token-also-long")).isFalse();
    }

    @Test
    void aMissingTokenIsRejected() {
        TokenAuthenticator auth = authenticator(true, VALID_TOKEN);

        assertThat(auth.isAuthorised(null)).isFalse();
        assertThat(auth.isAuthorised("")).isFalse();
    }

    @Test
    void aTokenDifferingOnlyInCaseIsRejected() {
        assertThat(authenticator(true, VALID_TOKEN).isAuthorised(VALID_TOKEN.toUpperCase()))
                .isFalse();
    }

    @Test
    void aPrefixOfTheTokenIsRejected() {
        TokenAuthenticator auth = authenticator(true, VALID_TOKEN);

        // a length-only or prefix check would let this through
        assertThat(auth.isAuthorised(VALID_TOKEN.substring(0, VALID_TOKEN.length() - 1)))
                .isFalse();
    }

    @Test
    void aTokenWithTrailingWhitespaceIsRejected() {
        assertThat(authenticator(true, VALID_TOKEN).isAuthorised(VALID_TOKEN + " ")).isFalse();
    }

    @Test
    void aLongerStringStartingWithTheTokenIsRejected() {
        assertThat(authenticator(true, VALID_TOKEN).isAuthorised(VALID_TOKEN + "extra"))
                .isFalse();
    }

    // ---------- startup validation ----------

    @Test
    void enablingAuthWithoutATokenFailsStartup() {
        assertThatThrownBy(() -> authenticator(true, "").validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("streamline.auth.token is empty");
    }

    @Test
    void enablingAuthWithABlankTokenFailsStartup() {
        assertThatThrownBy(() -> authenticator(true, "    ").validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aShortTokenFailsStartup() {
        assertThatThrownBy(() -> authenticator(true, "tooshort").validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least " + TokenAuthenticator.MIN_TOKEN_LENGTH);
    }

    @Test
    void aTokenOfExactlyTheMinimumLengthIsAccepted() {
        String token = "x".repeat(TokenAuthenticator.MIN_TOKEN_LENGTH);

        assertThatCode(() -> authenticator(true, token).validateConfiguration())
                .doesNotThrowAnyException();
    }

    // ---------- comparison is not short-circuiting ----------

    @Test
    void comparisonCostDoesNotDependOnHowMuchOfTheTokenIsCorrect() {
        String token = "a".repeat(4096);
        TokenAuthenticator auth = authenticator(true, token);

        String wrongAtStart = "b" + "a".repeat(4095);
        String wrongAtEnd = "a".repeat(4095) + "b";

        long earlyMismatch = timeRepeatedChecks(auth, wrongAtStart);
        long lateMismatch = timeRepeatedChecks(auth, wrongAtEnd);

        assertThat(auth.isAuthorised(wrongAtStart)).isFalse();
        assertThat(auth.isAuthorised(wrongAtEnd)).isFalse();

        // A short-circuiting compare returns almost immediately when the very
        // first byte differs, so the two would differ by orders of magnitude.
        // Wall-clock timing is noisy, so this only asserts the same ballpark.
        long slower = Math.max(earlyMismatch, lateMismatch);
        long faster = Math.max(Math.min(earlyMismatch, lateMismatch), 1);
        assertThat((double) slower / faster)
                .as("early=%dns late=%dns", earlyMismatch, lateMismatch)
                .isLessThan(50.0);
    }

    private long timeRepeatedChecks(TokenAuthenticator auth, String candidate) {
        for (int i = 0; i < 2_000; i++) {
            auth.isAuthorised(candidate);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            auth.isAuthorised(candidate);
        }
        return (System.nanoTime() - start) / 20_000;
    }

    // ---------- exposed settings ----------

    @Test
    void headerAndQueryParamNamesAreExposedForTheFilters() {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getAuth().setHeader("X-Custom-Token");
        properties.getAuth().setQueryParam("access_token");
        TokenAuthenticator auth = new TokenAuthenticator(properties);

        assertThat(auth.headerName()).isEqualTo("X-Custom-Token");
        assertThat(auth.queryParamName()).isEqualTo("access_token");
    }
}
