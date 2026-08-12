package server.configure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which address a request should be attributed to.
 *
 * Behind a reverse proxy every request arrives from the proxy, so keying a rate
 * limit on the socket address puts every caller in one bucket: one busy client
 * throttles everyone.
 *
 * The obvious fix, trusting X-Forwarded-For, is worse: any caller can set that
 * header and hand themselves a fresh bucket per request, which removes the
 * limit entirely. So the header is honoured only when the request actually came
 * from an address configured as a trusted proxy.
 */
@Component
public class ClientAddressResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";

    private final Set<String> trustedProxies;

    public ClientAddressResolver(StreamlineProperties properties) {
        this.trustedProxies = new LinkedHashSet<>(properties.getProxy().getTrusted());
    }

    /**
     * @param request the incoming request
     * @return the address to attribute this request to, never null
     */
    public String resolve(HttpServletRequest request) {
        String socketAddress = request.getRemoteAddr();
        if (socketAddress == null || socketAddress.isBlank()) {
            return UNKNOWN;
        }

        if (!trustedProxies.contains(socketAddress)) {
            // Not a proxy we know, so its own address is the truth and any
            // forwarding header it sent is just something it made up.
            return socketAddress;
        }

        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return socketAddress;
        }

        return firstUntrusted(forwarded, socketAddress);
    }

    /**
     * Reads an X-Forwarded-For chain right to left, past our own proxies.
     *
     * The list is client, proxy1, proxy2..., appended in order, so the entries
     * nearest the end are the ones our own infrastructure added and can be
     * believed. Walking from the right and stopping at the first address that is
     * not a trusted proxy avoids trusting anything the caller prepended.
     *
     * @return the nearest address that is not one of our proxies
     */
    private String firstUntrusted(String forwardedFor, String fallback) {
        List<String> hops = List.of(forwardedFor.split(","));

        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i).trim();
            if (hop.isEmpty()) {
                continue;
            }
            if (!trustedProxies.contains(hop)) {
                return hop;
            }
        }

        // every hop was one of ours, so the request never had a client address
        return fallback;
    }

    /** @return true when at least one proxy is configured as trusted */
    public boolean hasTrustedProxies() {
        return !trustedProxies.isEmpty();
    }
}
