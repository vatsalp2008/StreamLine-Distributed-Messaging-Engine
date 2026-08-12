package server.configure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which address a request is attributed to.
 *
 * This decides whose rate-limit bucket a request lands in, so the interesting
 * cases are the adversarial ones: a caller that sets its own forwarding header
 * must not be able to hand itself a fresh bucket.
 */
class ClientAddressResolverTest {

    private static final String PROXY = "10.0.0.1";
    private static final String CLIENT = "203.0.113.7";

    private ClientAddressResolver resolver(String... trusted) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getProxy().setTrusted(new java.util.ArrayList<>(List.of(trusted)));
        return new ClientAddressResolver(properties);
    }

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    // ---------- no proxies configured ----------

    @Test
    void theSocketAddressIsUsedWhenNoProxyIsTrusted() {
        assertThat(resolver().resolve(request(CLIENT, null))).isEqualTo(CLIENT);
    }

    @Test
    void aForwardingHeaderFromAnUntrustedSourceIsIgnored() {
        // otherwise any caller could claim to be anyone, per request
        assertThat(resolver().resolve(request(CLIENT, "1.2.3.4"))).isEqualTo(CLIENT);
    }

    @Test
    void aSpoofedHeaderCannotWinANewBucketEachRequest() {
        ClientAddressResolver resolver = resolver();

        String first = resolver.resolve(request(CLIENT, "1.1.1.1"));
        String second = resolver.resolve(request(CLIENT, "2.2.2.2"));

        // the whole point: both requests share one bucket
        assertThat(first).isEqualTo(second).isEqualTo(CLIENT);
    }

    // ---------- behind a trusted proxy ----------

    @Test
    void theForwardedClientIsUsedWhenTheProxyIsTrusted() {
        assertThat(resolver(PROXY).resolve(request(PROXY, CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    void twoClientsBehindOneProxyGetTheirOwnAddresses() {
        ClientAddressResolver resolver = resolver(PROXY);

        assertThat(resolver.resolve(request(PROXY, "203.0.113.1")))
                .isNotEqualTo(resolver.resolve(request(PROXY, "203.0.113.2")));
    }

    @Test
    void theProxyAddressIsUsedWhenItForwardsNoHeader() {
        assertThat(resolver(PROXY).resolve(request(PROXY, null))).isEqualTo(PROXY);
    }

    @Test
    void aBlankHeaderFallsBackToTheProxy() {
        assertThat(resolver(PROXY).resolve(request(PROXY, "   "))).isEqualTo(PROXY);
    }

    // ---------- chains ----------

    @Test
    void aChainIsReadPastOurOwnProxies() {
        // client, then two of our proxies appended in order
        String chain = CLIENT + ", 10.0.0.2, 10.0.0.1";

        assertThat(resolver("10.0.0.1", "10.0.0.2").resolve(request(PROXY, chain)))
                .isEqualTo(CLIENT);
    }

    @Test
    void anAddressPrependedByTheCallerIsNotTrustedOverTheRealOne() {
        // a caller sending "evil" arrives at the proxy, which appends the true
        // client address; reading from the right stops at the real one
        String chain = "1.2.3.4, " + CLIENT;

        assertThat(resolver(PROXY).resolve(request(PROXY, chain))).isEqualTo(CLIENT);
    }

    @Test
    void aChainOfOnlyOurProxiesFallsBackToTheSocketAddress() {
        String chain = "10.0.0.2, 10.0.0.1";

        assertThat(resolver("10.0.0.1", "10.0.0.2").resolve(request(PROXY, chain)))
                .isEqualTo(PROXY);
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(resolver(PROXY).resolve(request(PROXY, "  " + CLIENT + "  ")))
                .isEqualTo(CLIENT);
    }

    @Test
    void emptyEntriesInAChainAreSkipped() {
        assertThat(resolver(PROXY).resolve(request(PROXY, CLIENT + ", ,"))).isEqualTo(CLIENT);
    }

    // ---------- edge cases ----------

    @Test
    void aRequestWithNoAddressIsAttributedToUnknown() {
        assertThat(resolver(PROXY).resolve(request(null, CLIENT))).isEqualTo("unknown");
    }

    @Test
    void configuredProxiesAreReported() {
        assertThat(resolver(PROXY).hasTrustedProxies()).isTrue();
        assertThat(resolver().hasTrustedProxies()).isFalse();
    }
}
