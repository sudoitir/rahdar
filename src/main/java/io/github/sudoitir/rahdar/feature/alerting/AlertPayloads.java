package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.Transition;
import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.TransitionKind;
import io.github.sudoitir.rahdar.kernel.core.RahdarProperties;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds a delivery's payload (ADR-0105 D2). Version 2 is additive over the original
 * {@code ruleId}/{@code ruleName}/{@code severity}/{@code transitions[subject, kind, value]}: a
 * receiver written against those keeps working, and gains the cluster, readable subject labels,
 * times, counts and a link. Labels are resolved at enqueue time, so a delivery retried an hour
 * later still says what was true when it fired.
 */
@Component
@RequiredArgsConstructor
public class AlertPayloads {

    public static final String EVENT = "alert.transitions";
    public static final int VERSION = 2;

    private final ClusterDirectory clusters;
    private final RahdarProperties rahdar;
    private final ObjectMapper mapper;

    /** A transition as the payload carries it. */
    public record Item(String subject, TransitionKind kind, Double value) {
        static Item of(Transition t) {
            return new Item(t.subjectKey(), t.kind(), t.value());
        }
    }

    public String build(UUID ruleId, String ruleName, String severity, UUID clusterId, List<Item> items, Instant at) {
        String clusterName = clusterId == null
                ? null
                : clusters.cluster(clusterId).map(RegisteredCluster::getName).orElse(null);
        Map<String, String> nodeNames = new HashMap<>();
        if (clusterId != null) {
            for (ClusterNode node : clusters.nodes(clusterId)) {
                nodeNames.put(node.getId().toString(), node.getName());
            }
        }

        List<Map<String, Object>> transitions = new ArrayList<>();
        long fired = 0;
        for (Item item : items) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("subject", item.subject());
            t.put("kind", item.kind().name());
            t.put("value", item.value());
            t.put("subjectLabel", label(item.subject(), clusterName, nodeNames));
            t.put("at", at.toString());
            transitions.add(t);
            if (item.kind() == TransitionKind.FIRED) {
                fired++;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", EVENT);
        payload.put("version", VERSION);
        payload.put("ruleId", ruleId);
        payload.put("ruleName", ruleName);
        payload.put("severity", severity);
        payload.put("clusterId", clusterId);
        payload.put("clusterName", clusterName);
        payload.put("firedCount", fired);
        payload.put("resolvedCount", items.size() - fired);
        String url = rahdarUrl(clusterId);
        if (url != null) {
            payload.put("rahdarUrl", url);
        }
        payload.put("transitions", transitions);
        return mapper.writeValueAsString(payload);
    }

    /** {@code <public-url>/clusters/<id>/alerts}, or null when no public URL is configured. */
    String rahdarUrl(UUID clusterId) {
        if (!rahdar.hasPublicUrl()) {
            return null;
        }
        String base = rahdar.publicUrl();
        return clusterId == null ? base : base + "/clusters/" + clusterId + "/alerts";
    }

    /**
     * {@code node:<uuid>/subscription:orders} → {@code node primary-1 / subscription orders}. A part Rahdar does
     * not recognise is kept as it is: an unfamiliar key is still more useful than nothing.
     */
    static String label(String subject, String clusterName, Map<String, String> nodeNames) {
        if (subject == null) {
            return "";
        }
        if (subject.startsWith("setup:")) {
            String rest = subject.substring("setup:".length());
            int colon = rest.indexOf(':');
            if (colon > 0) {
                return rest.substring(0, colon) + " on " + label(rest.substring(colon + 1), clusterName, nodeNames);
            }
        }
        return Arrays.stream(subject.split("/"))
                .map(part -> part(part, clusterName, nodeNames))
                .collect(Collectors.joining(" / "));
    }

    private static String part(String part, String clusterName, Map<String, String> nodeNames) {
        if (part.equals("cluster")) {
            return clusterName != null ? "cluster " + clusterName : "the cluster";
        }
        if (part.equals("rahdar")) {
            return "Rahdar host";
        }
        if (part.startsWith("node:")) {
            String name = nodeNames.get(part.substring("node:".length()));
            return name != null ? "node " + name : part;
        }
        if (part.startsWith("subscription:")) {
            return "subscription " + part.substring("subscription:".length());
        }
        return part;
    }
}
