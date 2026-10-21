package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Structural validation of a declaration — everything that can be decided without
 * reading a broker. It is strict on purpose: the broker accepts a key it does not
 * know and silently does nothing with it ({@code docs/broker-management-notes.md} §15
 * M1), so a misspelt key that reaches a broker is a change that never happens and
 * nobody is told about.
 *
 * <p>Checks that need the cluster's observed state — a divert's forwarding address,
 * a page size against the merged maximum — live in {@link BrokerConfigPlanner}.
 */
public final class BrokerConfigValidator {

    private static final String ANYCAST = "ANYCAST";
    private static final String MULTICAST = "MULTICAST";
    private static final String NAME = ".name";
    private static final String DECLARED_TWICE = "' is declared twice.";
    private static final String ROUTING_TYPE_PATH = ".routingType";
    private static final String FILTER_PATH = ".filter";
    private static final String FORWARDING_ADDRESS_PATH = ".forwardingAddress";
    private static final String STATIC_CONNECTORS_PATH = ".staticConnectors";
    private static final String MATCH_PATH = ".match";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");
    private static final Set<String> ROUTING_TYPES = Set.of(ANYCAST, MULTICAST);
    private static final Set<String> DIVERT_ROUTING_TYPES = Set.of("STRIP", "PASS", ANYCAST, MULTICAST);
    /** {@code ComponentConfigurationRoutingType} as a bridge accepts it; the broker's default is {@code PASS}. */
    private static final Set<String> BRIDGE_ROUTING_TYPES = Set.of("STRIP", "PASS", ANYCAST, MULTICAST, "OFFSET");
    /** A name that survives being put in a JMX object name; the same rule a queue's name is held to. */
    private static final Pattern BRIDGE_NAME = Pattern.compile(LifecycleRequests.MANAGEMENT_NAME);
    /** Keys whose numeric value has {@code -1} as "unlimited"; anything lower is a typo. */
    private static final Set<AddressSettingKey> AT_LEAST_MINUS_ONE = Set.of(
            AddressSettingKey.MAX_SIZE_BYTES,
            AddressSettingKey.MAX_SIZE_MESSAGES,
            AddressSettingKey.PAGE_LIMIT_BYTES,
            AddressSettingKey.PAGE_LIMIT_MESSAGES,
            AddressSettingKey.MAX_DELIVERY_ATTEMPTS,
            AddressSettingKey.EXPIRY_DELAY,
            AddressSettingKey.MIN_EXPIRY_DELAY,
            AddressSettingKey.MAX_EXPIRY_DELAY,
            AddressSettingKey.REDISTRIBUTION_DELAY,
            AddressSettingKey.SLOW_CONSUMER_THRESHOLD,
            AddressSettingKey.DEFAULT_RING_SIZE,
            AddressSettingKey.DEFAULT_MAX_CONSUMERS,
            AddressSettingKey.MAX_REDELIVERY_DELAY,
            AddressSettingKey.MAX_READ_PAGE_BYTES,
            AddressSettingKey.MAX_READ_PAGE_MESSAGES,
            AddressSettingKey.PREFETCH_PAGE_BYTES,
            AddressSettingKey.PREFETCH_PAGE_MESSAGES,
            // -1 is how the broker's own documentation disables the management
            // truncation cap, and it is the value the capability snippet has always
            // told operators to paste. Refusing it here made the one setting Rahdar
            // recommends for whole message bodies undeclarable.
            AddressSettingKey.MANAGEMENT_MESSAGE_ATTRIBUTE_SIZE_LIMIT);

    private BrokerConfigValidator() {}

    public static List<Violation> validate(BrokerConfigDocument doc) {
        List<Violation> out = new ArrayList<>();
        addresses(doc, out);
        addressSettings(doc, out);
        securitySettings(doc, out);
        diverts(doc, out);
        bridges(doc, out);
        return List.copyOf(out);
    }

    // ---- addresses and queues -------------------------------------------

