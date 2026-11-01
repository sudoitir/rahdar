package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.PolicyMatches.Candidate;
import io.github.sudoitir.rahdar.system.rabbitmq.PolicyMatches.Match;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicyClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicySet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Policy;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyMatch;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyPreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangeResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * A RabbitMQ cluster's policies, and what a change to one would reach (ADR-0223). A preview lists the queues and
 * exchanges the pattern matches now, as Rahdar matches it, and those another policy of higher priority already wins.
 */
@Service
@RequiredArgsConstructor
public class RabbitMqPolicies {

    private static final String ALL = "all";

    /** The most matches a preview lists; the effect's count has all of them. */
    private static final int LISTED = 50;

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final RabbitMqOps ops;
    private final RabbitMqChanges changes;

    public PolicyPage list(UUID clusterId, String vhost, String cursor, Integer limit) {
        access.requireCluster(clusterId, RabbitMqModule.POLICY_READ);
        ClusterConnection connection = gateway.cluster(clusterId);
        Map<String, String> selector =
                vhost == null || vhost.isBlank() ? Map.of() : Map.of(RabbitMqIds.VHOST_SELECTOR, vhost);
        List<Resource> found = new ArrayList<>();
        for (String kind : List.of(RabbitMqIds.POLICY, RabbitMqIds.OPERATOR_POLICY)) {
            found.addAll(RabbitMqReads.all(connection, RabbitMqReads.of(RabbitMqIds.kind(kind), selector))
                    .items());
        }
        found.sort(Comparator.comparing((Resource r) -> r.id().path().segments().get(0))
                .thenComparing(r -> r.id().name())
                .thenComparing(r -> r.id().kind().value()));
        int from = Math.min(RabbitMqReads.offset(cursor), found.size());
        int to = Math.min(found.size(), from + RabbitMqReads.limit(limit));
        Map<String, List<Candidate>> candidates = new HashMap<>();
        List<Policy> items = new ArrayList<>();
        for (Resource resource : found.subList(from, to)) {
            String host = resource.id().path().segments().get(0);
            List<Candidate> named = candidates.computeIfAbsent(
                    host, v -> candidates(connection, v).items());
            items.add(policy(resource, named));
        }
        return new PolicyPage(items, to < found.size() ? String.valueOf(to) : null);
    }

    private static Policy policy(Resource resource, List<Candidate> candidates) {
        PolicyMatches.Policy asMatched = matched(resource);
        long matches;
        try {
            matches = PolicyMatches.of(asMatched, candidates, List.of()).size();
        } catch (IllegalArgumentException _) {
            // A pattern Java cannot read matches nothing here; the broker's own reading is its own.
            matches = 0;
        }
        return new Policy(
                resource.id().format(),
                resource.id().path().segments().get(0),
                resource.id().name(),
                asMatched.pattern(),
                asMatched.applyTo(),
                asMatched.priority(),
                RabbitMqReads.members(resource, RabbitMqIds.DEFINITION_PREFIX),
                RabbitMqIds.OPERATOR_POLICY.equals(resource.id().kind().value()),
                matches);
    }

    private static PolicyMatches.Policy matched(Resource resource) {
        String applyTo = RabbitMqReads.text(resource, RabbitMqIds.APPLY_TO);
        return new PolicyMatches.Policy(
                resource.id().name(),
                RabbitMqReads.text(resource, RabbitMqIds.PATTERN),
                applyTo.isEmpty() ? ALL : applyTo,
                RabbitMqReads.number(resource, RabbitMqIds.PRIORITY));
    }

    /** The queues and exchanges of a virtual host, by name and kind. */
    record Candidates(List<Candidate> items, boolean partial) {}

    Candidates candidates(ClusterConnection connection, String vhost) {
        Map<String, String> selector = Map.of(RabbitMqIds.VHOST_SELECTOR, vhost);
        Set<String> light = Set.of(RabbitMqIds.DURABLE);
        RabbitMqReads.Found queues = RabbitMqReads.all(
                connection,
                ResourceQuery.of(ResourceClass.SUBSCRIPTION).where(selector).project(light));
        RabbitMqReads.Found exchanges = RabbitMqReads.all(
                connection,
                ResourceQuery.of(RabbitMqIds.kind(RabbitMqIds.EXCHANGE))
                        .where(selector)
                        .project(light));
        List<Candidate> out = new ArrayList<>();
        queues.items().forEach(q -> out.add(new Candidate(queueKind(q.id()), q.id().name())));
        exchanges.items().forEach(e -> out.add(new Candidate(PolicyMatches.EXCHANGE, e.id().name())));
        return new Candidates(out, queues.truncated() || exchanges.truncated());
    }

    private static String queueKind(ResourceId id) {
        return switch (id.kind().value()) {
            case RabbitMqIds.QUORUM_QUEUE -> PolicyMatches.QUORUM_QUEUE;
            case RabbitMqIds.STREAM -> PolicyMatches.STREAM;
            default -> PolicyMatches.CLASSIC_QUEUE;
        };
    }

