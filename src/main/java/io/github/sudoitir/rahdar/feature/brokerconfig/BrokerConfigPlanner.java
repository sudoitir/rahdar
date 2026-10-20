package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.ObservedNodeConfig.AddressUsage;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Finding;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.FindingKind;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Hazard;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.HazardKind;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.NodePlan;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Op;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Section;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Step;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a declaration and one observed read per node into the ordered steps an apply
 * would take, the hazards those steps carry, and the findings nobody should act on
 * (ADR-0067 D3–D7). Pure: no broker, no database, no clock.
 *
 * <p>The same computation serves drift: an {@code ADD} step that nobody applies is a
 * missing resource, a {@code REPLACE} step is a divergent one. The drift service reads
 * the steps; it does not re-derive them.
 *
 * <p>Order within a node is dependency order — addresses, queues, address settings,
 * security settings, diverts, bridges — and removals come last in reverse, so a divert
 * never points at an address that does not yet exist, a bridge never reads from a queue
 * that does not yet exist, and a setting is never removed from under a resource that
 * still needs it.
 */
public final class BrokerConfigPlanner {

    /** The management and notification addresses a security match must not cover carelessly. */
    private static final List<String> PROTECTED_ADDRESSES = List.of("activemq.management", "activemq.notifications");

    private static final String DEFAULT_MATCH = "#";

    private static final String ADDRESS = "address";
    private static final String ROUTING_TYPE = "routing-type";
    private static final String FILTER_STRING = "filter-string";
    private static final String AUTO_CREATE_ADDRESS = "auto-create-address";
    private static final String ROUTING_TYPES = "routingTypes";
    private static final String EXISTS_AS_DECLARED = " exists as declared";
    private static final String BRIDGE_LABEL = "Bridge ";
    private static final String DIVERT_LABEL = "Divert ";
    private static final String REMOVE_BRIDGE = "Remove bridge ";
    private static final String REMOVE_DIVERT = "Remove divert ";
    private static final String ADDRESS_LABEL = "Address ";
    private static final String MATCH_LABEL = "Match '";
    private static final String NO_LONGER_DECLARED = " (Rahdar applied it; it is no longer declared)";
    private static final String NOT_DECLARED = "Exists and is not declared.";

    private BrokerConfigPlanner() {}

    public static Plan plan(
            BrokerConfigDocument doc, List<ObservedNodeConfig> observed, Set<OwnedItem> owned, PlanOptions options) {
        List<Violation> violations = new ArrayList<>(BrokerConfigValidator.validate(doc));
        List<ObservedNodeConfig> readable =
                observed.stream().filter(ObservedNodeConfig::readable).toList();
        referential(doc, readable, violations);
        if (!violations.isEmpty()) {
            return new Plan(List.of(), List.of(), List.of(), violations, "", 0, null);
        }

        List<NodePlan> nodes = new ArrayList<>();
        List<Hazard> hazards = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        observed.stream()
                .sorted(Comparator.comparing(ObservedNodeConfig::nodeName, Comparator.nullsLast(String::compareTo)))
                .forEach(node -> planNode(node, doc, owned, options, nodes, hazards, findings));

        UUID canary = canary(nodes, options.canaryNodeId());
        int stepCount = (int) nodes.stream().mapToLong(NodePlan::pendingSteps).sum();
        return new Plan(nodes, hazards, findings, List.of(), hash(nodes), stepCount, canary);
    }

    private static void planNode(
            ObservedNodeConfig node,
            BrokerConfigDocument doc,
            Set<OwnedItem> owned,
            PlanOptions options,
            List<NodePlan> nodes,
            List<Hazard> hazards,
            List<Finding> findings) {
        if (!node.live()) {
            nodes.add(new NodePlan(node.nodeId(), node.nodeName(), false, null, List.of()));
            findings.add(new Finding(
                    FindingKind.NOT_EVALUATED,
                    node.nodeId(),
                    node.nodeName(),
                    null,
                    null,
                    "Not live. A backup inherits what its primary holds once it becomes active."));
            return;
        }
        Set<UUID> targeted = options.nodeIds();
        if (!targeted.isEmpty() && !targeted.contains(node.nodeId())) {
            return;
        }
        if (node.unavailableReason() != null) {
            nodes.add(new NodePlan(node.nodeId(), node.nodeName(), true, node.unavailableReason(), List.of()));
            findings.add(new Finding(
                    FindingKind.UNREACHABLE, node.nodeId(), node.nodeName(), null, null, node.unavailableReason()));
            return;
        }
        List<Step> steps = new ArrayList<>();
        NodeContext ctx = new NodeContext(node, doc, owned, options, steps, hazards, findings);
        ctx.addresses();
        ctx.queues();
        ctx.addressSettings();
        ctx.securitySettings();
        ctx.diverts();
        ctx.bridges();
        ctx.removals();
        ctx.undeclared();
        nodes.add(new NodePlan(node.nodeId(), node.nodeName(), true, null, steps));
    }

    /**
     * The same plan, narrowed to the steps an operator asked for by identifier
     * (ADR-0087 D2).
     *
     * <p>A step identifier is {@code SECTION:key:OP}, so it names one declared item on
     * every node at once — which is what a row's "Apply this" means. Identifiers that
     * match nothing are ignored; an empty set is the whole plan.
     *
     * <p>Hazards of items that are no longer in the run go with them: a hazard is a
     * consequence of a step, and asking an operator to acknowledge one for a step that
     * will not run trains them to acknowledge without reading. Findings stay — they are
     * what the plan noticed and is not acting on either way — and so does the canary,
     * because the node order is unchanged. The hash is recomputed over what is left, so
     * the confirmation covers exactly the run.
     */
    public static Plan restrict(Plan plan, Set<String> stepIds) {
        if (stepIds.isEmpty()) {
            return plan;
        }
        List<NodePlan> nodes = plan.nodes().stream()
                .map(n -> new NodePlan(
                        n.nodeId(),
                        n.nodeName(),
                        n.live(),
                        n.unavailableReason(),
                        n.steps().stream().filter(s -> stepIds.contains(s.id())).toList()))
                .toList();
        Set<String> items = nodes.stream()
                .flatMap(n -> n.steps().stream())
                .map(s -> s.section() + ":" + s.key())
                .collect(Collectors.toSet());
        List<Hazard> hazards = plan.hazards().stream()
                .filter(h -> items.contains(h.section() + ":" + h.key()))
                .toList();
        int stepCount = (int) nodes.stream().mapToLong(NodePlan::pendingSteps).sum();
        return new Plan(
                nodes, hazards, plan.findings(), plan.violations(), hash(nodes), stepCount, plan.canaryNodeId());
    }

    // ---- referential checks that need the observed cluster ------------------