    private static void addresses(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> addressNames = new HashSet<>();
        Set<String> queueNames = new HashSet<>();
        for (int i = 0; i < doc.addresses().size(); i++) {
            address(doc.addresses().get(i), "addresses[" + i + "]", addressNames, queueNames, out);
        }
    }

    private static void address(
            AddressDecl a, String p, Set<String> addressNames, Set<String> queueNames, List<Violation> out) {
        if (blank(a.name())) {
            out.add(new Violation(p + NAME, "An address needs a name."));
        } else if (!addressNames.add(a.name())) {
            out.add(new Violation(p + NAME, "Address '" + a.name() + DECLARED_TWICE));
        }
        placeholder(a.name(), p + NAME, out);
        if (a.routingTypes().isEmpty()) {
            out.add(new Violation(p + ".routingTypes", "An address needs at least one routing type."));
        }
        for (String rt : a.routingTypes()) {
            if (!ROUTING_TYPES.contains(rt)) {
                out.add(new Violation(
                        p + ".routingTypes", "Routing type must be ANYCAST or MULTICAST, not '" + rt + "'."));
            }
        }
        for (int j = 0; j < a.queues().size(); j++) {
            queue(a, a.queues().get(j), p + ".queues[" + j + "]", queueNames, out);
        }
    }

    private static void queue(AddressDecl a, QueueDecl q, String qp, Set<String> queueNames, List<Violation> out) {
        if (blank(q.name())) {
            out.add(new Violation(qp + NAME, "A queue needs a name."));
        } else if (!queueNames.add(q.name())) {
            out.add(new Violation(
                    qp + NAME, "Queue '" + q.name() + "' is declared twice; queue names are unique per broker."));
        }
        if (blank(q.routingType()) || !ROUTING_TYPES.contains(q.routingType().toUpperCase(Locale.ROOT))) {
            out.add(new Violation(qp + ROUTING_TYPE_PATH, "A queue's routing type must be ANYCAST or MULTICAST."));
        } else if (!a.routingTypes().isEmpty()
                && !a.routingTypes().contains(q.routingType().toUpperCase(Locale.ROOT))) {
            out.add(new Violation(
                    qp + ROUTING_TYPE_PATH,
                    "Queue '" + q.name() + "' is " + q.routingType().toUpperCase(Locale.ROOT)
                            + " but its address only routes " + String.join(", ", a.routingTypes()) + "."));
        }
        if (q.maxConsumers() != null && q.maxConsumers() < -1) {
            out.add(new Violation(qp + ".maxConsumers", "Max consumers is -1 for unlimited or a count."));
        }
        if (q.ringSize() != null && q.ringSize() < -1) {
            out.add(new Violation(qp + ".ringSize", "Ring size is -1 for unlimited or a count."));
        }
        placeholder(q.filter(), qp + FILTER_PATH, out);
    }

    // ---- address settings ------------------------------------------------

    private static void addressSettings(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> matches = new HashSet<>();
        for (int i = 0; i < doc.addressSettings().size(); i++) {
            AddressSettingDecl s = doc.addressSettings().get(i);
            String p = "addressSettings[" + i + "]";
            if (blank(s.match())) {
                out.add(new Violation(p + MATCH_PATH, "An address setting needs a match pattern."));
            } else if (!matches.add(s.match())) {
                out.add(new Violation(p + MATCH_PATH, "Match '" + s.match() + DECLARED_TWICE));
            }
            if (s.values().isEmpty()) {
                out.add(new Violation(
                        p + ".values", "An address setting with no keys changes nothing; remove it or declare a key."));
            }
            s.values().forEach((name, value) -> settingKey(name, value, p + ".values." + name, out));
            BigDecimal max = number(s.values().get(AddressSettingKey.MAX_SIZE_BYTES.jsonName()));
            BigDecimal page = number(s.values().get(AddressSettingKey.PAGE_SIZE_BYTES.jsonName()));
            if (max != null && page != null && max.signum() >= 0 && page.compareTo(max) >= 0) {
                out.add(new Violation(
                        p + ".values.pageSizeBytes",
                        "page-size-bytes must be lower than max-size-bytes; the broker refuses the pair."));
            }
        }
    }