    /** The policies of the virtual host, which compete for a queue or an exchange. */
    private List<PolicyMatches.Policy> rivals(ClusterConnection connection, String vhost) {
        return RabbitMqReads.all(
                        connection,
                        RabbitMqReads.of(
                                RabbitMqIds.kind(RabbitMqIds.POLICY), Map.of(RabbitMqIds.VHOST_SELECTOR, vhost)))
                .items()
                .stream()
                .map(RabbitMqPolicies::matched)
                .toList();
    }

    public PolicyPreview previewSet(UUID clusterId, String vhost, String name, PolicyRequest request) {
        access.requireCluster(clusterId, RabbitMqModule.POLICY_WRITE);
        ResourceId id = id(vhost, name);
        Map<String, Object> attributes = attributes(request);
        Preview preview = ops.preview(clusterId, id, Verb.CREATE, attributes);
        ClusterConnection connection = gateway.cluster(clusterId);
        Candidates candidates = candidates(connection, vhost);
        PolicyMatches.Policy asked = new PolicyMatches.Policy(
                name,
                request.pattern(),
                request.applyTo() == null ? ALL : request.applyTo(),
                request.priority() == null ? 0 : request.priority());
        List<Match> matches = PolicyMatches.of(asked, candidates.items(), rivals(connection, vhost));
        long shadowed = matches.stream().filter(m -> m.shadowedBy() != null).count();
        String summary = "Set policy " + name + " in virtual host " + vhost + ": it matches " + matches.size()
                + " queues and exchanges as Rahdar matches it, and "
                + shadowed + " of them are already won by a policy of higher priority.";
        return withMatches(RabbitMqOps.view(preview, Verb.CREATE, summary, List.of()), matches, candidates.partial());
    }

    public RabbitMqChangeResult set(UUID clusterId, String vhost, String name, PolicyRequest request) {
        access.requireCluster(clusterId, RabbitMqModule.POLICY_WRITE);
        return changes.apply(new PolicySet(clusterId, id(vhost, name).format(), attributes(request), request.etag()));
    }

    public PolicyPreview previewClear(UUID clusterId, String vhost, String name) {
        access.requireCluster(clusterId, RabbitMqModule.POLICY_WRITE);
        ResourceId id = id(vhost, name);
        Preview preview = ops.preview(clusterId, id, Verb.DELETE, Map.of());
        ClusterConnection connection = gateway.cluster(clusterId);
        Candidates candidates = candidates(connection, vhost);
        List<PolicyMatches.Policy> all = rivals(connection, vhost);
        List<Match> matches = all.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .map(p -> PolicyMatches.of(p, candidates.items(), all))
                .orElse(List.of());
        String summary = "Clear policy " + name + " in virtual host " + vhost + ": " + matches.size()
                + " queues and exchanges stop matching it, as Rahdar matches it.";
        return withMatches(RabbitMqOps.view(preview, Verb.DELETE, summary, List.of()), matches, candidates.partial());
    }

    public RabbitMqChangeResult clear(UUID clusterId, String vhost, String name, String etag) {
        access.requireCluster(clusterId, RabbitMqModule.POLICY_WRITE);
        return changes.apply(new PolicyClear(clusterId, id(vhost, name).format(), Map.of(), etag));
    }

    private static PolicyPreview withMatches(RabbitMqChangePreview preview, List<Match> matches, boolean partial) {
        List<PolicyMatch> listed = matches.stream()
                .limit(LISTED)
                .map(m -> new PolicyMatch(m.kind(), m.name(), m.shadowedBy()))
                .toList();
        return new PolicyPreview(
                preview.effect(), preview.etag(), preview.identityBasis(), listed, partial || matches.size() > LISTED);
    }

    private static ResourceId id(String vhost, String name) {
        if (vhost == null || vhost.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("A policy needs a virtual host and a name.");
        }
        return RabbitMqIds.id(RabbitMqIds.POLICY, vhost, name);
    }

    static Map<String, Object> attributes(PolicyRequest request) {
        if (request.pattern() == null || request.pattern().isBlank()) {
            throw new IllegalArgumentException("A policy needs a pattern.");
        }
        PolicyMatches.compile(request.pattern());
        PolicyMatches.covers(request.applyTo(), PolicyMatches.EXCHANGE);
        if (request.definition() == null || request.definition().isEmpty()) {
            throw new IllegalArgumentException("A policy needs at least one setting.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RabbitMqIds.PATTERN, request.pattern());
        out.put(RabbitMqIds.APPLY_TO, request.applyTo() == null ? ALL : request.applyTo());
        out.put(RabbitMqIds.PRIORITY, request.priority() == null ? 0 : request.priority());
        request.definition().forEach((key, value) -> out.put(RabbitMqIds.DEFINITION_PREFIX + key, value));
        return out;
    }
}