    private static void referential(BrokerConfigDocument doc, List<ObservedNodeConfig> nodes, List<Violation> out) {
        Set<String> declaredAddresses = new HashSet<>();
        Set<String> declaredAddressesWithQueues = new HashSet<>();
        for (AddressDecl a : doc.addresses()) {
            declaredAddresses.add(a.name());
            if (!a.queues().isEmpty()) {
                declaredAddressesWithQueues.add(a.name());
            }
        }
        for (int i = 0; i < doc.addressSettings().size(); i++) {
            AddressSettingDecl s = doc.addressSettings().get(i);
            String p = "addressSettings[" + i + "].values.";
            check(DEAD_LETTER, s, p, declaredAddresses, nodes, out);
            check(EXPIRY, s, p, declaredAddresses, nodes, out);
            pageSizeAgainstMerged(s, nodes, "addressSettings[" + i + "].values.", out);
        }
        for (int i = 0; i < doc.diverts().size(); i++) {
            DivertDecl d = doc.diverts().get(i);
            if (d.forwardingAddress() == null) {
                continue;
            }
            boolean declared = declaredAddressesWithQueues.contains(d.forwardingAddress());
            boolean observedEverywhere = !nodes.isEmpty()
                    && nodes.stream()
                            .allMatch(n -> n.queues().values().stream()
                                    .anyMatch(q -> d.forwardingAddress().equals(q.get(ADDRESS))));
            if (!declared && !observedEverywhere) {
                out.add(new Violation(
                        "diverts[" + i + "].forwardingAddress",
                        "Nothing consumes at '" + d.forwardingAddress()
                                + "': declare a queue on it or it must exist with a queue on every live node."
                                + " Messages diverted to an address without a queue are dropped."));
            }
        }
    }

    /** An address setting that names another address, the setting that lets the broker create it, and what to call it. */
    private record AddressReference(AddressSettingKey key, AddressSettingKey autoCreateKey, String what) {}

    private static final AddressReference DEAD_LETTER = new AddressReference(
            AddressSettingKey.DEAD_LETTER_ADDRESS,
            AddressSettingKey.AUTO_CREATE_DEAD_LETTER_RESOURCES,
            "dead-letter address");
    private static final AddressReference EXPIRY = new AddressReference(
            AddressSettingKey.EXPIRY_ADDRESS, AddressSettingKey.AUTO_CREATE_EXPIRY_RESOURCES, "expiry address");

    private static void check(
            AddressReference reference,
            AddressSettingDecl s,
            String pathPrefix,
            Set<String> declaredAddresses,
            List<ObservedNodeConfig> nodes,
            List<Violation> out) {
        AddressSettingKey autoCreateKey = reference.autoCreateKey();
        Object target = s.values().get(reference.key().jsonName());
        if (!(target instanceof String address) || address.isBlank()) {
            return;
        }
        if (declaredAddresses.contains(address)) {
            return;
        }
        Object autoCreate = s.values().get(autoCreateKey.jsonName());
        if (autoCreate != null && Boolean.parseBoolean(autoCreate.toString())) {
            return;
        }
        boolean observedEverywhere = !nodes.isEmpty()
                && nodes.stream()
                        .allMatch(n -> n.addresses().containsKey(address)
                                || Boolean.TRUE.equals(Values.normalise(
                                        autoCreateKey,
                                        n.addressSettings()
                                                .getOrDefault(s.match(), Map.of())
                                                .get(autoCreateKey.jsonName()))));
        if (!observedEverywhere) {
            out.add(new Violation(
                    pathPrefix + reference.key().jsonName(),
                    "The " + reference.what() + " '" + address
                            + "' is neither declared nor present on every live node, and "
                            + autoCreateKey.xmlName() + " is off. Declare the address, or enable "
                            + autoCreateKey.xmlName() + "."));
        }
    }

    private static void pageSizeAgainstMerged(
            AddressSettingDecl s, List<ObservedNodeConfig> nodes, String pathPrefix, List<Violation> out) {
        BigDecimal declaredMax =
                BrokerConfigValidator.number(s.values().get(AddressSettingKey.MAX_SIZE_BYTES.jsonName()));
        BigDecimal declaredPage =
                BrokerConfigValidator.number(s.values().get(AddressSettingKey.PAGE_SIZE_BYTES.jsonName()));
        if ((declaredMax == null) == (declaredPage == null)) {
            return; // both declared is the validator's check; neither declared is nothing to check
        }
        for (ObservedNodeConfig n : nodes) {
            Map<String, Object> merged = n.addressSettings()
                    .getOrDefault(s.match(), n.addressSettings().getOrDefault(DEFAULT_MATCH, Map.of()));
            BigDecimal max = declaredMax != null
                    ? declaredMax
                    : BrokerConfigValidator.number(merged.get(AddressSettingKey.MAX_SIZE_BYTES.jsonName()));
            BigDecimal page = declaredPage != null
                    ? declaredPage
                    : BrokerConfigValidator.number(merged.get(AddressSettingKey.PAGE_SIZE_BYTES.jsonName()));
            if (max != null && page != null && max.signum() >= 0 && page.compareTo(max) >= 0) {
                out.add(new Violation(
                        pathPrefix + (declaredMax != null ? "maxSizeBytes" : "pageSizeBytes"),
                        "On " + n.nodeName() + " the merged page-size-bytes (" + page
                                + ") is not lower than max-size-bytes (" + max
                                + "); the broker refuses the pair. Declare both."));
                return;
            }
        }
    }

    // ---- per-node planning ---------------------------------------------------

