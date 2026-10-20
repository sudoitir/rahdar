package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.ArtemisLists;
import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Bridge management over Jolokia, mirroring {@link DivertOperations} (ADR-0091).
 *
 * <p>Reading is two round trips per node and cannot be fewer: the bridge MBeans are
 * found by a JMX search and then read in one batched POST.
 *
 * <p>Mutations use {@code createBridge(java.lang.String)}, whose document carries
 * {@code broker.xml}'s hyphenated key names and a <em>nested</em>
 * {@code "transformer-configuration": {"class-name": …, "properties": {…}}}. Three
 * other transformer shapes were measured and all three deployed the bridge with the
 * transformer silently dropped, returning 200 — as does a document with any unknown
 * key, which creates nothing at all. So the read-back below is mandatory, not
 * defensive.
 *
 * <p>There is no {@code updateBridge}: a change is a destroy and a create, which is
 * what the plan shows and names as a hazard.
 */
@Component
public class BridgeOperations {

    private static final String STATIC_CONNECTORS = "static-connectors";

    private final ObjectMapper mapper;

    public BridgeOperations(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- reads -----------------------------------------------------------

    /** Every bridge deployed on this node, with its started and connected state. */
    public List<BridgeRow> listBridges(ArtemisNode client, UUID nodeId, String nodeName) {
        String broker = client.resolveBrokerObjectName();
        return ArtemisLists.readAll(
                client.jolokia(),
                client.search(ArtemisMBeans.bridgesPattern(broker)),
                a -> BridgeRow.parse(a, nodeId, nodeName));
    }

    /**
     * The MBean names a declared bridge deploys as. A concurrency above one deploys
     * {@code <name>-0 … <name>-(N-1)}, each with its own {@code BridgeControl}; one or
     * unset deploys the bare name. Without this a healthy concurrent bridge reads back
     * as never deployed.
     */
    public static List<String> instanceNames(String name, int concurrency) {
        if (concurrency <= 1) {
            return List.of(name);
        }
        List<String> out = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            out.add(name + "-" + i);
        }
        return List.copyOf(out);
    }

    /** {@link #instanceNames} for a configuration document, whose {@code concurrency} may be unset. */
    public static List<String> instanceNames(Map<String, Object> config) {
        Object concurrency = config.get("concurrency");
        int workers = concurrency instanceof Number n ? n.intValue() : 1;
        return instanceNames(String.valueOf(config.get("name")), workers);
    }

    /** The deployed instances of one declared bridge, keyed by MBean name; absent names are simply missing. */
    public Map<String, BridgeRow> instancesOf(ArtemisNode client, List<String> names) {
        Map<String, BridgeRow> out = new LinkedHashMap<>();
        for (BridgeRow row : listBridges(client, null, null)) {
            if (names.contains(row.name())) {
                out.put(row.name(), row);
            }
        }
        return out;
    }

    // ---- mutations -------------------------------------------------------

    /** Create a bridge from a hyphenated {@code BridgeConfiguration} document. */
    public void createBridge(ArtemisNode client, String brokerMbean, Map<String, Object> config) {
        JolokiaResponse res = client.single(
                JolokiaRequest.exec(brokerMbean, "createBridge(java.lang.String)", mapper.writeValueAsString(config)));
        require(res, "createBridge");
    }

    /**
     * Destroy a bridge by its <em>declared</em> name, which removes every instance a
     * concurrency above one deployed. A bridge that is already gone answers 200 and
     * does nothing.
     */
    public void destroyBridge(ArtemisNode client, String brokerMbean, String name) {
        require(
                client.single(JolokiaRequest.exec(brokerMbean, "destroyBridge(java.lang.String)", name)),
                "destroyBridge");
    }

