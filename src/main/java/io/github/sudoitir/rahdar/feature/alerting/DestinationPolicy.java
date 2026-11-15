package io.github.sudoitir.rahdar.feature.alerting;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Whether a notification channel may send where it points (ADR-0227). A channel's destination is operator input, and
 * the server fetches it, so without this a holder of {@code alert:write} turns the test button into a request to any
 * address the process can reach — the loopback, the private network, a cloud metadata service — and reads the result
 * back out of the response.
 *
 * <p>Only {@code https} is accepted: a webhook carries alert content, and plain HTTP would put it on the wire in
 * clear. A name that does not resolve is accepted here and fails at the connection, where an outage stays a retryable
 * failure rather than a channel the operator has to recreate.
 *
 * <p>The address a name resolves to is checked, not the name. A name that resolves between this check and the
 * connection could still reach elsewhere; that window is documented rather than closed, because closing it needs the
 * socket pinned to the address checked (ADR-0227).
 */
@Component
public class DestinationPolicy {

    /** The destination as it will be fetched, or a refusal naming {@code field} and the reason. */
    public URI requirePublic(String field, String value) {
        URI uri = parse(field, value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            throw new IllegalArgumentException(field + ": must be an https URL");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(field + ": must not carry credentials in the URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(field + ": the URL must name a host");
        }
        for (InetAddress address : resolve(host)) {
            if (isInternal(address)) {
                throw new IllegalArgumentException(field + ": must not name an internal address");
            }
        }
        return uri;
    }

    private static URI parse(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + ": a URL is required");
        }
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException(field + ": not a valid URL");
        }
    }

    /** Every address the name answers with; none when it does not resolve, so the connection decides. */
    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException _) {
            return new InetAddress[0];
        }
    }

    /**
     * Loopback, any-local, link-local (which is where a cloud metadata service lives), site-local and unique-local
     * (RFC 1918 and {@code fc00::/7}), multicast, and the carrier-grade NAT range a host reaches a provider's
     * internal services through.
     */
    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // 100.64.0.0/10, RFC 6598.
            return first == 100 && second >= 64 && second <= 127;
        }
        // fc00::/7, RFC 4193.
        return (bytes[0] & 0xfe) == 0xfc;
    }
}