    private static void settingKey(String name, Object value, String path, List<Violation> out) {
        AddressSettingKey key = AddressSettingKey.byJsonName(name).orElse(null);
        if (key == null) {
            out.add(new Violation(
                    path,
                    "'" + name
                            + "' is not an address-setting key Rahdar knows. The broker would accept it"
                            + " and silently ignore it."));
        } else if (!key.applicable()) {
            out.add(new Violation(
                    path,
                    key.xmlName() + " governs what a configuration-file reload removes and is not applied at"
                            + " runtime. Declare it in broker.xml."));
        } else {
            value(key, value, path, out);
        }
    }

    private static void value(AddressSettingKey key, Object value, String path, List<Violation> out) {
        if (value == null) {
            out.add(new Violation(path, key.xmlName() + " has no value."));
            return;
        }
        if (value instanceof String s) {
            placeholder(s, path, out);
        }
        switch (key.type()) {
            case BOOLEAN -> booleanValue(key, value, path, out);
            case INT, LONG -> wholeValue(key, value, path, out);
            case DOUBLE -> decimalValue(key, value, path, out);
            case STRING -> textValue(key, value, path, out);
            case ENUM -> enumValue(key, value, path, out);
        }
    }

    private static void booleanValue(AddressSettingKey key, Object value, String path, List<Violation> out) {
        if (!(value instanceof Boolean) && !isBooleanText(value)) {
            out.add(new Violation(path, key.xmlName() + " must be true or false."));
        }
    }

    private static void wholeValue(AddressSettingKey key, Object value, String path, List<Violation> out) {
        BigDecimal n = number(value);
        if (n == null || n.scale() > 0 && n.stripTrailingZeros().scale() > 0) {
            out.add(new Violation(path, key.xmlName() + " must be a whole number."));
        } else if (AT_LEAST_MINUS_ONE.contains(key) && n.compareTo(BigDecimal.ONE.negate()) < 0) {
            out.add(new Violation(path, key.xmlName() + " is -1 for unlimited or a non-negative number."));
        } else if (!AT_LEAST_MINUS_ONE.contains(key) && n.signum() < 0) {
            out.add(new Violation(path, key.xmlName() + " cannot be negative."));
        } else if (key.type() == AddressSettingKey.Type.INT && n.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            out.add(new Violation(path, key.xmlName() + " is larger than the broker's 32-bit limit."));
        }
    }

    private static void decimalValue(AddressSettingKey key, Object value, String path, List<Violation> out) {
        BigDecimal n = number(value);
        if (n == null) {
            out.add(new Violation(path, key.xmlName() + " must be a number."));
        } else if (key == AddressSettingKey.REDELIVERY_DELAY_MULTIPLIER && n.signum() <= 0) {
            out.add(new Violation(path, "redelivery-delay-multiplier must be greater than zero."));
        } else if (key == AddressSettingKey.REDELIVERY_COLLISION_AVOIDANCE_FACTOR
                && (n.signum() < 0 || n.compareTo(BigDecimal.ONE) > 0)) {
            out.add(new Violation(path, "redelivery-collision-avoidance-factor is between 0 and 1."));
        }
    }

    private static void textValue(AddressSettingKey key, Object value, String path, List<Violation> out) {
        if (!(value instanceof String)) {
            out.add(new Violation(path, key.xmlName() + " must be text."));
        }
    }

    private static void enumValue(AddressSettingKey key, Object value, String path, List<Violation> out) {
        String text = value.toString().toUpperCase(Locale.ROOT);
        if (!key.allowedValues().contains(text)) {
            out.add(new Violation(
                    path, key.xmlName() + " must be one of " + String.join(", ", key.allowedValues()) + "."));
        }
    }

    // ---- security settings ---------------------------------------------