    /**
     * Create a bridge and report what the broker actually deployed.
     *
     * @return {@code ALREADY} when an identical bridge was already there, {@code APPLIED}
     *     when it is now deployed as asked
     * @throws ManagementRefusal naming the differing fields, or saying it was not
     *     deployed — and naming the transformer class when one was declared, because a
     *     class the node cannot load is the usual cause
     */
    public NodeStatus createVerified(ArtemisNode client, String brokerMbean, Map<String, Object> config) {
        String name = String.valueOf(config.get("name"));
        List<String> expected = instanceNames(config);
        Map<String, BridgeRow> before = instancesOf(client, expected);
        if (before.size() == expected.size()) {
            requireSame(name, before.values(), config);
            return NodeStatus.ALREADY;
        }
        try {
            createBridge(client, brokerMbean, config);
        } catch (ManagementRefusal e) {
            if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                throw e;
            }
        }
        Map<String, BridgeRow> after = instancesOf(client, expected);
        if (after.size() != expected.size()) {
            throw new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, notDeployed(name, expected, after, config));
        }
        requireSame(name, after.values(), config);
        return NodeStatus.APPLIED;
    }

    private static String notDeployed(
            String name, List<String> expected, Map<String, BridgeRow> after, Map<String, Object> config) {
        String missing =
                expected.stream().filter(n -> !after.containsKey(n)).findFirst().orElse(name);
        String transformer = transformerClass(config);
        return "The broker accepted bridge '" + name + "' but did not deploy "
                + (expected.size() == 1 ? "it" : missing + " (of " + expected.size() + " workers)")
                + ". Its log says why"
                + (transformer == null
                        ? "; the usual causes are a missing queue and an unknown connector name."
                        : "; the usual cause is that the transformer class " + transformer
                                + " is not on this node's classpath.");
    }

    private static void requireSame(String name, Iterable<BridgeRow> rows, Map<String, Object> config) {
        for (BridgeRow row : rows) {
            List<String> differing = differences(row, config);
            if (!differing.isEmpty()) {
                throw new ManagementRefusal(
                        ManagementRefusal.Kind.ARGUMENT,
                        "A bridge named '" + name + "' exists here with a different " + String.join(", ", differing)
                                + ". Remove it first; the broker has no way to change a bridge in place.");
            }
        }
    }

    /**
     * The keys on which a deployed bridge differs from the requested document.
     *
     * <p>Only the thirteen attributes {@code BridgeControl} reports are compared, and
     * only where the document set them — an unset key is the broker's own default, and
     * a key the broker never reports is not claimed to agree (ADR-0090 D4a).
     */
    static List<String> differences(BridgeRow row, Map<String, Object> config) {
        List<String> differing = new ArrayList<>();
        text(differing, config, "queue-name", row.queueName());
        text(differing, config, "forwarding-address", row.forwardingAddress());
        text(differing, config, "filter-string", row.filterString());
        text(differing, config, "discovery-group-name", row.discoveryGroupName());
        if (config.containsKey(STATIC_CONNECTORS)) {
            List<?> wanted = (List<?>) config.get(STATIC_CONNECTORS);
            if (!row.staticConnectors().equals(wanted)) {
                differing.add(STATIC_CONNECTORS);
            }
        }
        flag(differing, config, "ha", row.highlyAvailable());
        flag(differing, config, "use-duplicate-detection", row.useDuplicateDetection());
        number(differing, config, "retry-interval", row.retryInterval());
        number(differing, config, "retry-interval-multiplier", row.retryIntervalMultiplier());
        number(differing, config, "max-retry-interval", row.maxRetryInterval());
        number(differing, config, "reconnect-attempts", row.reconnectAttempts());
        transformerDifferences(differing, config, row.transformerClassName(), row.transformerProperties());
        return differing;
    }

    /**
     * The nested {@code transformer-configuration} against what a deployed bridge or
     * divert reports. Shared because the broker drops a malformed transformer on both
     * with the same 200, so both read-backs must name it (ADR-0091).
     */
    static void transformerDifferences(
            List<String> differing, Map<String, Object> config, String className, Map<String, String> properties) {
        if (config.containsKey("transformer-configuration")) {
            if (!Objects.equals(transformerClass(config), className)) {
                differing.add("transformer class-name");
            } else if (!transformerProperties(config).equals(properties)) {
                differing.add("transformer properties");
            }
        } else if (className != null) {
            differing.add("transformer class-name");
        }
    }

    private static void text(List<String> differing, Map<String, Object> config, String key, String actual) {
        if (!config.containsKey(key)) {
            return;
        }
        String wanted = config.get(key) == null ? null : String.valueOf(config.get(key));
        String a = actual == null || actual.isBlank() ? null : actual;
        String w = wanted == null || wanted.isBlank() ? null : wanted;
        if (!Objects.equals(a, w)) {
            differing.add(key);
        }
    }

    private static void flag(List<String> differing, Map<String, Object> config, String key, boolean actual) {
        if (config.containsKey(key) && !Objects.equals(config.get(key), actual)) {
            differing.add(key);
        }
    }

    private static void number(List<String> differing, Map<String, Object> config, String key, Number actual) {
        if (config.get(key) instanceof Number wanted && wanted.doubleValue() != actual.doubleValue()) {
            differing.add(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> transformerConfiguration(Map<String, Object> config) {
        Object t = config.get("transformer-configuration");
        return t instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String transformerClass(Map<String, Object> config) {
        Object className = transformerConfiguration(config).get("class-name");
        return className == null ? null : String.valueOf(className);
    }

    private static Map<?, ?> transformerProperties(Map<String, Object> config) {
        Object properties = transformerConfiguration(config).get("properties");
        return properties instanceof Map<?, ?> m ? m : Map.of();
    }

    private static void require(JolokiaResponse res, String operation) {
        ArtemisNode.require(res, operation);
    }
}
