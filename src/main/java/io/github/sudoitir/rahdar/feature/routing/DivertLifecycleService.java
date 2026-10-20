package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DivertRoute;
import io.github.sudoitir.rahdar.platform.broker.DivertSettings;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Check;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Divert creation and deletion across a cluster (ADR-0065).
 *
 * <p>A divert exists per node, so a command fans out to every live node with the same per-node
 * outcomes, preview and audit row as every other topology mutation (ADR-0049). A partial failure is
 * reported, never rolled back.
 */
@Service
@RequiredArgsConstructor
public class DivertLifecycleService {

    private static final String QUEUES_TOPIC = "queues";

    private final SseHub sseHub;
    private final BrokerCommands commands;
    private final ClusterAccessGuard access;
    private final ClusterDirectory clusters;
    private final BrokerGateway gateway;
    private final SystemExtensions extensions;
    private final Validator validator;

    /**
     * Create a divert on every live node.
     *
     * <p>Each node is checked first, in the preview and again for real: a forwarding address that is
     * not there would make producers to the source fail, and a cycle would copy a message around it
     * indefinitely. The result is what the broker deployed, read back: an identical divert already there
     * is {@code ALREADY}, a different one under the same name fails naming the difference.
     */
    public Attempt<LifecycleOutcome> createDivert(UUID clusterId, CreateDivertRequest req, boolean dryRun) {
        requireValid(req);
        refuseReservedName(req.name());
        // The divert as the broker is asked for it, which is what the audit row keeps.
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("name", req.name());
        audited.put(
                "routing-name",
                req.routingName() == null || req.routingName().isBlank() ? req.name() : req.routingName());
        audited.put("address", req.address());
        audited.put("forwarding-address", req.forwardingAddress());
        audited.put("exclusive", req.exclusive());
        if (req.filter() != null && !req.filter().isBlank()) {
            audited.put("filter-string", req.filter());
        }
        if (req.routingType() != null && !req.routingType().isBlank()) {
            audited.put("routing-type", req.routingType().toUpperCase(Locale.ROOT));
        }
        audited.put("acknowledgeCaptureShadowing", req.acknowledgeCaptureShadowing());
        DivertSettings settings = extensions.require(clusterId, DivertSettings.class);
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(DivertKind.CREATE_DIVERT.permission())
                // A divert copies what reaches one address into another: it needs both.
                .resources(List.of(
                        ResourceRef.destination(req.address()), ResourceRef.destination(req.forwardingAddress())))
                .auditAction(DivertKind.CREATE_DIVERT.auditName())
                .targetType(DivertKind.TARGET_TYPE)
                .targetName(req.name())
                .params(audited)
                .dryRun(dryRun)
                .preflight(node -> createPreflight(settings, node, req))
                .action(node -> DivertCalls.create(node, req))
                .signal(() -> sseHub.publishAbout(
                        clusterId, QUEUES_TOPIC, List.of(), List.of(req.address(), req.forwardingAddress())))
                .build()));
    }

    private Check createPreflight(DivertSettings settings, BrokerTarget node, CreateDivertRequest req) {
        List<DivertRoute> existing = DivertRoute.list(node);
        String cycle = cycle(existing, req.name(), req.address(), req.forwardingAddress());
        if (cycle != null) {
            return Check.refuse("This divert would complete a cycle of diverts: " + cycle
                    + ". A message would be copied around it without end.");
        }
        if (!settings.addressAvailable(node, req.forwardingAddress())) {
            return Check.refuse("The forwarding address '" + req.forwardingAddress() + "' does not exist on this node"
                    + " and is not created automatically, so producers to '" + req.address() + "' would fail."
                    + " Create the address first, or declare it in broker.xml: <addresses><address name=\""
                    + req.forwardingAddress() + "\"><anycast/></address></addresses>");
        }
        boolean captured = existing.stream()
                .anyMatch(d -> d.name() != null
                        && d.name().startsWith(RahdarNames.CAPTURE_PREFIX)
                        && req.address().equals(d.address()));
        if (Boolean.TRUE.equals(req.exclusive()) && captured) {
            String effect = "'" + req.address() + "' is being captured, and Artemis applies exclusive diverts before"
                    + " every other one: capture of it would observe nothing while this divert exists.";
            return Boolean.TRUE.equals(req.acknowledgeCaptureShadowing())
                    ? Check.warn(effect)
                    : Check.refuse(effect + " Set acknowledgeCaptureShadowing to create it anyway.");
        }
        return Check.OK;
    }

    /**
     * The cycle a new divert would close, as {@code A → B → A}, or null. Built from the diverts already on
     * the node, excluding any with the new divert's name.
     */
    static String cycle(List<DivertRoute> existing, String name, String address, String forwardingAddress) {
        Map<String, List<String>> edges = new HashMap<>();
        for (DivertRoute d : existing) {
            if (d.address() != null && d.forwardingAddress() != null && !Objects.equals(d.name(), name)) {
                edges.computeIfAbsent(d.address(), k -> new ArrayList<>()).add(d.forwardingAddress());
            }
        }
        List<String> path = pathTo(edges, forwardingAddress, address, new HashSet<>());
        if (path.isEmpty()) {
            return null;
        }
        List<String> cycle = new ArrayList<>();
        cycle.add(address);
        cycle.addAll(path);
        return String.join(" → ", cycle);
    }

    private static List<String> pathTo(Map<String, List<String>> edges, String from, String to, Set<String> seen) {
        if (from.equals(to)) {
            return new ArrayList<>(List.of(from));
        }
        if (!seen.add(from)) {
            return List.of();
        }
        for (String next : edges.getOrDefault(from, List.of())) {
            List<String> rest = pathTo(edges, next, to, seen);
            if (!rest.isEmpty()) {
                rest.addFirst(from);
                return rest;
            }
        }
        return List.of();
    }

    /** Bean validation for callers that do not pass through a validated controller argument (MCP). */
    private void requireValid(CreateDivertRequest req) {
        var violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    /** Capture's and plugins' diverts are managed by their owners (ADR-0079), whichever API is asked. */
    private static void refuseReservedName(String name) {
        if (name != null && name.startsWith(RahdarNames.CAPTURE_PREFIX)) {
            throw new IllegalArgumentException("'" + name + "' is in the namespace reserved for message capture ("
                    + RahdarNames.CAPTURE_PREFIX + "). Manage it from its capture subscription instead.");
        }
        if (name != null && name.startsWith(RahdarNames.PLUGIN_TAP_PREFIX)) {
            throw new IllegalArgumentException("'" + name + "' is in the namespace reserved for plugins' message taps ("
                    + RahdarNames.PLUGIN_TAP_PREFIX
                    + "). It is removed with the plugin registration that owns it.");
        }
    }

    /**
     * Delete a divert from every live node. Nothing else removes one: a divert created over management
     * outlives the broker process (ADR-0065), so this is the only path, and the confirmation says so
     * rather than implying a restart would do it.
     */
    public Attempt<LifecycleOutcome> deleteDivert(UUID clusterId, String name, boolean dryRun) {
        refuseReservedName(name);
        boolean wholeCluster = access.holds(clusterId, DivertKind.DELETE_DIVERT.permission());
        Set<ResourceRef> ends = wholeCluster ? Set.of() : divertEnds(clusterId, name);
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(DivertKind.DELETE_DIVERT.permission())
                .resources(List.copyOf(ends))
                .auditAction(DivertKind.DELETE_DIVERT.auditName())
                .targetType(DivertKind.TARGET_TYPE)
                .targetName(name)
                .params(Map.of())
                .dryRun(dryRun)
                // Each node's own divert is what is destroyed there: one that runs between addresses nobody
                // checked is left alone, so a name that means something else on another node costs nothing.
                .preflight(node -> wholeCluster || ends.containsAll(endsOn(node, name))
                        ? Check.OK
                        : Check.refuse("The divert on this node is not one you may delete, so it was left alone."))
                .action(node -> DivertCalls.delete(node, name))
                .signal(() -> sseHub.publishAbout(clusterId, QUEUES_TOPIC, ends))
                .build()));
    }

    /**
     * The addresses the divert runs between on every node that has it, which deleting it needs
     * {@code route:write} on: it is destroyed wherever it is deployed. A caller who may not read or write
     * all of them is told the divert does not exist, as is one whom nothing is known of it, so a name
     * cannot be told from a missing one.
     */
    private Set<ResourceRef> divertEnds(UUID clusterId, String name) {
        access.requireVisible(clusterId);
        Set<ResourceRef> ends = new LinkedHashSet<>();
        for (ClusterNode node : clusters.nodes(clusterId)) {
            if (node.getManagementUrl() == null) {
                continue;
            }
            try {
                ends.addAll(endsOn(gateway.node(clusterId, node.getId()), name));
            } catch (BrokerConnectionException _) {
                // Another node may still answer; a node that cannot is checked again when it is acted on.
            }
        }
        if (ends.isEmpty()) {
            throw new NotFoundException("divert", name);
        }
        try {
            access.requireAll(
                    clusterId,
                    ends.stream()
                            .map(end -> new ClusterAccessGuard.Requirement(end, DivertKind.DELETE_DIVERT.permission()))
                            .toList());
        } catch (NotFoundException _) {
            throw new NotFoundException("divert", name);
        }
        return ends;
    }

    private static List<ResourceRef> endsOn(BrokerTarget node, String name) {
        return DivertRoute.find(node, name)
                .map(d -> List.of(ResourceRef.destination(d.address()), ResourceRef.destination(d.forwardingAddress())))
                .orElse(List.of());
    }
}