    private static void securitySettings(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> matches = new HashSet<>();
        for (int i = 0; i < doc.securitySettings().size(); i++) {
            SecuritySettingDecl s = doc.securitySettings().get(i);
            String p = "securitySettings[" + i + "]";
            if (blank(s.match())) {
                out.add(new Violation(p + MATCH_PATH, "A security setting needs a match pattern."));
            } else if (!matches.add(s.match())) {
                out.add(new Violation(p + MATCH_PATH, "Match '" + s.match() + DECLARED_TWICE));
            }
            if (s.permissions().isEmpty()) {
                out.add(new Violation(
                        p + ".permissions",
                        "A security setting with no permissions denies everything under its match. Declare at least"
                                + " one role, or remove it."));
            }
            s.permissions().forEach((type, roles) -> roles(roles, p + ".permissions." + type.xmlName(), out));
        }
    }

    private static void roles(Set<String> roles, String path, List<Violation> out) {
        for (String role : roles) {
            if (blank(role) || role.contains(",")) {
                out.add(new Violation(path, "Role names are single words without commas; '" + role + "' is not."));
            }
            placeholder(role, path, out);
        }
    }

    // ---- diverts ---------------------------------------------------------

    private static void diverts(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < doc.diverts().size(); i++) {
            DivertDecl d = doc.diverts().get(i);
            String p = "diverts[" + i + "]";
            if (blank(d.name())) {
                out.add(new Violation(p + NAME, "A divert needs a name."));
            } else if (!names.add(d.name())) {
                out.add(new Violation(p + NAME, "Divert '" + d.name() + DECLARED_TWICE));
            }
            if (blank(d.address())) {
                out.add(new Violation(p + ".address", "A divert needs the address it reads from."));
            }
            if (blank(d.forwardingAddress())) {
                out.add(new Violation(p + FORWARDING_ADDRESS_PATH, "A divert needs the address it forwards to."));
            } else if (d.forwardingAddress().equals(d.address())) {
                out.add(new Violation(
                        p + FORWARDING_ADDRESS_PATH, "A divert cannot forward to the address it reads from."));
            }
            if (d.routingType() != null && !DIVERT_ROUTING_TYPES.contains(d.routingType())) {
                out.add(new Violation(
                        p + ROUTING_TYPE_PATH, "A divert's routing type is STRIP, PASS, ANYCAST or MULTICAST."));
            }
            placeholder(d.filter(), p + FILTER_PATH, out);
            placeholder(d.forwardingAddress(), p + FORWARDING_ADDRESS_PATH, out);
        }
    }

    // ---- bridges ---------------------------------------------------------

    /**
     * A bridge's structural rules (ADR-0091). The broker accepts a document with an
     * unknown key, answers 200 and deploys nothing, so everything that can be decided
     * here is decided here rather than discovered as a silent no-op on a node.
     */
    private static void bridges(BrokerConfigDocument doc, List<Violation> out) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < doc.bridges().size(); i++) {
            bridge(doc.bridges().get(i), "bridges[" + i + "]", names, out);
        }
    }

    private static void bridge(BridgeDecl b, String p, Set<String> names, List<Violation> out) {
        if (blank(b.name())) {
            out.add(new Violation(p + NAME, "A bridge needs a name."));
        } else if (!names.add(b.name())) {
            out.add(new Violation(p + NAME, "Bridge '" + b.name() + DECLARED_TWICE));
        } else if (!BRIDGE_NAME.matcher(b.name()).matches()) {
            out.add(new Violation(
                    p + NAME,
                    "A bridge's name cannot contain whitespace or any of , = : * ? \" \\ — the broker puts it in"
                            + " an object name."));
        }
        if (blank(b.queueName())) {
            out.add(new Violation(p + ".queueName", "A bridge needs the queue it reads from."));
        }
        if (blank(b.forwardingAddress())) {
            out.add(new Violation(p + FORWARDING_ADDRESS_PATH, "A bridge needs the address it forwards to."));
        }
        bridgeConnection(b, p, out);
        if (b.routingType() != null && !BRIDGE_ROUTING_TYPES.contains(b.routingType())) {
            out.add(new Violation(
                    p + ROUTING_TYPE_PATH,
                    "A bridge's routing type is one of " + String.join(", ", new TreeSet<>(BRIDGE_ROUTING_TYPES))
                            + "."));
        }
        bridgeLimits(b, p, out);
        if (b.transformer() != null && blank(b.transformer().className())) {
            out.add(new Violation(
                    p + ".transformer.className",
                    "A transformer needs its class name; properties on their own configure nothing."));
        }
        placeholder(b.filter(), p + FILTER_PATH, out);
        placeholder(b.queueName(), p + ".queueName", out);
        placeholder(b.forwardingAddress(), p + FORWARDING_ADDRESS_PATH, out);
        b.staticConnectors().forEach(c -> placeholder(c, p + STATIC_CONNECTORS_PATH, out));
    }

    private static void bridgeConnection(BridgeDecl b, String p, List<Violation> out) {
        boolean hasConnectors = !b.staticConnectors().isEmpty();
        boolean hasDiscovery = !blank(b.discoveryGroupName());
        if (hasConnectors && hasDiscovery) {
            out.add(new Violation(
                    p + STATIC_CONNECTORS_PATH,
                    "A bridge uses either static connectors or a discovery group, never both; the broker accepts"
                            + " only one. Remove one of them."));
        } else if (!hasConnectors && !hasDiscovery) {
            out.add(new Violation(
                    p + STATIC_CONNECTORS_PATH,
                    "A bridge needs somewhere to connect: name at least one static connector, or a discovery"
                            + " group."));
        }
    }

    private static void bridgeLimits(BridgeDecl b, String p, List<Violation> out) {
        atLeast(b.retryInterval(), 1, p + ".retryInterval", "retry-interval is at least 1 millisecond.", out);
        atLeast(b.maxRetryInterval(), 1, p + ".maxRetryInterval", "max-retry-interval is at least 1 millisecond.", out);
        atLeast(b.checkPeriod(), 1, p + ".checkPeriod", "check-period is at least 1 millisecond.", out);
        atLeast(b.connectionTtl(), -1, p + ".connectionTtl", "connection-ttl is -1 for never, or a duration.", out);
        atLeast(
                b.confirmationWindowSize(),
                -1,
                p + ".confirmationWindowSize",
                "confirmation-window-size is -1 to disable, or a size in bytes.",
                out);
        atLeast(
                b.producerWindowSize(),
                -1,
                p + ".producerWindowSize",
                "producer-window-size is -1 for unlimited, or a size in bytes.",
                out);
        atLeast(
                b.minLargeMessageSize(),
                1,
                p + ".minLargeMessageSize",
                "min-large-message-size is at least 1 byte.",
                out);
        atLeast(
                b.initialConnectAttempts(),
                -1,
                p + ".initialConnectAttempts",
                "initial-connect-attempts is -1 to retry forever, or a count.",
                out);
        atLeast(
                b.reconnectAttempts(),
                -1,
                p + ".reconnectAttempts",
                "reconnect-attempts is -1 to retry forever, or a count.",
                out);
        atLeast(b.concurrency(), 1, p + ".concurrency", "concurrency is at least 1 worker.", out);
        if (b.retryIntervalMultiplier() != null && b.retryIntervalMultiplier() <= 0) {
            out.add(new Violation(
                    p + ".retryIntervalMultiplier", "retry-interval-multiplier must be greater than zero."));
        }
    }

    private static void atLeast(Number value, long floor, String path, String message, List<Violation> out) {
        if (value != null && value.longValue() < floor) {
            out.add(new Violation(path, message));
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static void placeholder(String value, String path, List<Violation> out) {
        if (value != null && PLACEHOLDER.matcher(value).find()) {
            out.add(new Violation(
                    path, "'" + value + "' is a placeholder. Rahdar cannot resolve it; enter the concrete value."));
        }
    }

    static BigDecimal number(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal b -> b;
            case Number n -> new BigDecimal(n.toString());
            case String s -> {
                try {
                    yield new BigDecimal(s.trim());
                } catch (NumberFormatException _) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static boolean isBooleanText(Object value) {
        return value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
