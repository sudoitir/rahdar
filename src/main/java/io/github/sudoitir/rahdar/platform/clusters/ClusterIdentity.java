package io.github.sudoitir.rahdar.platform.clusters;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What makes two registrations the same cluster (ADR-0167).
 *
 * <p>The brokers' own NodeIDs come first: a NodeID is the broker's journal identity, so it is the
 * same whether the broker is reached by host name, by IP address, through another port or with a
 * trailing slash. A registration whose discovered nodes include any NodeID a registered cluster
 * already holds is the same brokers, even when only one node overlaps. The management URL, in its
 * {@linkplain #normalise normal form}, is the fallback for a seed whose broker did not report a
 * NodeID.
 *
 * @param nodeIds every NodeID the seeds reported, their own and the ones in their topology view
 * @param seedUrls the normal form of every seed's management URL
 * @param unidentifiedUrls the normal form of the seeds that reported no NodeID, claimed by URL instead
 */
record ClusterIdentity(Set<String> nodeIds, Set<String> seedUrls, Set<String> unidentifiedUrls) {

    /** The kind of a claim in {@code broker_identity}. */
    static final String NODE_ID = "NODE_ID";

    static final String URL = "URL";

    ClusterIdentity {
        nodeIds = Set.copyOf(nodeIds);
        seedUrls = Set.copyOf(seedUrls);
        unidentifiedUrls = Set.copyOf(unidentifiedUrls);
    }

    /** A registered cluster that already holds some of these brokers, and the nodes of it that match. */
    record Overlap(UUID clusterId, String clusterName, List<String> nodes) {}

    /** What this identity claims in {@code broker_identity}: every NodeID, and the URL of a seed without one. */
    Map<String, Set<String>> claims() {
        return Map.of(NODE_ID, nodeIds, URL, unidentifiedUrls);
    }

    /**
     * What a registered cluster's nodes claim now: every NodeID they carry, and the management URL of a
     * node that carries none. The same rule as a registration's {@link #claims()}, read from the nodes.
     */
    static Map<String, Set<String>> claimsOf(Collection<? extends ClusterNode> nodes) {
        Set<String> nodeIds = new TreeSet<>();
        Set<String> urls = new TreeSet<>();
        for (ClusterNode node : nodes) {
            if (node.getNativeNodeId() != null) {
                nodeIds.add(node.getNativeNodeId());
            } else if (node.getManagementUrl() != null) {
                urls.add(normalise(node.getManagementUrl()));
            }
        }
        return Map.of(NODE_ID, nodeIds, URL, urls);
    }

    /**
     * The registered cluster these brokers overlap, if any: a node of it matches when it carries one of
     * the NodeIDs, or its management URL has the normal form of one of the seeds. When several clusters
     * overlap, the one with the most matching nodes is named, then the first by name.
     *
     * @param registered nodes of registered clusters; any others are simply not matched
     * @param clusterNames each registered cluster's name, by id
     */
    Optional<Overlap> overlapWith(Collection<? extends ClusterNode> registered, Map<UUID, String> clusterNames) {
        Map<UUID, Set<String>> matched = new TreeMap<>();
        for (ClusterNode node : registered) {
            boolean sameNode = node.getNativeNodeId() != null && nodeIds.contains(node.getNativeNodeId());
            boolean sameUrl = node.getManagementUrl() != null && seedUrls.contains(normalise(node.getManagementUrl()));
            if (sameNode || sameUrl) {
                matched.computeIfAbsent(node.getClusterId(), k -> new TreeSet<>())
                        .add(node.getName());
            }
        }
        Comparator<Map.Entry<UUID, Set<String>>> mostNodes =
                Comparator.comparingInt(e -> -e.getValue().size());
        return matched.entrySet().stream()
                .min(mostNodes.thenComparing(e -> clusterNames.getOrDefault(e.getKey(), "")))
                .map(e -> new Overlap(
                        e.getKey(), clusterNames.getOrDefault(e.getKey(), ""), new ArrayList<>(e.getValue())));
    }

    /**
     * The normal form of a management URL: scheme and host in lower case, the port always explicit (the
     * scheme's default when absent, which is where the client connects), the path without trailing
     * slashes, and no query, fragment or user info. Host aliases are not resolved; the NodeID covers
     * them. A value that does not parse is only trimmed and lower-cased.
     */
    static String normalise(String url) {
        String trimmed = url.trim();
        try {
            URI u = new URI(trimmed);
            if (u.getScheme() == null || u.getHost() == null) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            int port = u.getPort() > 0 ? u.getPort() : defaultPort(scheme);
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + ":" + port
                    + withoutTrailingSlashes(u.getPath());
        } catch (URISyntaxException _) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    private static String withoutTrailingSlashes(String path) {
        if (path == null) {
            return "";
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }
}
