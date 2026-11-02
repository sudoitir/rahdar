package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.Plane;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The management API base URLs of a RabbitMQ cluster, {@code http(s)://host:port[/prefix]}: every token of every
 * entry, comma, space or new line separated, with the port defaulting to 15672 (15671 for https), the scheme and host
 * in lower case and no trailing slash, without duplicates. An account in the URL, a query or a fragment is refused.
 */
final class ManagementUrls {

    /** How many management URLs a request may carry. */
    static final int MAX = 16;

    static final String HTTP = "http";
    static final String HTTPS = "https";
    static final int HTTP_PORT = 15672;
    static final int HTTPS_PORT = 15671;

    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");

    private ManagementUrls() {}

    /** The normalised URLs, in the order given; at least one and at most {@link #MAX}. */
    static List<String> parse(List<String> entries) {
        Set<String> urls = new LinkedHashSet<>();
        if (entries != null) {
            for (String entry : entries) {
                for (String token : SEPARATORS.split(entry == null ? "" : entry.trim())) {
                    if (!token.isEmpty()) {
                        urls.add(normalised(token));
                    }
                }
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("At least one management URL is required.");
        }
        if (urls.size() > MAX) {
            throw new IllegalArgumentException("At most " + MAX + " management URLs, not " + urls.size() + ".");
        }
        return List.copyOf(urls);
    }

    /** The cluster endpoint for a normalised URL: the management plane, its scheme as protocol. */
    static Endpoint endpoint(String url) {
        return Endpoint.of(Plane.MANAGEMENT, scheme(url), url);
    }

    /** The host of a normalised URL, such as {@code rabbit-1}. */
    static String host(String url) {
        return URI.create(url).getHost();
    }

    private static String scheme(String url) {
        return url.substring(0, url.indexOf("://"));
    }

    private static String normalised(String token) {
        URI uri;
        try {
            uri = new URI(token);
        } catch (URISyntaxException _) {
            throw invalid(token);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)
                || uri.getHost() == null
                || uri.getHost().isEmpty()) {
            throw invalid(token);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "'" + hidden(token) + "' must not carry an account: put it in the credentials.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("'" + token + "' must not carry a query or a fragment.");
        }
        int port = uri.getPort() < 0 ? defaultPort(scheme) : uri.getPort();
        if (port < 1 || port > 65535) {
            throw invalid(token);
        }
        String path = withoutTrailingSlashes(uri.getRawPath());
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + path;
    }

    /** The path without the slashes at its end, found by a scan so a long run of them costs only its length. */
    private static String withoutTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static int defaultPort(String scheme) {
        return HTTPS.equals(scheme) ? HTTPS_PORT : HTTP_PORT;
    }

    /** The token without what stands before an {@code @}, so a mistaken password never reaches a message. */
    private static String hidden(String token) {
        int scheme = token.indexOf("://");
        int at = token.indexOf('@');
        return scheme >= 0 && at > scheme ? token.substring(0, scheme + 3) + token.substring(at + 1) : token;
    }

    private static IllegalArgumentException invalid(String token) {
        return new IllegalArgumentException("'" + hidden(token) + "' must be http(s)://host:port, optionally followed"
                + " by a path prefix, with a port from 1 to 65535.");
    }
}