    private record NodeContext(
            ObservedNodeConfig node,
            BrokerConfigDocument doc,
            Set<OwnedItem> owned,
            PlanOptions options,
            List<Step> steps,
            List<Hazard> hazards,
            List<Finding> findings) {

        Map<String, Object> base() {
            return node.addressSettings().getOrDefault(DEFAULT_MATCH, Map.of());
        }

        void addresses() {
            doc.addresses().forEach(this::address);
        }

        private void address(AddressDecl a) {
            Set<String> observed = node.addresses().get(a.name());
            Map<String, Object> after = Map.of(ROUTING_TYPES, List.copyOf(a.routingTypes()));
            if (observed == null) {
                steps.add(new Step(
                        id(Section.ADDRESS, a.name(), Op.ADD),
                        Op.ADD,
                        Section.ADDRESS,
                        a.name(),
                        Map.of(),
                        after,
                        false,
                        "Create address " + a.name() + " (" + String.join(", ", a.routingTypes()) + ")"));
                return;
            }
            Map<String, Object> before = Map.of(ROUTING_TYPES, List.copyOf(new TreeSet<>(observed)));
            // The declared set is authoritative (ADR-0082 D2): a superset is divergent too. A
            // removed type with queues of that type bound stays, because the broker refuses to
            // drop it (broker-management-notes §15 M8) and a refused step would halt the node.
            Map<String, List<String>> bound = boundQueuesOfRemovedTypes(a, observed);
            bound.forEach((type, queues) -> findings.add(new Finding(
                    FindingKind.DIVERGENT_ADDRESS,
                    node.nodeId(),
                    node.nodeName(),
                    Section.ADDRESS,
                    a.name(),
                    "Accepts " + type + ", which the declaration does not list, and " + String.join(", ", queues)
                            + (queues.size() == 1 ? " is" : " are") + " bound to it as " + type
                            + ". The broker refuses to remove a routing type while a queue of that type is"
                            + " bound, so an apply keeps " + type + ". Declare " + type
                            + ", or delete those queues with their own delete action and apply again.")));
            Set<String> target = new TreeSet<>(a.routingTypes());
            target.addAll(bound.keySet());
            if (observed.equals(target)) {
                if (bound.isEmpty()) {
                    steps.add(new Step(
                            id(Section.ADDRESS, a.name(), Op.ADD),
                            Op.ADD,
                            Section.ADDRESS,
                            a.name(),
                            before,
                            after,
                            true,
                            ADDRESS_LABEL + a.name() + " exists"));
                }
                return;
            }
            steps.add(new Step(
                    id(Section.ADDRESS, a.name(), Op.REPLACE),
                    Op.REPLACE,
                    Section.ADDRESS,
                    a.name(),
                    before,
                    Map.of(ROUTING_TYPES, List.copyOf(target)),
                    false,
                    "Change address " + a.name() + " routing types from " + String.join(", ", new TreeSet<>(observed))
                            + " to " + String.join(", ", target)));
            routingTypeHazards(a.name(), observed, target);
        }

        /** Each routing type the declaration drops that still has queues of that type bound on this node. */
        private Map<String, List<String>> boundQueuesOfRemovedTypes(AddressDecl a, Set<String> observed) {
            Map<String, List<String>> bound = new TreeMap<>();
            for (String removed : observed) {
                if (a.routingTypes().contains(removed)) {
                    continue;
                }
                List<String> queues = node.queues().values().stream()
                        .filter(q -> a.name().equals(q.get(ADDRESS))
                                && removed.equalsIgnoreCase(Objects.toString(q.get(ROUTING_TYPE), "")))
                        .map(q -> Objects.toString(q.get("name"), ""))
                        .sorted()
                        .toList();
                if (!queues.isEmpty()) {
                    bound.put(removed, queues);
                }
            }
            return bound;
        }

        private void routingTypeHazards(String address, Set<String> observed, Set<String> target) {
            for (String added : target) {
                if (!observed.contains(added)) {
                    hazard(
                            HazardKind.ROUTING_TYPE_CHANGE,
                            HazardClass.LOW,
                            Section.ADDRESS,
                            address,
                            added,
                            ADDRESS_LABEL + address + " starts accepting " + added + " sends.");
                }
            }
            for (String removed : new TreeSet<>(observed)) {
                if (!target.contains(removed)) {
                    hazard(
                            HazardKind.ROUTING_TYPE_CHANGE,
                            HazardClass.LOW,
                            Section.ADDRESS,
                            address,
                            removed,
                            ADDRESS_LABEL + address + " stops accepting " + removed + " sends; no " + removed
                                    + " queue is bound to it on " + node.nodeName() + ".");
                }
            }
        }

        void queues() {
            for (AddressDecl a : doc.addresses()) {
                a.queues().forEach(q -> queue(a, q));
            }
        }

        private void queue(AddressDecl a, QueueDecl q) {
            Map<String, Object> wanted = queueConfig(a.name(), q);
            Map<String, Object> existing = node.queues().get(q.name());
            if (existing == null) {
                steps.add(new Step(
                        id(Section.QUEUE, q.name(), Op.ADD),
                        Op.ADD,
                        Section.QUEUE,
                        q.name(),
                        Map.of(),
                        wanted,
                        false,
                        "Create queue " + q.name() + " on " + a.name()));
                return;
            }
            List<String> differing = wanted.entrySet().stream()
                    .filter(e -> !e.getKey().equals(AUTO_CREATE_ADDRESS))
                    .filter(e -> !Values.same(e.getValue(), existing.get(e.getKey())))
                    .map(Map.Entry::getKey)
                    .toList();
            List<String> immutable = differing.stream()
                    .filter(QueueLifecycleOperations.IMMUTABLE_ON_UPDATE::contains)
                    .toList();
            if (!immutable.isEmpty()) {
                findings.add(new Finding(
                        FindingKind.DIVERGENT_QUEUE,
                        node.nodeId(),
                        node.nodeName(),
                        Section.QUEUE,
                        q.name(),
                        "Exists with a different " + String.join(", ", immutable)
                                + ", which the broker cannot change on a live queue. Changing it means"
                                + " deleting the queue with its own delete action and applying again;"
                                + " until then an apply leaves this queue as it is."));
            } else if (differing.isEmpty()) {
                steps.add(new Step(
                        id(Section.QUEUE, q.name(), Op.ADD),
                        Op.ADD,
                        Section.QUEUE,
                        q.name(),
                        existing,
                        wanted,
                        true,
                        "Queue " + q.name() + EXISTS_AS_DECLARED));
            } else {
                // Only declared keys are compared, shown and sent; the rest keep their live
                // values through the read-merge update (ADR-0082 D1), so neither side of
                // the step lists them.
                Map<String, Object> after = new LinkedHashMap<>(wanted);
                after.remove(AUTO_CREATE_ADDRESS);
                Map<String, Object> before = new LinkedHashMap<>();
                after.keySet().stream().filter(existing::containsKey).forEach(k -> before.put(k, existing.get(k)));
                steps.add(new Step(
                        id(Section.QUEUE, q.name(), Op.REPLACE),
                        Op.REPLACE,
                        Section.QUEUE,
                        q.name(),
                        before,
                        after,
                        false,
                        "Update queue " + q.name() + ": "
                                + differing.stream()
                                        .map(k -> k + " " + orUnset(existing.get(k)) + " → " + wanted.get(k))
                                        .collect(Collectors.joining(", "))));
            }
        }

        void addressSettings() {
            Map<String, Object> base = base();
            for (AddressSettingDecl s : doc.addressSettings()) {
                Map<String, Object> declared = Values.normalise(s.values());
                Map<String, Object> before =
                        Values.sorted(node.addressSettings().getOrDefault(s.match(), Map.of()));
                boolean already =
                        declared.entrySet().stream().allMatch(e -> Values.same(e.getValue(), before.get(e.getKey())));
                boolean explicitEntry = !before.isEmpty() && !sameMap(before, base);
                Map<String, Object> after = new TreeMap<>(base);
                after.putAll(declared);
                Op op = explicitEntry ? Op.REPLACE : Op.ADD;
                String verb = op == Op.ADD ? "Add" : "Replace";
                String keys = declared.size() == 1 ? " key" : " keys";
                String desc = already
                        ? "Address setting " + s.match() + " already matches"
                        : verb + " address setting " + s.match() + " (" + declared.size() + keys + ")";
                steps.add(new Step(
                        id(Section.ADDRESS_SETTING, s.match(), op),
                        op,
                        Section.ADDRESS_SETTING,
                        s.match(),
                        before,
                        after,
                        already,
                        desc));
                if (!already) {
                    addressSettingHazards(s, declared, before, base);
                }
            }
        }

        private void addressSettingHazards(
                AddressSettingDecl s,
                Map<String, Object> declared,
                Map<String, Object> before,
                Map<String, Object> base) {
            String match = s.match();
            List<String> covered = node.addresses().keySet().stream()
                    .filter(a -> AddressMatch.covers(match, a))
                    .sorted()
                    .toList();
            HazardClass reach = covered.isEmpty() ? HazardClass.MEDIUM : HazardClass.HIGH;

            if (AddressMatch.isCatchAll(match)) {
                hazard(
                        HazardKind.BROAD_MATCH,
                        HazardClass.HIGH,
                        Section.ADDRESS_SETTING,
                        match,
                        null,
                        MATCH_LABEL + match + "' is the catch-all: this replaces the default every address inherits.");
            }
            revertedKeyHazards(match, declared, before, base);
            fullPolicyHazards(match, declared, before, covered, reach);
            limitBelowUsage(match, declared, AddressSettingKey.MAX_SIZE_BYTES, covered, true);
            limitBelowUsage(match, declared, AddressSettingKey.PAGE_LIMIT_BYTES, covered, true);
            limitBelowUsage(match, declared, AddressSettingKey.MAX_SIZE_MESSAGES, covered, false);
            limitBelowUsage(match, declared, AddressSettingKey.PAGE_LIMIT_MESSAGES, covered, false);
            redirectHazards(match, declared, before, covered);
            redistributionHazard(match, declared, before);
        }

        /**
         * Replace semantics (M2): every key set on this match that the declaration does not
         * set falls back to what the parent hierarchy gives.
         */
        private void revertedKeyHazards(
                String match, Map<String, Object> declared, Map<String, Object> before, Map<String, Object> base) {
            for (Map.Entry<String, Object> e : before.entrySet()) {
                Object inherited = base.get(e.getKey());
                if (!declared.containsKey(e.getKey()) && !Values.same(e.getValue(), inherited)) {
                    hazard(
                            HazardKind.UNINTENDED_KEY_CHANGE,
                            HazardClass.HIGH,
                            Section.ADDRESS_SETTING,
                            match,
                            e.getKey(),
                            xml(e.getKey()) + " is " + e.getValue()
                                    + " on this match and the declaration does not set it;"
                                    + " after the apply it reverts to "
                                    + (inherited == null ? "the broker's default" : inherited + " (inherited)")
                                    + ". Declare it to keep it.");
                }
            }
        }

        /** The address-full and page-full policies that lose or stall messages once a limit is reached. */
        private void fullPolicyHazards(
                String match,
                Map<String, Object> declared,
                Map<String, Object> before,
                List<String> covered,
                HazardClass reach) {
            String addressFullKey = AddressSettingKey.ADDRESS_FULL_MESSAGE_POLICY.jsonName();
            Object policy = declared.get(addressFullKey);
            if (policy != null && !Values.same(policy, before.get(addressFullKey))) {
                String p = policy.toString();
                if (p.equals("DROP") || p.equals("FAIL")) {
                    hazard(
                            HazardKind.MESSAGE_LOSS_POLICY,
                            reach,
                            Section.ADDRESS_SETTING,
                            match,
                            addressFullKey,
                            "address-full-policy becomes " + p + ": once the address is full, producers'"
                                    + (p.equals("DROP") ? " messages are discarded" : " sends are refused")
                                    + coveredText(covered) + ".");
                } else if (p.equals("BLOCK")) {
                    hazard(
                            HazardKind.BLOCKING_POLICY,
                            reach,
                            Section.ADDRESS_SETTING,
                            match,
                            addressFullKey,
                            "address-full-policy becomes BLOCK: once the address is full, producers stall until it"
                                    + " drains" + coveredText(covered) + ".");
                }
            }
            String pageFullKey = AddressSettingKey.PAGE_FULL_MESSAGE_POLICY.jsonName();
            Object pagePolicy = declared.get(pageFullKey);
            if (pagePolicy != null
                    && "FAIL".equals(pagePolicy.toString())
                    && !Values.same(pagePolicy, before.get(pageFullKey))) {
                hazard(
                        HazardKind.MESSAGE_LOSS_POLICY,
                        reach,
                        Section.ADDRESS_SETTING,
                        match,
                        pageFullKey,
                        "page-full-policy becomes FAIL: once the page limit is reached, sends are refused"
                                + coveredText(covered) + ".");
            }
        }

        /** Dead-letter and expiry redirection, and the auto-delete switches, all only matter to addresses under the match. */
        private void redirectHazards(
                String match, Map<String, Object> declared, Map<String, Object> before, List<String> covered) {
            if (covered.isEmpty()) {
                return;
            }
            for (AddressSettingKey k :
                    List.of(AddressSettingKey.DEAD_LETTER_ADDRESS, AddressSettingKey.EXPIRY_ADDRESS)) {
                Object v = declared.get(k.jsonName());
                if (v != null && !Values.same(v, before.get(k.jsonName()))) {
                    hazard(
                            HazardKind.DLQ_EXPIRY_CHANGE,
                            HazardClass.MEDIUM,
                            Section.ADDRESS_SETTING,
                            match,
                            k.jsonName(),
                            k.xmlName() + " changes from " + orDefault(before.get(k.jsonName())) + " to " + v
                                    + coveredText(covered) + ".");
                }
            }
            for (AddressSettingKey k : List.of(
                    AddressSettingKey.AUTO_DELETE_QUEUES,
                    AddressSettingKey.AUTO_DELETE_ADDRESSES,
                    AddressSettingKey.AUTO_DELETE_CREATED_QUEUES)) {
                Object v = declared.get(k.jsonName());
                if (Boolean.TRUE.equals(v) && !Values.same(v, before.get(k.jsonName()))) {
                    hazard(
                            HazardKind.AUTO_DELETE_ENABLED,
                            HazardClass.MEDIUM,
                            Section.ADDRESS_SETTING,
                            match,
                            k.jsonName(),
                            k.xmlName() + " turns on: the broker will remove idle resources" + coveredText(covered)
                                    + ".");
                }
            }
        }

        private void redistributionHazard(String match, Map<String, Object> declared, Map<String, Object> before) {
            String key = AddressSettingKey.REDISTRIBUTION_DELAY.jsonName();
            Object redistribution = declared.get(key);
            if (redistribution != null && !Values.same(redistribution, before.get(key))) {
                hazard(
                        HazardKind.REDISTRIBUTION_CHANGE,
                        HazardClass.LOW,
                        Section.ADDRESS_SETTING,
                        match,
                        key,
                        "redistribution-delay changes from " + orDefault(before.get(key)) + " to " + redistribution
                                + "; -1 stops messages moving to nodes with consumers.");
            }
        }

        private void limitBelowUsage(
                String match,
                Map<String, Object> declared,
                AddressSettingKey key,
                List<String> covered,
                boolean bytes) {
            BigDecimal limit = BrokerConfigValidator.number(declared.get(key.jsonName()));
            if (limit == null || limit.signum() < 0) {
                return;
            }
            for (String address : covered) {
                AddressUsage usage = node.addressUsage().get(address);
                if (usage == null) {
                    continue;
                }
                long current = bytes ? usage.bytes() : usage.messages();
                if (BigDecimal.valueOf(current).compareTo(limit) > 0) {
                    hazard(
                            HazardKind.LIMIT_BELOW_USAGE,
                            HazardClass.HIGH,
                            Section.ADDRESS_SETTING,
                            match,
                            key.jsonName() + ":" + address,
                            key.xmlName() + " = " + limit + " is below what " + address + " already holds on "
                                    + node.nodeName() + " (" + current + (bytes ? " bytes" : " messages")
                                    + "); the full-address policy triggers immediately.");
                }
            }
        }

        void securitySettings() {
            Map<PermissionType, Set<String>> baseRoles = node.securitySettings().getOrDefault(DEFAULT_MATCH, Map.of());
            for (SecuritySettingDecl s : doc.securitySettings()) {
                Map<PermissionType, Set<String>> observed =
                        node.securitySettings().get(s.match());
                boolean already = observed != null && sameRoles(observed, s.permissions());
                boolean explicitEntry = observed != null && !observed.isEmpty() && !sameRoles(observed, baseRoles);
                Op op = explicitEntry ? Op.REPLACE : Op.ADD;
                String verb = op == Op.ADD ? "Add" : "Replace";
                steps.add(new Step(
                        id(Section.SECURITY_SETTING, s.match(), op),
                        op,
                        Section.SECURITY_SETTING,
                        s.match(),
                        rolesMap(observed),
                        rolesMap(s.permissions()),
                        already,
                        already
                                ? "Security setting " + s.match() + " already matches"
                                : verb + " security setting " + s.match()));
                if (!already) {
                    securitySettingHazards(s);
                }
            }
        }

        private void securitySettingHazards(SecuritySettingDecl s) {
            if (AddressMatch.isCatchAll(s.match())) {
                hazard(
                        HazardKind.BROAD_MATCH,
                        HazardClass.HIGH,
                        Section.SECURITY_SETTING,
                        s.match(),
                        null,
                        MATCH_LABEL + s.match() + "' is the catch-all: every address without a more specific"
                                + " security setting gets exactly these roles.");
            }
            if (PROTECTED_ADDRESSES.stream().anyMatch(a -> AddressMatch.covers(s.match(), a))) {
                hazard(
                        HazardKind.MANAGEMENT_ACCESS,
                        HazardClass.HIGH,
                        Section.SECURITY_SETTING,
                        s.match(),
                        null,
                        MATCH_LABEL + s.match() + "' covers the broker's management or notification address. If"
                                + " Rahdar's own user loses manage or view here, Rahdar is locked out and cannot"
                                + " revert it; the canary's verification read must succeed or the run halts.");
            }
        }

        void diverts() {
            for (DivertDecl d : doc.diverts()) {
                DivertDecl observed = node.diverts().get(d.name());
                Map<String, Object> after = divertMap(d);
                if (observed == null) {
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.ADD),
                            Op.ADD,
                            Section.DIVERT,
                            d.name(),
                            Map.of(),
                            after,
                            false,
                            "Create divert " + d.name() + " from " + d.address() + " to " + d.forwardingAddress()));
                    divertHazards(d);
                } else if (observed.sameAs(d)) {
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.ADD),
                            Op.ADD,
                            Section.DIVERT,
                            d.name(),
                            divertMap(observed),
                            after,
                            true,
                            DIVERT_LABEL + d.name() + EXISTS_AS_DECLARED));
                } else {
                    // A second createDivert for an existing name succeeds and changes
                    // nothing (M4), so a change is an explicit remove and add.
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.REMOVE),
                            Op.REMOVE,
                            Section.DIVERT,
                            d.name(),
                            divertMap(observed),
                            Map.of(),
                            false,
                            REMOVE_DIVERT + d.name() + " (it differs from the declaration)"));
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.ADD),
                            Op.ADD,
                            Section.DIVERT,
                            d.name(),
                            Map.of(),
                            after,
                            false,
                            "Recreate divert " + d.name() + " as declared"));
                    hazard(
                            HazardKind.DIVERT_REPLACE,
                            HazardClass.MEDIUM,
                            Section.DIVERT,
                            d.name(),
                            null,
                            DIVERT_LABEL + d.name() + " exists with different properties. Changing it is a delete and a"
                                    + " create; messages arriving between the two are not diverted.");
                    divertHazards(d);
                }
            }
        }

        /**
         * Bridges last in (ADR-0091): a bridge reads from a queue and forwards to an
         * address, both of which earlier steps may create. There is no
         * {@code updateBridge}, so a change is a removal and a creation with a gap in
         * which nothing is forwarded — named as a High hazard rather than made safe.
         */
        void bridges() {
            for (BridgeDecl b : doc.bridges()) {
                ObservedNodeConfig.ObservedBridge observed = node.bridges().get(b.name());
                Map<String, Object> after = bridgeMap(b);
                if (observed == null) {
                    steps.add(new Step(
                            id(Section.BRIDGE, b.name(), Op.ADD),
                            Op.ADD,
                            Section.BRIDGE,
                            b.name(),
                            Map.of(),
                            after,
                            false,
                            "Create bridge " + b.name() + " from " + b.queueName() + " to " + b.forwardingAddress()
                                    + " on " + target(b)));
                    hazard(
                            HazardKind.BRIDGE_CREATE,
                            HazardClass.MEDIUM,
                            Section.BRIDGE,
                            b.name(),
                            null,
                            BRIDGE_LABEL + b.name() + " starts forwarding " + b.queueName()
                                    + "'s messages off this cluster, to " + b.forwardingAddress() + " on " + target(b)
                                    + ".");
                    transformerHazard(Section.BRIDGE, b.name(), b.transformer());
                } else if (b.sameAs(observed.config()) && observed.instances() == b.effectiveConcurrency()) {
                    steps.add(new Step(
                            id(Section.BRIDGE, b.name(), Op.ADD),
                            Op.ADD,
                            Section.BRIDGE,
                            b.name(),
                            bridgeMap(observed.config()),
                            after,
                            true,
                            BRIDGE_LABEL + b.name() + EXISTS_AS_DECLARED));
                    // Running state, not configuration: a bridge that matches and is not
                    // connected is a fault to report, never drift to reconcile.
                    if (!observed.connected()) {
                        findings.add(new Finding(
                                FindingKind.NOT_CONNECTED,
                                node.nodeId(),
                                node.nodeName(),
                                Section.BRIDGE,
                                b.name(),
                                "Matches the declaration but is "
                                        + (observed.started() ? "not connected to" : "not started, so nothing reaches")
                                        + " " + b.forwardingAddress()
                                        + ". Nothing is being forwarded and messages are accumulating on "
                                        + b.queueName() + "."));
                    }
                } else {
                    steps.add(new Step(
                            id(Section.BRIDGE, b.name(), Op.REMOVE),
                            Op.REMOVE,
                            Section.BRIDGE,
                            b.name(),
                            bridgeMap(observed.config()),
                            Map.of(),
                            false,
                            REMOVE_BRIDGE + b.name() + " (it differs from the declaration in "
                                    + String.join(", ", bridgeDifferences(b, observed)) + ")"));
                    steps.add(new Step(
                            id(Section.BRIDGE, b.name(), Op.ADD),
                            Op.ADD,
                            Section.BRIDGE,
                            b.name(),
                            Map.of(),
                            after,
                            false,
                            "Recreate bridge " + b.name() + " as declared"));
                    hazard(
                            HazardKind.BRIDGE_REPLACE,
                            HazardClass.HIGH,
                            Section.BRIDGE,
                            b.name(),
                            null,
                            BRIDGE_LABEL + b.name() + " differs in " + String.join(", ", bridgeDifferences(b, observed))
                                    + ". The broker cannot change a bridge in place, so this is a removal and a"
                                    + " creation: between the two nothing is forwarded to " + b.forwardingAddress()
                                    + " and " + b.queueName() + " accumulates.");
                    transformerHazard(Section.BRIDGE, b.name(), b.transformer());
                }
            }
        }

        /**
         * The fields that differ, including the concurrency — which {@code BridgeControl}
         * does not report, but which the <em>names</em> of the deployed MBeans do: a
         * concurrency of N deploys N of them. That is evidence, not an inference.
         */
        private static List<String> bridgeDifferences(BridgeDecl b, ObservedNodeConfig.ObservedBridge observed) {
            List<String> differing = new ArrayList<>(b.differencesFrom(observed.config()));
            if (observed.instances() != b.effectiveConcurrency()) {
                differing.add("concurrency (" + observed.instances() + " deployed, " + b.effectiveConcurrency()
                        + " declared)");
            }
            return differing;
        }

        private static String target(BridgeDecl b) {
            return b.discoveryGroupName() != null
                    ? "discovery group " + b.discoveryGroupName()
                    : String.join(", ", b.staticConnectors());
        }

        /**
         * ADR-0090 D6: no management operation reports what a broker has loaded, so the
         * class cannot be verified before the apply runs. The field is never gated on
         * this — the hazard says so, and the read-back names the class if a node
         * declines to deploy.
         */
        private void transformerHazard(Section section, String key, BrokerConfigDocument.TransformerDecl t) {
            if (t == null) {
                return;
            }
            hazard(
                    HazardKind.UNVERIFIABLE_TRANSFORMER,
                    HazardClass.MEDIUM,
                    section,
                    key,
                    t.className(),
                    "Rahdar cannot check that " + t.className() + " is on " + node.nodeName()
                            + "'s classpath until the apply runs. If the class cannot be loaded the node answers"
                            + " success and deploys nothing; the read-back reports that and names the class.");
        }

        private void divertHazards(DivertDecl d) {
            transformerHazard(Section.DIVERT, d.name(), d.transformer());
            if (!d.exclusive()) {
                return;
            }
            boolean hasQueues =
                    node.queues().values().stream().anyMatch(q -> d.address().equals(q.get(ADDRESS)));
            hazard(
                    HazardKind.EXCLUSIVE_DIVERT,
                    hasQueues ? HazardClass.HIGH : HazardClass.MEDIUM,
                    Section.DIVERT,
                    d.name(),
                    null,
                    DIVERT_LABEL + d.name() + " is exclusive: messages on " + d.address() + " are taken to "
                            + d.forwardingAddress() + ", not copied"
                            + (hasQueues ? "; queues on " + d.address() + " stop receiving them" : "") + ".");
        }

        void removals() {
            // Reverse dependency order: bridges, then diverts, then security settings,
            // then address settings. A bridge reads from a queue and forwards to an
            // address, so it is last in and first out.
            removeBridges();
            removeDiverts();
            removeSecuritySettings();
            removeAddressSettings();
        }

        private List<OwnedItem> ownedIn(Section section) {
            return owned.stream()
                    .filter(o -> o.section() == section)
                    .sorted(Comparator.comparing(OwnedItem::key))
                    .toList();
        }

        private void removeBridges() {
            Set<String> declaredBridges = new HashSet<>();
            doc.bridges().forEach(b -> declaredBridges.add(b.name()));
            Set<String> ownedBridges = new HashSet<>();
            for (OwnedItem item : ownedIn(Section.BRIDGE)) {
                ownedBridges.add(item.key());
                ObservedNodeConfig.ObservedBridge observed = node.bridges().get(item.key());
                if (declaredBridges.contains(item.key()) || observed == null) {
                    continue;
                }
                steps.add(new Step(
                        id(Section.BRIDGE, item.key(), Op.REMOVE),
                        Op.REMOVE,
                        Section.BRIDGE,
                        item.key(),
                        bridgeMap(observed.config()),
                        Map.of(),
                        false,
                        REMOVE_BRIDGE + item.key() + NO_LONGER_DECLARED));
                bridgeRemovalHazard(item.key(), observed);
            }
            if (options.removeUndeclared()) {
                for (Map.Entry<String, ObservedNodeConfig.ObservedBridge> e :
                        new TreeMap<>(node.bridges()).entrySet()) {
                    if (declaredBridges.contains(e.getKey()) || ownedBridges.contains(e.getKey())) {
                        continue;
                    }
                    steps.add(new Step(
                            id(Section.BRIDGE, e.getKey(), Op.REMOVE),
                            Op.REMOVE,
                            Section.BRIDGE,
                            e.getKey(),
                            bridgeMap(e.getValue().config()),
                            Map.of(),
                            false,
                            REMOVE_BRIDGE + e.getKey() + " (not declared; Rahdar did not create it)"));
                    bridgeRemovalHazard(e.getKey(), e.getValue());
                }
            }
        }

        private void removeDiverts() {
            Set<String> declaredDiverts = new HashSet<>();
            doc.diverts().forEach(d -> declaredDiverts.add(d.name()));
            Set<String> ownedDiverts = new HashSet<>();
            for (OwnedItem item : ownedIn(Section.DIVERT)) {
                ownedDiverts.add(item.key());
                DivertDecl observed = node.diverts().get(item.key());
                if (declaredDiverts.contains(item.key()) || observed == null) {
                    continue;
                }
                steps.add(new Step(
                        id(Section.DIVERT, item.key(), Op.REMOVE),
                        Op.REMOVE,
                        Section.DIVERT,
                        item.key(),
                        divertMap(observed),
                        Map.of(),
                        false,
                        REMOVE_DIVERT + item.key() + NO_LONGER_DECLARED));
                hazard(
                        HazardKind.REMOVE_OWNED,
                        HazardClass.MEDIUM,
                        Section.DIVERT,
                        item.key(),
                        null,
                        DIVERT_LABEL + item.key() + " is removed because it left the declaration.");
            }
            if (options.removeUndeclared()) {
                for (Map.Entry<String, DivertDecl> e : new TreeMap<>(node.diverts()).entrySet()) {
                    if (declaredDiverts.contains(e.getKey()) || ownedDiverts.contains(e.getKey())) {
                        continue;
                    }
                    steps.add(new Step(
                            id(Section.DIVERT, e.getKey(), Op.REMOVE),
                            Op.REMOVE,
                            Section.DIVERT,
                            e.getKey(),
                            divertMap(e.getValue()),
                            Map.of(),
                            false,
                            REMOVE_DIVERT + e.getKey() + " (not declared; Rahdar did not create it)"));
                    hazard(
                            HazardKind.REMOVE_UNDECLARED,
                            HazardClass.HIGH,
                            Section.DIVERT,
                            e.getKey(),
                            null,
                            DIVERT_LABEL + e.getKey() + " was not created by Rahdar. If broker.xml declares it, it"
                                    + " comes back on the next restart and Rahdar cannot tell.");
                }
            }
        }

        private void removeSecuritySettings() {
            Set<String> declaredSecurity = new HashSet<>();
            doc.securitySettings().forEach(s -> declaredSecurity.add(s.match()));
            Map<PermissionType, Set<String>> baseRoles = node.securitySettings().getOrDefault(DEFAULT_MATCH, Map.of());
            for (OwnedItem item : ownedIn(Section.SECURITY_SETTING)) {
                Map<PermissionType, Set<String>> observed =
                        node.securitySettings().get(item.key());
                if (declaredSecurity.contains(item.key()) || observed == null || sameRoles(observed, baseRoles)) {
                    continue;
                }
                steps.add(new Step(
                        id(Section.SECURITY_SETTING, item.key(), Op.REMOVE),
                        Op.REMOVE,
                        Section.SECURITY_SETTING,
                        item.key(),
                        rolesMap(observed),
                        Map.of(),
                        false,
                        "Remove security setting " + item.key() + NO_LONGER_DECLARED));
                hazard(
                        HazardKind.REMOVE_OWNED,
                        HazardClass.MEDIUM,
                        Section.SECURITY_SETTING,
                        item.key(),
                        null,
                        "Security setting " + item.key() + " is removed; addresses under it fall back to the next"
                                + " match.");
            }
        }

        private void removeAddressSettings() {
            Set<String> declaredMatches = new HashSet<>();
            doc.addressSettings().forEach(s -> declaredMatches.add(s.match()));
            Map<String, Object> base = base();
            for (OwnedItem item : ownedIn(Section.ADDRESS_SETTING)) {
                Map<String, Object> observed = node.addressSettings().get(item.key());
                if (declaredMatches.contains(item.key()) || observed == null || sameMap(observed, base)) {
                    continue;
                }
                steps.add(new Step(
                        id(Section.ADDRESS_SETTING, item.key(), Op.REMOVE),
                        Op.REMOVE,
                        Section.ADDRESS_SETTING,
                        item.key(),
                        Values.sorted(observed),
                        Values.sorted(base),
                        false,
                        "Remove address setting " + item.key() + NO_LONGER_DECLARED));
                hazard(
                        HazardKind.REMOVE_OWNED,
                        HazardClass.MEDIUM,
                        Section.ADDRESS_SETTING,
                        item.key(),
                        null,
                        "Address setting " + item.key() + " is removed; addresses under it inherit the parent"
                                + " match's values.");
            }
        }

        /** Removing a bridge is its own hazard: traffic to another broker stops and nothing here says so. */
        private void bridgeRemovalHazard(String name, ObservedNodeConfig.ObservedBridge observed) {
            hazard(
                    HazardKind.BRIDGE_REMOVE,
                    HazardClass.HIGH,
                    Section.BRIDGE,
                    name,
                    null,
                    BRIDGE_LABEL + name + " is removed: " + observed.config().queueName()
                            + " stops being forwarded to " + observed.config().forwardingAddress()
                            + " on the other broker, which will simply stop receiving. Nothing on this cluster"
                            + " reports that it has, and " + observed.config().queueName() + " accumulates.");
        }

        void undeclared() {
            if (!options.reportUndeclared()) {
                return;
            }
            undeclaredAddresses();
            undeclaredQueues();
            undeclaredDiverts();
            undeclaredBridges();
        }

        private void notDeclared(Section section, String key) {
            findings.add(
                    new Finding(FindingKind.UNDECLARED, node.nodeId(), node.nodeName(), section, key, NOT_DECLARED));
        }

        private void undeclaredAddresses() {
            Set<String> declaredAddresses = new HashSet<>();
            doc.addresses().forEach(a -> declaredAddresses.add(a.name()));
            for (String address : new TreeMap<>(node.addresses()).keySet()) {
                if (!declaredAddresses.contains(address) && !excluded(address) && !system(address)) {
                    notDeclared(Section.ADDRESS, address);
                }
            }
        }

        private void undeclaredQueues() {
            Set<String> declaredQueues = new HashSet<>();
            doc.addresses().forEach(a -> a.queues().forEach(q -> declaredQueues.add(q.name())));
            for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(node.queues()).entrySet()) {
                String address = Objects.toString(e.getValue().get(ADDRESS), "");
                if (!declaredQueues.contains(e.getKey())
                        && !excluded(e.getKey())
                        && !excluded(address)
                        && !system(address)
                        && !system(e.getKey())) {
                    findings.add(new Finding(
                            FindingKind.UNDECLARED,
                            node.nodeId(),
                            node.nodeName(),
                            Section.QUEUE,
                            e.getKey(),
                            address.isEmpty() ? NOT_DECLARED : "Exists on " + address + " and is not declared."));
                }
            }
        }

        private void undeclaredDiverts() {
            Set<String> declaredDiverts = new HashSet<>();
            doc.diverts().forEach(d -> declaredDiverts.add(d.name()));
            Set<String> removing = removalKeys(Section.DIVERT);
            for (String divert : new TreeMap<>(node.diverts()).keySet()) {
                if (!declaredDiverts.contains(divert) && !excluded(divert) && !removing.contains(divert)) {
                    notDeclared(Section.DIVERT, divert);
                }
            }
        }

        private void undeclaredBridges() {
            Set<String> declaredBridges = new HashSet<>();
            doc.bridges().forEach(b -> declaredBridges.add(b.name()));
            Set<String> removingBridges = removalKeys(Section.BRIDGE);
            for (String bridge : new TreeMap<>(node.bridges()).keySet()) {
                // A concurrent bridge's workers are instances of one declared name, not
                // undeclared bridges of their own.
                String declared = bridge.replaceFirst("-\\d+$", "");
                if (!declaredBridges.contains(bridge)
                        && !declaredBridges.contains(declared)
                        && !excluded(bridge)
                        && !removingBridges.contains(bridge)) {
                    notDeclared(Section.BRIDGE, bridge);
                }
            }
        }

        /** The keys of a section this node's plan already removes. */
        private Set<String> removalKeys(Section section) {
            Set<String> removing = new HashSet<>();
            steps.stream()
                    .filter(s -> s.section() == section && s.op() == Op.REMOVE)
                    .forEach(s -> removing.add(s.key()));
            return removing;
        }

        private static String id(Section section, String key, Op op) {
            return section + ":" + key + ":" + op;
        }

        private static String xml(String jsonName) {
            return AddressSettingKey.byJsonName(jsonName)
                    .map(AddressSettingKey::xmlName)
                    .orElse(jsonName);
        }

        private static String orUnset(Object value) {
            return value == null ? "unset" : value.toString();
        }

        private static String orDefault(Object value) {
            return value == null ? "the broker's default" : value.toString();
        }

        private static String coveredText(List<String> covered) {
            if (covered.isEmpty()) {
                return " (no address on this node is under the match yet)";
            }
            String shown = covered.size() <= 3
                    ? String.join(", ", covered)
                    : String.join(", ", covered.subList(0, 3)) + " and " + (covered.size() - 3) + " more";
            return " — under the match on this node: " + shown;
        }

        private static boolean sameMap(Map<String, Object> a, Map<String, Object> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, Object> e : a.entrySet()) {
                if (!b.containsKey(e.getKey()) || !Values.same(e.getValue(), b.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        /** Equal on every type the broker reports back; the unechoed ones cannot differ observably. */
        private static boolean sameRoles(Map<PermissionType, Set<String>> a, Map<PermissionType, Set<String>> b) {
            for (PermissionType t : PermissionType.values()) {
                if (t.echoed() && !a.getOrDefault(t, Set.of()).equals(b.getOrDefault(t, Set.of()))) {
                    return false;
                }
            }
            return true;
        }

        private static Map<String, Object> rolesMap(Map<PermissionType, Set<String>> roles) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (roles == null) {
                return out;
            }
            for (PermissionType t : PermissionType.values()) {
                Set<String> r = roles.get(t);
                if (r != null && !r.isEmpty()) {
                    out.put(t.xmlName(), String.join(",", new TreeSet<>(r)));
                }
            }
            return out;
        }

        private boolean excluded(String name) {
            return options.undeclaredExclusions().stream().anyMatch(p -> AddressMatch.covers(p, name));
        }

        private static boolean system(String address) {
            return address.startsWith("activemq.") || address.startsWith("$sys.") || address.startsWith("$.artemis.");
        }

        private void hazard(
                HazardKind kind, HazardClass hazardClass, Section section, String key, String subKey, String message) {
            String id = kind + ":" + node.nodeId() + ":" + section + ":" + key + (subKey == null ? "" : ":" + subKey);
            hazards.add(new Hazard(id, kind, hazardClass, node.nodeId(), node.nodeName(), section, key, message));
        }
    }

    // ---- helpers -------------------------------------------------------------

    /** A {@code DivertConfiguration} document with the hyphenated keys the JSON arm expects. */
    public static Map<String, Object> divertMap(DivertDecl d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("routing-name", d.name());
        m.put(ADDRESS, d.address());
        m.put("forwarding-address", d.forwardingAddress());
        m.put("exclusive", d.exclusive());
        if (d.filter() != null) {
            m.put(FILTER_STRING, d.filter());
        }
        if (d.routingType() != null) {
            m.put(ROUTING_TYPE, d.routingType());
        }
        transformer(m, d.transformer());
        return m;
    }

    /**
     * A {@code BridgeConfiguration} document with the hyphenated keys
     * {@code createBridge(String)} expects (ADR-0091). Anything unset is omitted so the
     * broker's own default stands; an unknown key would be accepted and silently
     * ignored, so the names here are the measured ones.
     *
     * <p>The credential is deliberately absent: it is resolved from the vault at the
     * moment of the broker call, so it reaches neither the plan, the plan hash, the
     * stored apply nor the audit parameters (ADR-0092).
     */
    public static Map<String, Object> bridgeMap(BridgeDecl b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.name());
        m.put("queue-name", b.queueName());
        m.put("forwarding-address", b.forwardingAddress());
        putIf(m, FILTER_STRING, b.filter());
        if (!b.staticConnectors().isEmpty()) {
            m.put("static-connectors", b.staticConnectors());
        }
        putIf(m, "discovery-group-name", b.discoveryGroupName());
        putIf(m, "ha", b.ha());
        putIf(m, "use-duplicate-detection", b.useDuplicateDetection());
        putIf(m, "retry-interval", b.retryInterval());
        putIf(m, "retry-interval-multiplier", b.retryIntervalMultiplier());
        putIf(m, "max-retry-interval", b.maxRetryInterval());
        putIf(m, "initial-connect-attempts", b.initialConnectAttempts());
        putIf(m, "reconnect-attempts", b.reconnectAttempts());
        putIf(m, "confirmation-window-size", b.confirmationWindowSize());
        putIf(m, "producer-window-size", b.producerWindowSize());
        putIf(m, "min-large-message-size", b.minLargeMessageSize());
        putIf(m, "check-period", b.checkPeriod());
        putIf(m, "connection-ttl", b.connectionTtl());
        putIf(m, ROUTING_TYPE, b.routingType());
        putIf(m, "concurrency", b.concurrency());
        putIf(m, "client-id", b.clientId());
        transformer(m, b.transformer());
        return m;
    }

    /**
     * The transformer is a <em>nested</em> object on both diverts and bridges. Three
     * flatter shapes were measured and every one deployed with the transformer silently
     * dropped, returning 200 (ADR-0091).
     */
    private static void transformer(Map<String, Object> m, BrokerConfigDocument.TransformerDecl t) {
        if (t == null) {
            return;
        }
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("class-name", t.className());
        nested.put("properties", t.properties());
        m.put("transformer-configuration", nested);
    }

    private static void putIf(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    /** A {@code QueueConfiguration} document, on the shape {@code QueueLifecycleService} sends. */
    public static Map<String, Object> queueConfig(String address, QueueDecl q) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", q.name());
        config.put(ADDRESS, address);
        config.put(ROUTING_TYPE, q.routingType().toUpperCase(Locale.ROOT));
        config.put("durable", q.durable());
        config.put(AUTO_CREATE_ADDRESS, false);
        if (q.filter() != null) {
            config.put(FILTER_STRING, q.filter());
        }
        if (q.maxConsumers() != null) {
            config.put("max-consumers", q.maxConsumers());
        }
        if (q.purgeOnNoConsumers() != null) {
            config.put("purge-on-no-consumers", q.purgeOnNoConsumers());
        }
        if (q.exclusive() != null) {
            config.put("exclusive", q.exclusive());
        }
        if (q.nonDestructive() != null) {
            config.put("non-destructive", q.nonDestructive());
        }
        if (q.ringSize() != null) {
            config.put("ring-size", q.ringSize());
        }
        return config;
    }

    private static UUID canary(List<NodePlan> nodes, UUID requested) {
        List<NodePlan> readable = nodes.stream().filter(NodePlan::readable).toList();
        if (requested != null && readable.stream().anyMatch(n -> n.nodeId().equals(requested))) {
            return requested;
        }
        return readable.isEmpty() ? null : readable.getFirst().nodeId();
    }

    private static String hash(List<NodePlan> nodes) {
        StringBuilder sb = new StringBuilder();
        for (NodePlan n : nodes) {
            for (Step s : n.steps()) {
                if (s.already()) {
                    continue;
                }
                sb.append(n.nodeId())
                        .append('|')
                        .append(s.id())
                        .append('|')
                        .append(new TreeMap<>(s.after()))
                        .append('\n');
            }
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
