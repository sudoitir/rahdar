package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ClusterMember;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlSource;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.broker.ProbeSession;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns one or more reachable seed connections into persisted {@code broker_node}
 * rows and a {@link ClusterTopology}.
 *
 * <p>Discovery keys on the broker-reported NodeID: a primary and its synced
 * backup share it (Phase 0), so they merge into one {@link LogicalNode}.
 * {@code listNetworkTopology()} returns broker-to-broker {@code host:port}
 * connectors, never Jolokia URLs, so a discovered endpoint lands with
 * {@code dataUrl} set and {@code managementUrl} null, until a management URL is derived for it from
 * the cluster's pattern and proved by the broker's NodeID (ADR-0175). A seed or manual URL is never
 * rewritten by discovery; a derived one is re-derived when the pattern changes. A topology view that
 * omits the {@code backup} key (the post-failover shape) means "not currently announced", not "delete".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopologyDiscovery {

    private static final String PRIMARY = "PRIMARY";
    private static final String BACKUP = "BACKUP";

    private final BrokerNodeRepository nodes;
    private final HaStateEvaluator evaluator;
    private final BrokerNodeMapper nodeMapper;
    private final ClusterIdentityClaims identityClaims;
    private final BrokerGateway gateway;
    private final TransactionTemplate transactions;

    /**
     * A seed the caller has already connected to. A seed that is not {@code attachable} is read for what
     * it reports and its URL is never given to a node: a TLS seed's addresses are read to learn the
     * NodeIDs behind its host name, which stays the seed.
     */
    public record ProbedSeed(String managementUrl, BrokerTarget target, boolean attachable) {

        public ProbedSeed(String managementUrl, BrokerTarget target) {
            this(managementUrl, target, true);
        }
    }

    /** What derives a node's management URL and proves it: the cluster's pattern and the management account. */
    public record UrlDerivation(String pattern, BrokerConnectionSettings settings) {}

    /** The preview's endpoints, and what the management account did on each, by endpoint name. */
    public record Preview(
            ClusterTopology topology, Map<String, AccountResult> management, Map<String, UrlProof> proofs) {}

    /**
     * What asking one management address came to: the broker that answered, or the reason nothing did.
     * Package-private for the connection check, which asks the addresses of a registered cluster.
     */
    record UrlProof(String url, SeedReading reading, ManagementUrlProblem problem, boolean throttled) {

        String nodeId() {
            return reading == null ? null : reading.nodeId();
        }

        String version() {
            return reading == null ? null : reading.version();
        }

        String haDesignation() {
            return reading == null
                    ? null
                    : new HaStateEvaluator().deriveHaDesignation(reading.backup(), reading.clustered());
        }

        AccountResult management() {
            return problem == null || problem == ManagementUrlProblem.OTHER_BROKER
                    ? AccountResult.ACCEPTED
                    : switch (problem) {
                        case NO_PATTERN -> AccountResult.NOT_TRIED;
                        case CREDENTIALS_REJECTED -> AccountResult.REJECTED;
                        default -> AccountResult.UNREACHABLE;
                    };
        }
    }

    private record SeedReading(
            String managementUrl,
            boolean attachable,
            String nodeId,
            boolean backup,
            Boolean started,
            boolean active,
            Boolean replicaSync,
            String version,
            Boolean clustered,
            List<TopologyEntry> entries) {

        SeedReading withEntries(List<TopologyEntry> found) {
            return new SeedReading(
                    managementUrl, attachable, nodeId, backup, started, active, replicaSync, version, clustered, found);
        }
    }

    private record TopologyEntry(String nodeId, String live, String primary, String backup) {
        String primaryConnector() {
            return primary != null ? primary : live;
        }
    }

    /**
     * What the seeds report, read once: one HA read and one topology read per seed. The connection
     * check and the registration each take one survey and use it for both the identity check and the
     * topology, so neither asks a broker twice.
     */
    public static final class Survey {

        private final List<SeedReading> readings;

        private Survey(List<SeedReading> readings) {
            this.readings = List.copyOf(readings);
        }

        /** The NodeIDs every seed reported, its own and its topology view's, and the seeds' URLs (ADR-0167). */
        ClusterIdentity identity() {
            Set<String> nodeIds = new TreeSet<>();
            Set<String> seedUrls = new TreeSet<>();
            Set<String> unidentified = new TreeSet<>();
            for (SeedReading r : readings) {
                String url = ClusterIdentity.normalise(r.managementUrl());
                seedUrls.add(url);
                if (r.nodeId() == null) {
                    unidentified.add(url);
                } else {
                    nodeIds.add(r.nodeId());
                }
                r.entries().stream()
                        .map(TopologyEntry::nodeId)
                        .filter(Objects::nonNull)
                        .forEach(nodeIds::add);
            }
            return new ClusterIdentity(nodeIds, seedUrls, unidentified);
        }
    }

    /** Read every seed once; see {@link Survey}. */
    public Survey survey(List<ProbedSeed> seeds) {
        return new Survey(readAnswering(seeds));
    }

    /**
     * Read the seeds, ask every address that may give a node its management URL, then persist what they
     * report in a transaction of its own: no broker is called inside a database transaction (ADR-0078).
     */
    public ClusterTopology discover(UUID clusterId, List<ProbedSeed> seeds, UrlDerivation derivation) {
        Survey survey = survey(seeds);
        Map<String, UrlProof> proofs = proveUnproved(clusterId, survey, derivation);
        return transactions.execute(status -> persist(clusterId, survey, proofs));
    }

    /**
     * Persist what a survey and the proofs read before it report, in the caller's transaction; nothing here
     * calls a broker.
     */
    @Transactional
    ClusterTopology discover(UUID clusterId, Survey survey, Map<String, UrlProof> proofs) {
        return persist(clusterId, survey, proofs);
    }

    private ClusterTopology persist(UUID clusterId, Survey survey, Map<String, UrlProof> proofs) {
        List<SeedReading> readings = survey.readings;

        // 1. Connector-named discovered rows from every seed's topology view.
        for (SeedReading r : readings) {
            for (TopologyEntry e : r.entries()) {
                upsertDiscovered(clusterId, e.primaryConnector(), PRIMARY, e.nodeId());
                if (e.backup() != null) {
                    upsertDiscovered(clusterId, e.backup(), BACKUP, e.nodeId());
                }
            }
        }

        // 2. Attach each seed's management URL and live state to its own row.
        Set<String> brokers = new HashSet<>();
        Set<String> known = nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .map(BrokerNodeEntity::getManagementUrl)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (SeedReading r : readings) {
            boolean first = firstOfItsBroker(brokers, r);
            if (r.attachable() && (known.contains(r.managementUrl()) || first)) {
                attachSeed(clusterId, r);
            }
        }

        // 3. Give every node that has none the management URL its proof found, or the reason it has none (ADR-0175).
        applyProofs(clusterId, proofs);

        // 4. The cluster now claims exactly the brokers its nodes carry (ADR-0167): nodes found after
        // registration are claimed, and a NodeID that changed releases the old one.
        identityClaims.sync(clusterId);

        // 5. Re-read and evaluate.
        return evaluated(clusterId);
    }

    /** The persisted topology, evaluated — no broker calls. */
    @Transactional(readOnly = true)
    public ClusterTopology currentTopology(UUID clusterId) {
        return evaluated(clusterId);
    }

    private ClusterTopology evaluated(UUID clusterId) {
        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        return new ClusterTopology(
                clusterId, evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), SplitBrainStatus.byNodeId(rows)));
    }

    /**
     * A non-persisting view of what the seeds report — for {@code ?dryRun=true}.
     * Reads the brokers, builds the topology entirely in memory, writes nothing,
     * and uses a throwaway evaluator so the real split-brain ratchet is untouched.
     * A node no seed answered for gets a management URL derived and proved exactly as a
     * registration would, so the preview shows what the registered cluster would hold.
     */
    public Preview preview(Survey survey, UrlDerivation derivation) {
        List<SeedReading> readings = survey.readings;
        HaStateEvaluator scratch = new HaStateEvaluator();
        Map<String, NodeEndpoint> byName = new LinkedHashMap<>();
        Map<String, AccountResult> management = new LinkedHashMap<>();
        Map<String, UrlProof> proofs = new LinkedHashMap<>();

        for (SeedReading r : readings) {
            for (TopologyEntry e : r.entries()) {
                byName.computeIfAbsent(e.primaryConnector(), k -> connectorEndpoint(k, PRIMARY, e.nodeId()));
                if (e.backup() != null) {
                    byName.computeIfAbsent(e.backup(), k -> connectorEndpoint(k, BACKUP, e.nodeId()));
                }
            }
        }
        attachLiveSeeds(scratch, readings, byName, management);
        deriveUnattached(scratch, derivation, byName, management, proofs);
        // Nothing is persisted for a preview, so there is no real cluster id yet; a
        // fresh one only gives every rendered node a stable React key (ADR-0014's
        // domain records are not otherwise nullable-aware).
        return new Preview(
                new ClusterTopology(UUID.randomUUID(), scratch.toLogicalNodes(List.copyOf(byName.values()))),
                management,
                proofs);
    }

    /** Lets each answering seed stand in for the connector row of its own broker, or add one named after its URL. */
    private static void attachLiveSeeds(
            HaStateEvaluator scratch,
            List<SeedReading> readings,
            Map<String, NodeEndpoint> byName,
            Map<String, AccountResult> management) {
        Set<String> brokers = new HashSet<>();
        for (SeedReading r : readings) {
            if (!firstOfItsBroker(brokers, r)) {
                continue;
            }
            String haDesignation = scratch.deriveHaDesignation(r.backup(), r.clustered());
            String key = byName.entrySet().stream()
                    .filter(en -> r.nodeId() != null
                            && r.nodeId().equals(en.getValue().nativeNodeId()))
                    .filter(en -> haDesignation.equals(en.getValue().haDesignation()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(seedName(r.managementUrl()));
            NodeEndpoint connector = byName.get(key);
            byName.put(
                    key,
                    liveEndpoint(
                            scratch, key, connector == null ? null : connector.dataUrl(), r, ManagementUrlSource.SEED));
            management.put(key, AccountResult.ACCEPTED);
        }
    }

    /** Derives and proves the management URL of every node no seed answered for, as a registration would. */
    private void deriveUnattached(
            HaStateEvaluator scratch,
            UrlDerivation derivation,
            Map<String, NodeEndpoint> byName,
            Map<String, AccountResult> management,
            Map<String, UrlProof> proofs) {
        for (Map.Entry<String, NodeEndpoint> en : List.copyOf(byName.entrySet())) {
            NodeEndpoint node = en.getValue();
            if (node.managementUrl() != null) {
                continue;
            }
            UrlProof proof = prove(derivation, node.name(), node.nativeNodeId());
            management.put(en.getKey(), proof.management());
            proofs.put(en.getKey(), proof);
            if (proof.url() != null) {
                byName.put(
                        en.getKey(),
                        liveEndpoint(
                                scratch, en.getKey(), node.dataUrl(), proof.reading(), ManagementUrlSource.DERIVED));
            } else if (!proof.throttled()) {
                byName.put(en.getKey(), withProblem(node, proof.problem()));
            }
        }
    }

    /**
     * Whether this is the first reading of its broker. A host name that resolves to an IPv4 and an IPv6
     * address, or two URLs for one broker, expand into seeds that answer as the same NodeID in the same
     * role: one broker, whose URL is the first that answered.
     */
    private static boolean firstOfItsBroker(Set<String> brokers, SeedReading r) {
        return r.nodeId() == null || brokers.add(r.nodeId() + "|" + (r.backup() ? BACKUP : "LIVE"));
    }

    private static NodeEndpoint liveEndpoint(
            HaStateEvaluator scratch, String name, String dataUrl, SeedReading r, ManagementUrlSource source) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                name,
                r.nodeId(),
                r.managementUrl(),
                dataUrl,
                scratch.deriveHaDesignation(r.backup(), r.clustered()),
                scratch.deriveState(r.started()),
                r.active(),
                r.replicaSync(),
                null,
                r.version(),
                null,
                null,
                Instant.now(),
                source,
                null,
                false,
                true);
    }

    private static NodeEndpoint connectorEndpoint(String name, String haDesignation, String nodeId) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                name,
                nodeId,
                null,
                name,
                haDesignation,
                "UNKNOWN",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false);
    }

    private static NodeEndpoint withProblem(NodeEndpoint node, ManagementUrlProblem problem) {
        return new NodeEndpoint(
                node.id(),
                node.name(),
                node.nativeNodeId(),
                null,
                node.dataUrl(),
                node.haDesignation(),
                node.state(),
                node.active(),
                node.replicaSync(),
                node.observedCycle(),
                node.version(),
                node.lastError(),
                node.lastErrorKind(),
                node.lastSeenAt(),
                null,
                problem,
                node.dataUrlManual(),
                false);
    }

    private void upsertDiscovered(UUID clusterId, String connector, String haDesignation, String nodeId) {
        Optional<BrokerNodeEntity> existing = nodes.findByClusterIdAndName(clusterId, connector);
        if (existing.isPresent()) {
            existing.get().mergeDiscovered(connector, haDesignation, nodeId);
            nodes.save(existing.get());
            return;
        }
        nodes.save(BrokerNodeEntity.discovered(clusterId, connector, haDesignation, nodeId));
    }

    /**
     * A seed's management URL is its identity: the row already holding it, else a
     * connector-named row without a URL on the seed's host (or, when the connector
     * is named differently from the management address, with the seed's NodeID and
     * role), else a new row. A URL is never moved from one row to another. HA role
     * and state are observed and are written to whichever row that is. A row that
     * already holds the seed's URL keeps its source: re-reading a node does not make
     * its URL a seed.
     */
    private void attachSeed(UUID clusterId, SeedReading r) {
        String haDesignation = evaluator.deriveHaDesignation(r.backup(), r.clustered());
        String state = evaluator.deriveState(r.started());

        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        BrokerNodeEntity node = rows.stream()
                .filter(n -> r.managementUrl().equals(n.getManagementUrl()))
                .findFirst()
                .or(() -> unmanaged(rows, n -> connectorHost(n.getName()).equals(seedHost(r.managementUrl()))))
                .or(() -> unmanaged(
                        rows,
                        n -> r.nodeId() != null
                                && r.nodeId().equals(n.getNativeNodeId())
                                && haDesignation.equals(n.getHaDesignation())))
                .orElseGet(() -> nodes.save(
                        BrokerNodeEntity.fromSeed(clusterId, seedName(r.managementUrl()), haDesignation, r.nodeId())));

        if (!r.managementUrl().equals(node.getManagementUrl())) {
            node.attachSeedUrl(r.managementUrl());
        }
        node.applyHaState(
                new HaObservation(r.active(), state, haDesignation, r.replicaSync(), r.version(), r.nodeId()),
                0L,
                Instant.now());
        nodes.save(node);
    }

    /** The first row whose management URL discovery may set, that the filter accepts. */
    private static Optional<BrokerNodeEntity> unmanaged(
            List<BrokerNodeEntity> rows, Predicate<BrokerNodeEntity> match) {
        return rows.stream()
                .filter(n -> n.getManagementUrl() == null || n.getUrlSource() == ManagementUrlSource.DERIVED)
                .filter(match)
                .findFirst();
    }

    /** How long a node whose derived address did not answer is left alone before it is asked again. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    /**
     * Asks, for every node of a registered cluster that has no management URL, or a derived one the pattern no
     * longer gives, the address the pattern derives for it. Called outside any transaction. A URL that is
     * already what the pattern derives has been proved and is not asked again; a seed or manual URL is never
     * asked about. A node whose address did not answer is asked again after {@link #RETRY_AFTER}; one whose
     * address refused the account or answered for another broker waits until the pattern or the account
     * changes, which clears its reason.
     */
    Map<String, UrlProof> proveUnproved(UUID clusterId, Survey survey, UrlDerivation derivation) {
        Map<String, UrlProof> proofs = new LinkedHashMap<>();
        Instant now = Instant.now();
        Set<String> known = new HashSet<>();
        for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            known.add(node.getName());
            if (needsProof(node, derivation, now)) {
                prove(proofs, derivation, node.getName(), node.getNativeNodeId());
            }
        }
        // A node the topology names for the first time has no row yet, and no seed answered for it.
        for (SeedReading r : survey.readings) {
            for (TopologyEntry e : r.entries()) {
                provePending(proofs, known, survey, derivation, e.primaryConnector(), PRIMARY, e.nodeId());
                if (e.backup() != null) {
                    provePending(proofs, known, survey, derivation, e.backup(), BACKUP, e.nodeId());
                }
            }
        }
        return proofs;
    }

    private void provePending(
            Map<String, UrlProof> proofs,
            Set<String> known,
            Survey survey,
            UrlDerivation derivation,
            String connector,
            String haDesignation,
            String nodeId) {
        boolean answered = survey.readings.stream()
                .anyMatch(r -> r.attachable()
                        && (connectorHost(connector).equals(seedHost(r.managementUrl()))
                                || (nodeId != null
                                        && nodeId.equals(r.nodeId())
                                        && haDesignation.equals(
                                                evaluator.deriveHaDesignation(r.backup(), r.clustered())))));
        if (known.add(connector) && !answered) {
            prove(proofs, derivation, connector, nodeId);
        }
    }

    private void prove(Map<String, UrlProof> proofs, UrlDerivation derivation, String connector, String nodeId) {
        UrlProof proof = prove(derivation, connector, nodeId);
        if (!proof.throttled()) {
            proofs.put(connector, proof);
        }
    }

    private static boolean needsProof(BrokerNodeEntity node, UrlDerivation derivation, Instant now) {
        if (node.getManagementUrl() != null) {
            return node.getUrlSource() == ManagementUrlSource.DERIVED
                    && (derivation.pattern() == null || !node.getManagementUrl().equals(derivedUrl(derivation, node)));
        }
        ManagementUrlProblem problem = node.getUrlProblem();
        if (problem == null || problem == ManagementUrlProblem.NO_PATTERN) {
            return true;
        }
        return switch (problem) {
            case UNREACHABLE, TLS_FAILED ->
                node.getUrlCheckedAt() == null
                        || node.getUrlCheckedAt().plus(RETRY_AFTER).isBefore(now);
            default -> false;
        };
    }

    /** Gives each node the URL its proof found, or the reason it has none; a node the proofs say nothing of is left. */
    private void applyProofs(UUID clusterId, Map<String, UrlProof> proofs) {
        for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            UrlProof proof = proofs.get(node.getName());
            boolean replaceable = node.getManagementUrl() == null || node.getUrlSource() == ManagementUrlSource.DERIVED;
            if (proof != null && replaceable && applyProof(clusterId, node, proof)) {
                nodes.save(node);
            }
        }
    }

    /** Records what one proof found on its node, and whether the node changed and needs saving. */
    private boolean applyProof(UUID clusterId, BrokerNodeEntity node, UrlProof proof) {
        if (proof.url() == null) {
            node.recordUrlProblem(proof.problem(), Instant.now());
            return true;
        }
        if (nodes.existsManagementUrlElsewhere(clusterId, proof.url(), node.getId())) {
            log.debug("Derived URL {} already belongs to another node of cluster {}", proof.url(), clusterId);
            return false;
        }
        SeedReading r = proof.reading();
        node.attachDerivedUrl(proof.url());
        node.applyHaState(
                new HaObservation(
                        r.active(),
                        evaluator.deriveState(r.started()),
                        evaluator.deriveHaDesignation(r.backup(), r.clustered()),
                        r.replicaSync(),
                        r.version(),
                        r.nodeId()),
                0L,
                Instant.now());
        return true;
    }

    private static String derivedUrl(UrlDerivation derivation, BrokerNodeEntity node) {
        return ManagementUrlPattern.derive(derivation.pattern(), connectorHost(node.getName()));
    }

    /**
     * Asks the address the pattern derives for a connector's host who answers there, with the management
     * account, in one batched HA read. The URL is proved only when the answering broker reports the node's
     * NodeID: behind a load balancer or a recycled address another broker may answer, and attaching its
     * URL would manage the wrong one.
     */
    UrlProof prove(UrlDerivation derivation, String connector, String expectedNodeId) {
        if (derivation.pattern() == null) {
            return new UrlProof(null, null, ManagementUrlProblem.NO_PATTERN, false);
        }
        String url = ManagementUrlPattern.derive(derivation.pattern(), connectorHost(connector));
        UrlProof proof = probe(derivation.settings(), url, expectedNodeId);
        if (proof.reading() == null || (proof.problem() == null && expectedNodeId != null)) {
            return proof; // nothing answered, and its reason stands; or the node itself answered
        }
        // Another broker answered, or there is no NodeID to compare it with: nothing is proved.
        return new UrlProof(null, null, ManagementUrlProblem.OTHER_BROKER, false);
    }

    /** Asks one address, with an account, which broker answers there and whether it is the one expected. */
    UrlProof probe(BrokerConnectionSettings settings, String url, String expectedNodeId) {
        try (ProbeSession session = gateway.probe(settings)) {
            SeedReading reading = readHa(url, true, session.node(url));
            if (expectedNodeId != null && !expectedNodeId.equals(reading.nodeId())) {
                return new UrlProof(url, reading, ManagementUrlProblem.OTHER_BROKER, false);
            }
            return new UrlProof(url, reading, null, false);
        } catch (BrokerConnectionException e) {
            return switch (e.kind()) {
                case THROTTLED -> new UrlProof(null, null, ManagementUrlProblem.UNREACHABLE, true);
                case CREDENTIALS_REJECTED -> new UrlProof(null, null, ManagementUrlProblem.CREDENTIALS_REJECTED, false);
                case UNREACHABLE -> new UrlProof(null, null, ManagementUrlProblem.UNREACHABLE, false);
                case TLS_FAILED -> new UrlProof(null, null, ManagementUrlProblem.TLS_FAILED, false);
                case NOT_ARTEMIS, WRONG_PATH, BAD_RESPONSE, UNSUPPORTED_VERSION ->
                    new UrlProof(null, null, ManagementUrlProblem.WRONG_ENDPOINT, false);
            };
        }
    }

    /**
     * Reads each seed on its own: one that does not answer is skipped (the scrape's
     * tier A records its error) and discovery goes on with the others. Only when none
     * answered is there nothing to discover from, and the first failure is thrown.
     */
    private static List<SeedReading> readAnswering(List<ProbedSeed> seeds) {
        List<SeedReading> readings = new ArrayList<>();
        BrokerConnectionException firstFailure = null;
        for (ProbedSeed seed : seeds) {
            try {
                readings.add(read(seed));
            } catch (BrokerConnectionException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (readings.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return readings;
    }

    private static SeedReading read(ProbedSeed seed) {
        SeedReading ha = readHa(seed.managementUrl(), seed.attachable(), seed.target());
        List<TopologyEntry> entries = new ArrayList<>();
        for (ClusterMember m : seed.target().members()) {
            entries.add(new TopologyEntry(
                    m.nativeId().isEmpty() ? null : m.nativeId(),
                    connector(m, "artemis.live_connector"),
                    connector(m, "artemis.primary_connector"),
                    connector(m, "artemis.backup_connector")));
        }
        return ha.withEntries(entries);
    }

    private static String connector(ClusterMember member, String attribute) {
        return member.attributes().raw(attribute).map(Object::toString).orElse(null);
    }

    /** One batched read of the HA attributes, with no topology: all a node's URL has to prove. */
    private static SeedReading readHa(String managementUrl, boolean attachable, BrokerTarget target) {
        NodeDescription ha;
        try {
            ha = target.describe();
        } catch (BrokerConnectionException e) {
            throw BrokerFailures.answeredError(e)
                    .map(error -> new BrokerConnectionException(
                            BrokerConnectionException.Kind.BAD_RESPONSE, "HA read failed: " + error))
                    .orElse(e);
        }
        Attributes a = ha.attributes();
        return new SeedReading(
                managementUrl,
                attachable,
                ha.nativeId().isEmpty() ? null : ha.nativeId(),
                flag(a, "artemis.backup").orElse(false),
                flag(a, "artemis.started").orElse(null),
                flag(a, "artemis.active").orElse(false),
                flag(a, "artemis.replica_sync").orElse(null),
                ha.version().orElse(null),
                flag(a, "artemis.clustered").orElse(null),
                List.of());
    }

    private static Optional<Boolean> flag(Attributes attributes, String name) {
        return attributes.raw(name).map(v -> (Boolean) v);
    }

    private static String seedHost(String managementUrl) {
        try {
            return URI.create(managementUrl).getHost();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String connectorHost(String connector) {
        return ManagementUrlPattern.connectorHost(connector);
    }

    private static String seedName(String managementUrl) {
        try {
            URI u = URI.create(managementUrl);
            int port = u.getPort();
            return port > 0 ? u.getHost() + ":" + port : u.getHost();
        } catch (RuntimeException _) {
            return managementUrl;
        }
    }
}
