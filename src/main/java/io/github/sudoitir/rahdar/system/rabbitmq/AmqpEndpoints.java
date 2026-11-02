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
 * The AMQP endpoints of a RabbitMQ cluster, {@code amqp(s)://host:port}: every token of every entry, comma, space or
 * new line separated, with the port defaulting to 5672 (5671 for amqps), without duplicates. None is allowed, which is
 * a cluster managed without a data plane. An account, a path, a query or a fragment is refused.
 */
final class AmqpEndpoints {

    /** How many AMQP endpoints a request may carry. */
    static final int MAX = 32;

    static final String AMQP = "amqp";
    static final String AMQPS = "amqps";
    static final int AMQP_PORT = 5672;
    static final int AMQPS_PORT = 5671;

    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");

    private AmqpEndpoints() {}

    /** The normalised endpoints, in the order given; empty when there are none, at most {@link #MAX}. */
    static List<String> parse(List<String> entries) {
        Set<String> endpoints = new LinkedHashSet<>();
        if (entries != null) {
            for (String entry : entries) {
                for (String token : SEPARATORS.split(entry == null ? "" : entry.trim())) {
                    if (!token.isEmpty()) {
                        endpoints.add(normalised(token));
                    }
                }
            }
        }
        if (endpoints.size() > MAX) {
            throw new IllegalArgumentException("At most " + MAX + " AMQP endpoints, not " + endpoints.size() + ".");
        }
        return List.copyOf(endpoints);
    }

    /** The cluster endpoint for a normalised entry: the data plane, its scheme as protocol, {@code host:port}. */
    static Endpoint endpoint(String entry) {
        return Endpoint.of(Plane.DATA, entry.substring(0, entry.indexOf("://")), hostAndPort(entry));
    }

    /** The {@code host:port} of a normalised entry. */
    static String hostAndPort(String entry) {
        return entry.substring(entry.indexOf("://") + 3);
    }

    private static int defaultPort(String scheme) {
        return AMQPS.equals(scheme) ? AMQPS_PORT : AMQP_PORT;
    }

    private static String normalised(String token) {
        URI uri;
        try {
            uri = new URI(token);
        } catch (URISyntaxException _) {
            throw invalid(token);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!AMQP.equals(scheme) && !AMQPS.equals(scheme)
                || uri.getHost() == null
                || uri.getHost().isEmpty()) {
            throw invalid(token);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "An AMQP endpoint must not carry an account: put it in the credentials.");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "'" + token + "' must be amqp(s)://host:port, without a path, a query or a fragment.");
        }
        int port = uri.getPort() < 0 ? defaultPort(scheme) : uri.getPort();
        if (port < 1 || port > 65535) {
            throw invalid(token);
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static IllegalArgumentException invalid(String token) {
        return new IllegalArgumentException("'" + token + "' must be amqp(s)://host:port with a port from 1 to 65535.");
    }
}
