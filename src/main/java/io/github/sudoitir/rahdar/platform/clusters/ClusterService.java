package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.replica.RahdarBus;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ScopedGrants;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilityProbe;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.DataPlaneCheck;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlSource;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.broker.ProbeSession;
import io.github.sudoitir.rahdar.platform.broker.SubscriptionVerdict;
import io.github.sudoitir.rahdar.platform.broker.VersionGate;
import io.github.sudoitir.rahdar.platform.clusters.TopologyDiscovery.ProbedSeed;
import io.github.sudoitir.rahdar.platform.clusters.TopologyDiscovery.UrlDerivation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerTlsEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerTlsRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.EnvironmentRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.AccountUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AdoptionCountsView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AdoptionPreviewView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterConnectionDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterConnectionView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterSummary;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.HealthView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeEndpointView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeProbeView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RegisterPreview;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.TopologyView;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates cluster registration, topology, capabilities, and health.
 *
 * <p>Every mutating method opens its transaction, writes a {@code PENDING} audit
 * row, then commits with the audit row set to {@code SUCCESS} or {@code FAILURE}
 * (non-negotiable #3). A broker that cannot be reached is a {@link Attempt.Failed}
 * return, not a rollback — the audit trail keeps the failed attempt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterService {

    private static final String MANAGEMENT = "MANAGEMENT";
    private static final String DATA = "DATA";
    private static final String REGISTER_CLUSTER = "REGISTER_CLUSTER";
    private static final String UPDATE_CONNECTION = "UPDATE_CLUSTER_CONNECTION";
    private static final String CLUSTER = "CLUSTER";
    private static final String SEED_URLS = "seedUrls";
    private static final UUID UNBOUND = new UUID(0L, 0L);

    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;
    private final BrokerCredentialRepository credentials;
    private final BrokerTlsRepository tlsRepository;
    private final BrokerIdentityClaims identityClaims;
    private final ClusterIdentityClaims clusterClaims;

    private final BrokerGateway gateway;
    private final ClusterKinds clusterKinds;
    private final BrokerConnections connections;
    private final List<RegistrationCheckContributor> checkContributors;
    private final BrokerCapabilityProbe capabilityProbe;
    private final CapabilityAssessments capabilityAssessments;
    private final TopologyDiscovery topologyDiscovery;
    private final HaStateEvaluator evaluator;
    private final BrokerNotifications coreSubscriptions;
    private final DataPlaneCheck dataPlaneCheck;
    private final SeedExpander seedExpander;
    private final Optional<RegistrationAdoption> adoption;
    private final EnvironmentRepository environments;
    private final RahdarBus bus;
    private final SecretVault vault;
    private final AuditService audit;
    private final ApplicationEventPublisher eventPublisher;
    private final ActorResolver actorResolver;
    private final ClusterEnvironmentIndex environmentIndex;
    private final ClusterAccessGuard clusterAccess;
    private final TransactionTemplate transactions;
    private final PermissionResolver permissions;
    private final ScopedGrants grants;

    private final BrokerNodeMapper nodeMapper;
    private final ClusterViewMapper viewMapper;
    private final OperationGate gate;

    /**
     * What the gate holds of a connection edit: the request with the Core account's {@code null}, which clears it,
     * as {@code clearCore}. Both accounts' passwords are redacted for those who read it.
     */
    record ClusterUpdateParams(
            UUID clusterId,
            String name,
            String description,
            List<String> seedUrls,
            String managementUrlPattern,
            String tlsBundle,
            AccountUpdate management,
            AccountUpdate core,
            boolean clearCore) {

        UpdateClusterRequest toRequest() {
            return new UpdateClusterRequest(
                    name,
                    description,
                    seedUrls,
                    managementUrlPattern,
                    tlsBundle,
                    management,
                    clearCore ? AccountUpdate.CLEAR : core);
        }
    }

    /** What the gate holds of a cluster delete. */
    record ClusterDeleteParams(UUID clusterId) {}

    /** What the gate holds of a node's URL override: the URLs it sets, either of which may be absent. */
    record NodeOverrideParams(UUID clusterId, UUID nodeId, String managementUrl, String dataUrl) {

        NodeOverrideRequest toRequest() {
            return new NodeOverrideRequest(managementUrl, dataUrl);
        }
    }

    private record Probe(
            String url, boolean attachable, BrokerTarget target, String version, BrokerConnectionException error) {
        boolean ok() {
            return error == null;
        }

        ProbedSeed asSeed() {
            return new ProbedSeed(url, target, attachable);
        }
    }

    /** What a check or a registration probes with: the seeds, the pattern, and both accounts. */
    private record ConnectionInputs(
            List<String> seedUrls, String pattern, BrokerConnectionSettings management, CoreConnectionSettings core) {

        UrlDerivation derivation() {
            return new UrlDerivation(pattern, management);
        }
    }

    // ---- connection check (?dryRun=true) -------------------------------------

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.platform.clusters.ClusterPermissions).CLUSTER_WRITE)")
    public Attempt<RegisterPreview> checkConnection(RegisterClusterRequest request) {
        ConnectionInputs inputs = inputsOf(request);
        try (ProbeSession session = gateway.probe(inputs.management())) {
            return checkConnection(request, inputs, session);
        }
    }

    private Attempt<RegisterPreview> checkConnection(
            RegisterClusterRequest request, ConnectionInputs inputs, ProbeSession session) {
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                REGISTER_CLUSTER,
                CLUSTER,
                request.name(),
                null,
                null,
                Map.of(SEED_URLS, request.seedUrls()),
                true);

        List<Probe> probes = connectAll(inputs, session);
        List<Probe> reachable = probes.stream().filter(Probe::ok).toList();
        if (reachable.isEmpty()) {
            return failed(event, probes.get(0).error());
        }
        BrokerConnectionException tooOld = belowMinimum(reachable);
        if (tooOld != null) {
            return failed(event, tooOld);
        }

        TopologyDiscovery.Survey survey =
                topologyDiscovery.survey(reachable.stream().map(Probe::asSeed).toList());
        refuseIfRegistered(survey.identity(), () -> event);
        TopologyDiscovery.Preview found = topologyDiscovery.preview(survey, inputs.derivation());
        ClusterTopology preview = found.topology();
        List<NodeEndpoint> endpoints = endpoints(preview);
        // One subscription per node rather than reporting NotAttempted: it says both whether the broker lets the
        // Core account in on each node and whether it may subscribe to notifications.
        DataPlaneCheck.Result dataPlane = dataPlaneCheck.check(endpoints, inputs.core());
        List<AccountResult> coreResults = dataPlane.accounts();
        SubscriptionVerdict coreVerdict = dataPlane.verdict();
        BrokerCapabilities capabilities = capabilityProbe.probe(reachable.get(0).target(), coreVerdict);
        int nodeCount =
                (int) endpoints.stream().map(NodeEndpoint::name).distinct().count();

        Map<String, Object> contributions = new TreeMap<>();
        for (RegistrationCheckContributor contributor : checkContributors) {
            contributions.put(
                    contributor.featureId(),
                    contributor.contribute(
                            capabilities,
                            reachable.get(0).target(),
                            coreSettingsFrom(request).username()));
        }

        audit.succeed(event, nodeCount);
        return new Attempt.Ok<>(new RegisterPreview(
                viewMapper.capabilities(capabilities, VersionGate.assessAll(endpoints)),
                reachable.size(),
                nodeCount,
                viewMapper.topology(preview),
                inputs.pattern(),
                probeRows(endpoints, found.management(), coreResults),
                adoptionPreview(endpoints, session),
                contributions));
    }

    private ConnectionInputs inputsOf(RegisterClusterRequest request) {
        String pattern = request.managementUrlPattern() != null
                        && !request.managementUrlPattern().isBlank()
                ? request.managementUrlPattern()
                : ManagementUrlPattern.defaultFor(request.seedUrls().get(0));
        return new ConnectionInputs(
                request.seedUrls(),
                pattern,
                new BrokerConnectionSettings(
                        UNBOUND,
                        request.hasCredentials() ? request.credentials().username() : null,
                        request.hasCredentials() ? request.credentials().password() : null,
                        request.tlsBundle(),
                        true),
                coreSettingsFrom(request));
    }

    /**
     * Core settings straight from an unregistered request: the Core credentials if
     * given, otherwise the management ones, matching the fallback
     * {@code BrokerConnections.coreSettingsFor} applies once the cluster exists
     * (ADR-0026, D6). Nothing is persisted or sealed — there is no cluster to key
     * the vault by yet.
     */
    private static CoreConnectionSettings coreSettingsFrom(RegisterClusterRequest request) {
        RegisterClusterRequest.Credentials core =
                request.hasCoreCredentials() ? request.coreCredentials() : request.credentials();
        return core == null
                ? new CoreConnectionSettings(null, null, null, request.tlsBundle(), true)
                : new CoreConnectionSettings(null, core.username(), core.password(), request.tlsBundle(), true);
    }

    private static List<NodeProbeView> probeRows(
            List<NodeEndpoint> endpoints, Map<String, AccountResult> management, List<AccountResult> core) {
        List<NodeProbeView> rows = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            NodeEndpoint e = endpoints.get(i);
            rows.add(new NodeProbeView(
                    e.name(),
                    e.haDesignation(),
                    e.nativeNodeId(),
                    e.version(),
                    e.managementUrl(),
                    e.urlSource(),
                    e.urlProblem(),
                    management.getOrDefault(e.name(), AccountResult.NOT_TRIED),
                    core.get(i)));
        }
        return rows;
    }

    /** What adopting the running configuration would declare, read from the live nodes the check reached. */
    private AdoptionPreviewView adoptionPreview(List<NodeEndpoint> endpoints, ProbeSession session) {
        return adoption.flatMap(adopter -> adopter.preview(endpoints.stream()
                        .filter(e -> e.active() && e.managementUrl() != null)
                        .map(e -> new RegistrationAdoption.LiveNode(e.name(), session.node(e.managementUrl())))
                        .toList()))
                .map(p -> new AdoptionPreviewView(
                        new AdoptionCountsView(
                                p.counts().addresses(),
                                p.counts().addressSettings(),
                                p.counts().securitySettings(),
                                p.counts().diverts()),
                        p.disagreements(),
                        p.notes()))
                .orElse(null);
    }

    // ---- registration -------------------------------------------------------

    /**
     * Registers a cluster. What asks a broker for its management URL (the seeds, the topology, each derived
     * address), the capability probe and the adoption's reads happen before the transaction opens, and the rows
     * are saved in one transaction (ADR-0078).
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.platform.clusters.ClusterPermissions).CLUSTER_WRITE)")
    public Attempt<ClusterDetail> register(RegisterClusterRequest request) {
        if (request.adopts() && adoption.isEmpty()) {
            throw new ConflictException(
                    "adoption-unavailable",
                    "Adopting the running configuration needs the broker configuration feature, which is off.");
        }
        if (request.environmentId() != null && !environments.existsById(request.environmentId())) {
            throw new NotFoundException("environment", request.environmentId());
        }
        return gatedRegister(request);
    }

    @Gated("cluster.register")
    private Attempt<ClusterDetail> gatedRegister(RegisterClusterRequest request) {
        return gate.run(Operation.of(request), () -> registerNow(request));
    }

    private Attempt<ClusterDetail> registerNow(RegisterClusterRequest request) {
        ConnectionInputs inputs = inputsOf(request);
        try (ProbeSession session = gateway.probe(inputs.management())) {
            return registerNow(request, inputs, session);
        }
    }

    private Attempt<ClusterDetail> registerNow(
            RegisterClusterRequest request, ConnectionInputs inputs, ProbeSession session) {
        List<Probe> probes = connectAll(inputs, session);
        List<Probe> reachable = probes.stream().filter(Probe::ok).toList();
        if (reachable.isEmpty()) {
            AuditEvent event = audit.begin(
                    actorResolver.resolve(), REGISTER_CLUSTER, CLUSTER, request.name(), null, null, Map.of(), false);
            return failed(event, probes.get(0).error());
        }
        BrokerConnectionException tooOld = belowMinimum(reachable);
        if (tooOld != null) {
            AuditEvent event = audit.begin(
                    actorResolver.resolve(),
                    REGISTER_CLUSTER,
                    CLUSTER,
                    request.name(),
                    null,
                    null,
                    Map.of(SEED_URLS, request.seedUrls()),
                    false);
            return failed(event, tooOld);
        }

        TopologyDiscovery.Survey survey =
                topologyDiscovery.survey(reachable.stream().map(Probe::asSeed).toList());
        ClusterIdentity identity = survey.identity();
        refuseIfRegistered(identity, () -> refusedAttempt(request));
        TopologyDiscovery.Preview found = topologyDiscovery.preview(survey, inputs.derivation());

        // A cluster not yet registered has no Core subscription and no recorded write, so this is the whole
        // assessment; it is read here so that no broker call is made inside the registration transaction.
        BrokerCapabilities capabilities =
                capabilityProbe.probe(reachable.get(0).target(), new SubscriptionVerdict.NotAttempted());
        RegistrationAdoption.Reading adopted = request.adopts() ? readAdoption(found, session) : null;
        return transactions.execute(status -> saveRegistration(request, inputs, survey, found, capabilities, adopted));
    }

    /** What the live nodes run, read before the registration transaction opens (ADR-0176). */
    private RegistrationAdoption.Reading readAdoption(TopologyDiscovery.Preview found, ProbeSession session) {
        return adoption.orElseThrow()
                .read(endpoints(found.topology()).stream()
                        .filter(e -> e.active() && e.managementUrl() != null)
                        .map(e -> new RegistrationAdoption.LiveNode(e.name(), session.node(e.managementUrl())))
                        .toList())
                .orElseThrow(() -> new ConflictException(
                        "no-live-node", "No live node could be read, so there is nothing to adopt."));
    }

    private Attempt<ClusterDetail> saveRegistration(
            RegisterClusterRequest request,
            ConnectionInputs inputs,
            TopologyDiscovery.Survey survey,
            TopologyDiscovery.Preview found,
            BrokerCapabilities capabilities,
            RegistrationAdoption.Reading adopted) {
        ClusterIdentity identity = survey.identity();
        ClusterEntity cluster = new ClusterEntity(
                ArtemisModule.SYSTEM,
                request.name() != null
                        ? request.name()
                        : hostOf(request.seedUrls().get(0)),
                request.description(),
                request.environmentId());
        cluster.edit(cluster.getName(), cluster.getDescription(), inputs.pattern());
        cluster = clusters.saveAndFlush(cluster);
        UUID clusterId = cluster.getId();

        // The check above sees committed clusters only. The claim settles two registrations of the same
        // brokers running at once: it waits for the other to commit, then finds the brokers taken, and
        // throwing rolls this cluster back (ADR-0167).
        refuseIfClaimed(clusterId, identity, request);

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                REGISTER_CLUSTER,
                CLUSTER,
                cluster.getName(),
                clusterId,
                null,
                Map.of(SEED_URLS, request.seedUrls(), "adopt", request.adopts()),
                false);

        if (request.hasCredentials()) {
            credentials.save(new BrokerCredentialEntity(
                    clusterId,
                    MANAGEMENT,
                    request.credentials().username(),
                    vault.seal(
                            SecretVault.aad(clusterId, MANAGEMENT),
                            request.credentials().password())));
        }
        if (request.hasCoreCredentials()) {
            // A DATA row is a genuinely separate sealed secret — AAD is clusterId|CORE
            // (ADR-0026). When absent, coreSettingsFor falls back to the Jolokia credential.
            credentials.save(new BrokerCredentialEntity(
                    clusterId,
                    DATA,
                    request.coreCredentials().username(),
                    vault.seal(
                            SecretVault.aad(clusterId, DATA),
                            request.coreCredentials().password())));
        }
        if (request.tlsBundle() != null) {
            tlsRepository.save(new BrokerTlsEntity(clusterId, request.tlsBundle(), null, true));
        }

        ClusterTopology topology = topologyDiscovery.discover(clusterId, survey, found.proofs());
        if (request.adopts()) {
            adoption.orElseThrow().adopt(clusterId, adopted);
        }
        eventPublisher.publishEvent(new ClusterRegistered(clusterId));
        environmentIndex.invalidate();

        audit.succeed(event, endpointCount(topology));
        return new Attempt.Ok<>(new ClusterDetail(
                clusterId,
                cluster.getName(),
                cluster.getDescription(),
                viewMapper.topology(topology),
                viewMapper.capabilities(capabilities, VersionGate.assessAll(endpoints(topology))),
                viewMapper.health(healthOf(clusterId, topology.nodes())),
                cluster.getEnvironmentId(),
                connectionView(clusterId, cluster),
                clusterKinds.systemOf(clusterId),
                clusterKinds.kindsOf(clusterId)));
    }

    // ---- one registration per set of brokers (ADR-0167) ---------------------

    /** Refuses brokers a registered cluster already holds, recording the refusal on the attempt's audit row. */
    private void refuseIfRegistered(ClusterIdentity identity, Supplier<AuditEvent> attempt) {
        Optional<ClusterIdentity.Overlap> overlap = registeredOverlap(identity);
        if (overlap.isPresent()) {
            throw refusal(overlap.get(), attempt.get());
        }
    }

    /**
     * Claims the brokers for the new cluster, or refuses them (ADR-0167). Another cluster's claim with no
     * node behind it any more (its URL was overridden, its broker's journal replaced) is stale: it is
     * released and the claim made again, so it never refuses an unrelated broker. A claim backed by a
     * node is refused; that is how the second of two racing registrations ends.
     */
    private void refuseIfClaimed(UUID clusterId, ClusterIdentity identity, RegisterClusterRequest request) {
        Optional<UUID> holder = identityClaims.claim(clusterId, identity.claims());
        if (holder.isEmpty()) {
            return;
        }
        Optional<ClusterIdentity.Overlap> overlap = registeredOverlap(identity);
        if (overlap.isEmpty()) {
            identityClaims.releaseHeldByOthers(clusterId, identity.claims());
            holder = identityClaims.claim(clusterId, identity.claims());
            if (holder.isEmpty()) {
                return;
            }
            overlap = registeredOverlap(identity);
        }
        UUID holderId = holder.get();
        throw refusal(
                overlap.orElseGet(() -> new ClusterIdentity.Overlap(
                        holderId,
                        clusters.findById(holderId).map(ClusterEntity::getName).orElse(""),
                        List.of())),
                refusedAttempt(request));
    }

    /** The registered cluster holding any of these brokers, by NodeID or by a management URL's normal form. */
    private Optional<ClusterIdentity.Overlap> registeredOverlap(ClusterIdentity identity) {
        List<BrokerNodeEntity> candidates = new ArrayList<>(nodes.findManaged());
        if (!identity.nodeIds().isEmpty()) {
            candidates.addAll(nodes.findByNativeNodeIdIn(identity.nodeIds()));
        }
        Map<UUID, String> names = new HashMap<>();
        clusters.findAllById(candidates.stream()
                        .map(BrokerNodeEntity::getClusterId)
                        .distinct()
                        .toList())
                .forEach(c -> names.put(c.getId(), c.getName()));
        return identity.overlapWith(candidates, names);
    }

    /**
     * The refusal for an overlap. The caller is told which cluster only when they may read it (the
     * authorization spec keeps a cluster hidden from anyone with no grant on it), and the audit row says
     * exactly what the caller was told: the row belongs to no cluster, so no cluster's read grant guards
     * it, and it must not name a cluster its actor could not see. Its seed URLs still let an administrator
     * find the holder.
     */
    private ClusterAlreadyRegisteredException refusal(ClusterIdentity.Overlap overlap, AuditEvent attempt) {
        ClusterAlreadyRegisteredException refusal = permissions.can(overlap.clusterId(), Permissions.CLUSTER_READ)
                ? new ClusterAlreadyRegisteredException(overlap.clusterId(), overlap.clusterName(), overlap.nodes())
                : ClusterAlreadyRegisteredException.hidden();
        audit.fail(attempt, refusal.getMessage());
        return refusal;
    }

    /** The audit row of a registration refused before it created a cluster. */
    private AuditEvent refusedAttempt(RegisterClusterRequest request) {
        return audit.begin(
                actorResolver.resolve(),
                REGISTER_CLUSTER,
                CLUSTER,
                request.name(),
                null,
                null,
                Map.of(SEED_URLS, request.seedUrls()),
                false);
    }

    // ---- reads ------------------------------------------------------------

    @PostFilter("@perm.canSeeCluster(filterObject.id())")
    @Transactional(readOnly = true)
    public List<ClusterSummary> list() {
        List<ClusterSummary> out = new ArrayList<>();
        for (ClusterEntity c : clusters.findAllByOrderByNameAsc()) {
            List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(c.getId());
            var logical = evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), SplitBrainStatus.byNodeId(rows));
            var health = healthOf(c.getId(), logical);
            out.add(new ClusterSummary(
                    c.getId(),
                    c.getName(),
                    c.getDescription(),
                    health.level(),
                    rows.size(),
                    c.getUpdatedAt(),
                    c.getEnvironmentId(),
                    clusterKinds.systemOf(c.getId()),
                    clusterKinds.kindsOf(c.getId())));
        }
        return out;
    }

    public ClusterDetail get(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        requireCluster(clusterId);
        // The probe is shared and runs on its own thread; no transaction (and no connection) is open while it is
        // awaited.
        CapabilityAssessments.Outcome assessed = capabilityAssessments.outcome(clusterId);
        return transactions.execute(status -> {
            ClusterEntity cluster = requireCluster(clusterId);
            ClusterTopology topology = topologyDiscovery.currentTopology(clusterId);
            return new ClusterDetail(
                    clusterId,
                    cluster.getName(),
                    cluster.getDescription(),
                    withheld(clusterId, viewMapper.topology(topology)),
                    capabilitiesFor(clusterId, topology, assessed),
                    viewMapper.health(healthOf(clusterId, topology.nodes())),
                    cluster.getEnvironmentId(),
                    clusterAccess.holds(clusterId, Permissions.CLUSTER_READ)
                            ? connectionView(clusterId, cluster)
                            : null,
                    clusterKinds.systemOf(clusterId),
                    clusterKinds.kindsOf(clusterId));
        });
    }

    /**
     * How Rahdar reaches the cluster, for the connection settings: what the operator gave, and the account names.
     * Never a password.
     */
    private ClusterConnectionDetail connectionView(UUID clusterId, ClusterEntity cluster) {
        List<String> seeds = nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> n.getUrlSource() == ManagementUrlSource.SEED)
                .map(BrokerNodeEntity::getManagementUrl)
                .toList();
        BrokerTlsEntity tls = tlsRepository.findByClusterId(clusterId).orElse(null);
        return new ClusterConnectionDetail(
                cluster.getManagementUrlPattern(),
                seeds,
                tls == null ? null : tls.getTruststoreRef(),
                credentials
                        .findByClusterIdAndKind(clusterId, MANAGEMENT)
                        .map(BrokerCredentialEntity::getUsername)
                        .orElse(null),
                credentials
                        .findByClusterIdAndKind(clusterId, DATA)
                        .map(BrokerCredentialEntity::getUsername)
                        .orElse(null));
    }

    /**
     * The cluster's health, naming the accounts a broker refused. A management rejection is on the node, from
     * its last scrape; a Core one is held by this replica's subscriptions, so another replica does not see it.
     */
    private ClusterHealth healthOf(UUID clusterId, List<LogicalNode> logical) {
        Map<UUID, SubscriptionVerdict.NodeState> states = coreSubscriptions.nodeStates();
        Set<UUID> coreRejected = logical.stream()
                .flatMap(n -> n.endpoints().stream())
                .map(NodeEndpoint::id)
                .filter(id -> states.get(id) instanceof SubscriptionVerdict.NodeState.Failed failed
                        && failed.kind() == SubscriptionVerdict.Kind.UNAUTHORIZED)
                .collect(Collectors.toSet());
        return evaluator.toHealth(clusterId, logical, coreRejected);
    }

    /**
     * Where a node is reached, and what went wrong reaching it, is shown to a caller who reads the cluster. One who
     * sees it only through a team's queues is shown the nodes without those details.
     */
    private TopologyView withheld(UUID clusterId, TopologyView topology) {
        return clusterAccess.holds(clusterId, Permissions.CLUSTER_READ)
                ? topology
                : topology.withoutConnectionDetails();
    }

    /**
     * The capabilities, from the cluster's shared assessment. For a caller who reads the cluster the reasons say what was tried and where;
     * for one who does not, the statuses alone, and a probe that could not connect says only that.
     */
    private CapabilitiesView capabilitiesFor(
            UUID clusterId, ClusterTopology topology, CapabilityAssessments.Outcome assessed) {
        boolean wholeCluster = clusterAccess.holds(clusterId, Permissions.CLUSTER_READ);
        try {
            CapabilitiesView view =
                    viewMapper.capabilities(assessed.orThrow(), VersionGate.assessAll(endpoints(topology)));
            return wholeCluster ? view : view.withoutConnectionDetails();
        } catch (BrokerConnectionException e) {
            if (wholeCluster) {
                throw e;
            }
            throw new BrokerConnectionException(e.kind(), "The cluster's brokers could not be reached.");
        }
    }

    @Transactional(readOnly = true)
    public TopologyView topology(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        requireCluster(clusterId);
        return withheld(clusterId, viewMapper.topology(topologyDiscovery.currentTopology(clusterId)));
    }

    @Transactional(readOnly = true)
    public HealthView health(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        requireCluster(clusterId);
        ClusterTopology topology = topologyDiscovery.currentTopology(clusterId);
        return viewMapper.health(healthOf(clusterId, topology.nodes()));
    }

    /**
     * The cluster's shared capability assessment (a probe of its first manageable node, at most one per
     * {@link CapabilityAssessments#TTL}), with the recorded management-write evidence overlaid (ADR-0049 D5).
     * The probe itself never writes, so without that overlay the write capability could only ever be unknown.
     */
    public CapabilitiesView capabilities(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        requireCluster(clusterId);
        CapabilityAssessments.Outcome assessed = capabilityAssessments.outcome(clusterId);
        return transactions.execute(
                status -> capabilitiesFor(clusterId, topologyDiscovery.currentTopology(clusterId), assessed));
    }

    /** The same assessment as {@link #capabilities}, before it becomes a DTO. */
    public BrokerCapabilities brokerCapabilities(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        requireCluster(clusterId);
        return capabilityAssessments.outcome(clusterId).orThrow();
    }

    // ---- mutations ------------------------------------------------------------

    /**
     * Re-run discovery from every manageable node of a cluster, so a broker that joined
     * after registration appears on its own (ADR-0004, ADR-0119), and so a node without a
     * management URL gets one derived from the cluster's pattern (ADR-0175). Called by the scrape
     * scheduler's discovery tier: a system operation, with no permission check and no
     * audit event per tick, like the tiers' own writes. Discovery reads each seed on its
     * own, so a node that does not answer is skipped this round (tier A records its
     * error) and the others still count.
     */
    public void rediscover(UUID clusterId) {
        rediscover(clusterId, List.of());
    }

    private void rediscover(UUID clusterId, List<SeedExpander.Seed> extraSeeds) {
        Optional<ClusterEntity> cluster = clusters.findById(clusterId);
        if (cluster.isEmpty()) {
            return;
        }
        try (ProbeSession session = gateway.probe(clusterId)) {
            List<ProbedSeed> seeds = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
                if (node.getManagementUrl() != null && seen.add(node.getManagementUrl())) {
                    seeds.add(new ProbedSeed(node.getManagementUrl(), session.node(node.getManagementUrl())));
                }
            }
            for (SeedExpander.Seed seed : extraSeeds) {
                if (seen.add(seed.url())) {
                    seeds.add(new ProbedSeed(seed.url(), session.node(seed.url()), seed.attachable()));
                }
            }
            if (!seeds.isEmpty()) {
                topologyDiscovery.discover(
                        clusterId,
                        seeds,
                        new UrlDerivation(cluster.get().getManagementUrlPattern(), connections.settingsFor(clusterId)));
            }
        }
    }

    /**
     * Point Rahdar at a node by hand: its management (Jolokia) URL, its Core URL or both. It decides where Rahdar
     * sends the cluster's credentials, so it passes the approval gate as {@code cluster.node-override}. A new
     * management URL must answer as an Artemis broker before it is saved.
     */
    public Attempt<NodeEndpointView> overrideNodeUrl(UUID clusterId, UUID nodeId, NodeOverrideRequest request) {
        clusterAccess.requireCluster(clusterId, ClusterPermissions.CLUSTER_WRITE);
        requireCluster(clusterId);
        BrokerNodeEntity node = requireNode(clusterId, nodeId);
        if (request.hasJolokiaUrl() && nodes.existsManagementUrlElsewhere(clusterId, request.jolokiaUrl(), nodeId)) {
            throw new ConflictException(
                    "duplicate-node-url", "Another node of this cluster already uses " + request.jolokiaUrl() + ".");
        }
        return gatedOverride(
                new NodeOverrideParams(
                        clusterId,
                        nodeId,
                        request.hasJolokiaUrl() ? request.jolokiaUrl() : null,
                        request.hasCoreUrl() ? request.coreUrl() : null),
                node.getName(),
                request);
    }

    @Gated("cluster.node-override")
    private Attempt<NodeEndpointView> gatedOverride(
            NodeOverrideParams params, String nodeName, NodeOverrideRequest request) {
        return gate.run(
                Operation.of(params), () -> overrideNow(params.clusterId(), params.nodeId(), nodeName, request));
    }

    private Attempt<NodeEndpointView> overrideNow(
            UUID clusterId, UUID nodeId, String nodeName, NodeOverrideRequest request) {
        Map<String, Object> params = new HashMap<>();
        if (request.hasJolokiaUrl()) {
            params.put("jolokiaUrl", request.jolokiaUrl());
        }
        if (request.hasCoreUrl()) {
            params.put("coreUrl", request.coreUrl());
        }
        AuditEvent event = audit.begin(
                actorResolver.resolve(), "OVERRIDE_NODE_URL", "NODE", nodeName, clusterId, nodeId, params, false);

        if (request.hasJolokiaUrl()) {
            // The address must reach a broker before Rahdar sends the cluster's credentials there for good.
            try (ProbeSession session = gateway.probe(clusterId)) {
                session.node(request.jolokiaUrl()).describe();
            } catch (BrokerConnectionException e) {
                return failed(event, e);
            }
        }
        BrokerNodeEntity saved;
        try {
            saved = transactions.execute(status -> {
                BrokerNodeEntity node = requireNode(clusterId, nodeId);
                if (request.hasJolokiaUrl()) {
                    node.applyManualUrl(request.jolokiaUrl());
                }
                if (request.hasCoreUrl()) {
                    node.applyManualDataUrl(request.coreUrl());
                }
                BrokerNodeEntity result = nodes.save(node);
                // A node known only by its URL is claimed by it: the old URL is released, the new one claimed.
                clusterClaims.sync(clusterId);
                return result;
            });
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
        capabilityAssessments.invalidate(clusterId);
        audit.succeed(event, 1);
        return new Attempt.Ok<>(viewMapper.endpoint(nodeMapper.toEndpoint(saved)));
    }

    private BrokerNodeEntity requireNode(UUID clusterId, UUID nodeId) {
        return nodes.findById(nodeId)
                .filter(n -> n.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Node", nodeId));
    }

    // ---- connection edit (PATCH /clusters/{id}) ---------------------------------

    /** An account's user name and password; both {@code null} for a Core account that falls back. */
    private record Account(String username, String password) {}

    /** The connection as it would be after a request: the stored values with the request laid over them. */
    private record Edited(
            String name,
            String description,
            String pattern,
            String tlsBundle,
            List<String> seedUrls,
            Account management,
            Account core,
            boolean managementSupplied,
            boolean retryProblems) {

        BrokerConnectionSettings managementSettings(UUID clusterId) {
            return new BrokerConnectionSettings(
                    clusterId, management.username(), management.password(), tlsBundle, true);
        }

        /** The Core account, or the management account while none is set (ADR-0026, D6). */
        CoreConnectionSettings coreSettings(UUID clusterId) {
            Account effective = core != null ? core : management;
            return new CoreConnectionSettings(clusterId, effective.username(), effective.password(), tlsBundle, true);
        }
    }

    /**
     * Check an edited connection without saving it: the same per-node probes as a registration check, run with
     * the request's values over the stored ones. A password left empty is the stored one, so changing only a
     * pattern is checked with the accounts the cluster already uses.
     */
    public ConnectionCheck checkUpdate(UUID clusterId, UpdateClusterRequest request) {
        clusterAccess.requireCluster(clusterId, ClusterPermissions.CLUSTER_WRITE);
        ClusterEntity cluster = requireCluster(clusterId);
        Edited edited = merge(clusterId, cluster, request);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                UPDATE_CONNECTION,
                CLUSTER,
                cluster.getName(),
                clusterId,
                null,
                auditParams(edited, request),
                true);

        BrokerConnectionSettings management = edited.managementSettings(clusterId);
        UrlDerivation derivation = new UrlDerivation(edited.pattern(), management);
        List<BrokerNodeEntity> stored = nodes.findByClusterIdOrderByNameAsc(clusterId);
        List<NodeEndpoint> endpoints = nodeMapper.toEndpoints(stored);
        List<AccountResult> core =
                dataPlaneCheck.check(endpoints, edited.coreSettings(clusterId)).accounts();

        List<NodeProbeView> rows = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            rows.add(storedRow(stored.get(i), derivation, core.get(i)));
        }
        Set<String> known =
                stored.stream().map(BrokerNodeEntity::getManagementUrl).collect(Collectors.toSet());
        Set<String> knownHosts = knownHostPorts(stored);
        for (String url : edited.seedUrls()) {
            if (!known.contains(url)) {
                requireSuppliedForNewHost(edited, url, knownHosts);
                rows.add(seedRow(url, management));
            }
        }
        audit.succeed(event, rows.size());
        return new ConnectionCheck(edited.pattern(), rows);
    }

    /**
     * One stored node as the edited connection would find it. A seed or manual URL stays as it is and is asked
     * again with the new account; a derived one, or none, is derived afresh from the pattern, as discovery would.
     */
    private NodeProbeView storedRow(BrokerNodeEntity node, UrlDerivation derivation, AccountResult core) {
        boolean fixed = node.getManagementUrl() != null && node.getUrlSource() != ManagementUrlSource.DERIVED;
        TopologyDiscovery.UrlProof proof = fixed
                ? topologyDiscovery.probe(derivation.settings(), node.getManagementUrl(), node.getNativeNodeId())
                : topologyDiscovery.prove(derivation, node.getName(), node.getNativeNodeId());
        boolean derived = !fixed && proof.url() != null;
        ManagementUrlSource source = derived ? ManagementUrlSource.DERIVED : null;
        return new NodeProbeView(
                node.getName(),
                node.getHaDesignation(),
                node.getNativeNodeId(),
                proof.version() != null ? proof.version() : node.getVersion(),
                fixed ? node.getManagementUrl() : proof.url(),
                fixed ? node.getUrlSource() : source,
                fixed || derived ? null : proof.problem(),
                proof.management(),
                core);
    }

    /** A seed the request adds, asked on its own: it is a new address, so no stored node says what it should answer. */
    private NodeProbeView seedRow(String url, BrokerConnectionSettings management) {
        TopologyDiscovery.UrlProof proof = topologyDiscovery.probe(management, url, null);
        return new NodeProbeView(
                hostOf(url),
                proof.haDesignation() != null ? proof.haDesignation() : "NONE",
                proof.nodeId(),
                proof.version(),
                url,
                ManagementUrlSource.SEED,
                null,
                proof.management(),
                AccountResult.NOT_TRIED);
    }

    /**
     * Change a cluster's connection: name, description, seeds, management URL pattern, TLS bundle
     * and both accounts, in one audited transaction with the secrets redacted. New secrets are sealed
     * and never returned. Discovery then runs at once, so the nodes' URLs follow a changed pattern or seed
     * without waiting for the next tick; a broker that does not answer then does not undo the save.
     */
    public ClusterConnectionView updateConnection(UUID clusterId, UpdateClusterRequest request) {
        clusterAccess.requireCluster(clusterId, ClusterPermissions.CLUSTER_WRITE);
        ClusterEntity cluster = requireCluster(clusterId);
        Edited edited = merge(clusterId, cluster, request);
        Set<String> knownHosts = knownHostPorts(nodes.findByClusterIdOrderByNameAsc(clusterId));
        edited.seedUrls().forEach(url -> requireSuppliedForNewHost(edited, url, knownHosts));
        return gatedUpdate(updateParams(clusterId, request), cluster, edited, request);
    }

    /** The request as the gate holds it: the Core account's {@code null}, which clears it, as a flag. */
    private static ClusterUpdateParams updateParams(UUID clusterId, UpdateClusterRequest request) {
        boolean clearCore = request.core() == AccountUpdate.CLEAR;
        return new ClusterUpdateParams(
                clusterId,
                request.name(),
                request.description(),
                request.seedUrls(),
                request.managementUrlPattern(),
                request.tlsBundle(),
                request.management(),
                clearCore ? null : request.core(),
                clearCore);
    }

    @Gated("cluster.update")
    private ClusterConnectionView gatedUpdate(
            ClusterUpdateParams params, ClusterEntity cluster, Edited edited, UpdateClusterRequest request) {
        return gate.run(Operation.of(params), () -> updateNow(params.clusterId(), cluster, edited, request));
    }

    private ClusterConnectionView updateNow(
            UUID clusterId, ClusterEntity cluster, Edited edited, UpdateClusterRequest request) {
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                UPDATE_CONNECTION,
                CLUSTER,
                cluster.getName(),
                clusterId,
                null,
                auditParams(edited, request),
                false);

        try {
            transactions.executeWithoutResult(status -> saveConnection(clusterId, edited));
            capabilityAssessments.invalidate(clusterId);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
        audit.succeed(event, 1);

        // The edit is committed: discovery runs after it and outside any transaction (ADR-0078), so the nodes'
        // URLs follow a changed pattern or seed without waiting for the next tick, and a broker that does not
        // answer does not undo the save.
        try {
            rediscover(
                    clusterId,
                    edited.seedUrls().stream()
                            .flatMap(url -> seedExpander.expand(url).stream())
                            .toList());
        } catch (BrokerConnectionException e) {
            log.warn(
                    "Discovery after editing the connection of cluster {} found no answering node: {}",
                    clusterId,
                    e.toString());
        }
        ClusterEntity saved = requireCluster(clusterId);
        return new ClusterConnectionView(
                clusterId, saved.getName(), saved.getDescription(), connectionView(clusterId, saved));
    }

    private void saveConnection(UUID clusterId, Edited edited) {
        ClusterEntity cluster = requireCluster(clusterId);
        cluster.edit(edited.name(), edited.description(), edited.pattern());
        clusters.save(cluster);
        storeTls(clusterId, edited.tlsBundle());
        storeCredential(clusterId, MANAGEMENT, edited.management());
        storeCredential(clusterId, DATA, edited.core());
        releaseDroppedSeeds(clusterId, edited.seedUrls());
        if (edited.retryProblems()) {
            // The pattern, the account or the TLS bundle changed, so why a node had no URL may no longer hold.
            nodes.findByClusterIdOrderByNameAsc(clusterId).forEach(BrokerNodeEntity::clearUrlProblem);
        }
        environmentIndex.invalidate();
    }

    private static final String ENTER_AGAIN = "Enter the %s password again to check new hosts.";

    private Edited merge(UUID clusterId, ClusterEntity cluster, UpdateClusterRequest request) {
        BrokerConnectionSettings stored = connections.settingsFor(clusterId);
        List<BrokerNodeEntity> storedNodes = nodes.findByClusterIdOrderByNameAsc(clusterId);
        String tls = request.tlsBundle() == null ? stored.tlsBundle() : blankToNull(request.tlsBundle());

        // A stored secret goes only where Rahdar already sends it. An edit that points the connection at a host
        // it has not used (a new seed, another pattern or TLS bundle) or changes who signs in must carry the
        // password again, so a caller who can edit the connection cannot make Rahdar send the stored one to an
        // address of their own.
        boolean newHosts = introducesHosts(request, cluster, stored, storedNodes);

        boolean managementSupplied =
                request.management() != null && notBlank(request.management().password());
        boolean managementRenamed = request.management() != null
                && !Objects.equals(request.management().username(), stored.username());
        if ((newHosts || managementRenamed) && !managementSupplied && stored.username() != null) {
            throw new IllegalArgumentException(ENTER_AGAIN.formatted("management"));
        }
        Account management = managementAccount(request, stored);
        Optional<BrokerCredentialEntity> storedCore = credentials.findByClusterIdAndKind(clusterId, DATA);
        Account core = coreAccount(clusterId, request, storedCore);
        requireCorePasswordAgain(request, storedCore, core, newHosts);
        if (management.username() != null && management.password() == null) {
            throw new IllegalArgumentException("Enter the management account's password.");
        }

        List<String> seeds = seedsAfter(request, storedNodes);
        String pattern = patternOf(request, cluster, seeds);
        return new Edited(
                request.name() != null ? request.name() : cluster.getName(),
                request.description() != null ? blankToNull(request.description()) : cluster.getDescription(),
                pattern,
                tls,
                seeds,
                management,
                core,
                managementSupplied,
                newHosts || managementRenamed || managementSupplied);
    }

    /** The seeds after the request: its own, or the ones the stored nodes were found from. */
    private static List<String> seedsAfter(UpdateClusterRequest request, List<BrokerNodeEntity> storedNodes) {
        if (request.seedUrls() != null) {
            return request.seedUrls();
        }
        return storedNodes.stream()
                .filter(n -> n.getUrlSource() == ManagementUrlSource.SEED)
                .map(BrokerNodeEntity::getManagementUrl)
                .toList();
    }

    /** A Core account the edit sends to a new host, or under another user, must come with its password again. */
    private static void requireCorePasswordAgain(
            UpdateClusterRequest request, Optional<BrokerCredentialEntity> storedCore, Account core, boolean newHosts) {
        boolean supplied = setsCore(request) && notBlank(request.core().password());
        if (core != null && (newHosts || coreRenamed(request, storedCore)) && !supplied) {
            throw new IllegalArgumentException(ENTER_AGAIN.formatted("Core"));
        }
    }

    /** The management account after the request: the stored one, or the request's with an empty password kept. */
    private static Account managementAccount(UpdateClusterRequest request, BrokerConnectionSettings stored) {
        if (request.management() == null) {
            return new Account(stored.username(), stored.password());
        }
        return new Account(
                request.management().username(), keepStored(request.management().password(), stored.password()));
    }

    /** The Core account after the request: the stored one, none when cleared, or the request's. */
    private Account coreAccount(
            UUID clusterId, UpdateClusterRequest request, Optional<BrokerCredentialEntity> storedCore) {
        if (request.core() == null) {
            return storedCore
                    .map(c -> new Account(c.getUsername(), openSecret(clusterId, c)))
                    .orElse(null);
        }
        if (request.core() == AccountUpdate.CLEAR) {
            return null;
        }
        AccountUpdate update = request.core();
        String kept = storedCore.map(c -> openSecret(clusterId, c)).orElse(null);
        String password = keepStored(update.password(), kept);
        if (password == null) {
            throw new IllegalArgumentException("Enter the Core account's password.");
        }
        return new Account(update.username(), password);
    }

    /** Whether the request sets the Core account, rather than leaving or clearing it. */
    private static boolean setsCore(UpdateClusterRequest request) {
        return request.core() != null && request.core() != AccountUpdate.CLEAR;
    }

    /** Whether the request sets the Core account to another user than the stored one. */
    private static boolean coreRenamed(UpdateClusterRequest request, Optional<BrokerCredentialEntity> storedCore) {
        return setsCore(request)
                && storedCore
                        .map(c ->
                                !Objects.equals(c.getUsername(), request.core().username()))
                        .orElse(true);
    }

    /** Whether the edit points Rahdar at a host it has not used for this cluster: a new seed, pattern or TLS bundle. */
    private static boolean introducesHosts(
            UpdateClusterRequest request,
            ClusterEntity cluster,
            BrokerConnectionSettings stored,
            List<BrokerNodeEntity> storedNodes) {
        Set<String> known = knownHostPorts(storedNodes);
        boolean newSeed = request.seedUrls() != null
                && request.seedUrls().stream().anyMatch(url -> !known.contains(hostPort(url)));
        boolean newPattern = request.managementUrlPattern() != null
                && !request.managementUrlPattern().equals(cluster.getManagementUrlPattern());
        boolean newTls =
                request.tlsBundle() != null && !Objects.equals(blankToNull(request.tlsBundle()), stored.tlsBundle());
        return newSeed || newPattern || newTls;
    }

    /** The {@code host:port} of every management URL the cluster's nodes already have. */
    private static Set<String> knownHostPorts(List<BrokerNodeEntity> storedNodes) {
        return storedNodes.stream()
                .map(BrokerNodeEntity::getManagementUrl)
                .filter(Objects::nonNull)
                .map(ClusterService::hostPort)
                .collect(Collectors.toSet());
    }

    /** {@code host:port} of a URL, the port defaulted by the scheme, as one string to compare addresses by. */
    static String hostPort(String url) {
        try {
            URI uri = URI.create(url);
            int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
            return String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException _) {
            return url;
        }
    }

    /**
     * Defence in depth behind {@link #merge}: a stored password that the request did not supply is never sent
     * to an address the cluster does not already use.
     */
    private static void requireSuppliedForNewHost(Edited edited, String url, Set<String> knownHosts) {
        if (!edited.managementSupplied() && !knownHosts.contains(hostPort(url))) {
            throw new IllegalArgumentException(ENTER_AGAIN.formatted("management"));
        }
    }

    /** The pattern asked for, else the one the cluster has, else the one its first seed implies. */
    private static String patternOf(UpdateClusterRequest request, ClusterEntity cluster, List<String> seeds) {
        if (request.managementUrlPattern() != null) {
            return request.managementUrlPattern();
        }
        if (cluster.getManagementUrlPattern() != null) {
            return cluster.getManagementUrlPattern();
        }
        return seeds.isEmpty() ? null : ManagementUrlPattern.defaultFor(seeds.get(0));
    }

    /** The audit parameters of an edit: what changed, with each password only ever as the mask. */
    private static Map<String, Object> auditParams(Edited edited, UpdateClusterRequest request) {
        Map<String, Object> params = new TreeMap<>();
        params.put("name", edited.name());
        params.put(SEED_URLS, edited.seedUrls());
        params.put("managementUrlPattern", edited.pattern());
        params.put("tlsBundle", edited.tlsBundle());
        params.put("managementUsername", edited.management().username());
        if (request.management() != null && notBlank(request.management().password())) {
            params.put("managementPassword", SecretRedactor.MASK);
        }
        params.put("coreUsername", edited.core() == null ? null : edited.core().username());
        if (request.core() != null && notBlank(request.core().password())) {
            params.put("corePassword", SecretRedactor.MASK);
        }
        return params;
    }

    /** An empty password keeps the stored one. */
    private static String keepStored(String submitted, String stored) {
        return notBlank(submitted) ? submitted : stored;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String openSecret(UUID clusterId, BrokerCredentialEntity credential) {
        return vault.open(SecretVault.aad(clusterId, credential.getKind()), credential.getSealed());
    }

    private void storeTls(UUID clusterId, String bundle) {
        Optional<BrokerTlsEntity> existing = tlsRepository.findByClusterId(clusterId);
        if (bundle == null) {
            existing.ifPresent(tlsRepository::delete);
        } else if (existing.isPresent()) {
            existing.get()
                    .update(
                            bundle,
                            existing.get().getClientCertRef(),
                            existing.get().isVerifyHostname());
        } else {
            tlsRepository.save(new BrokerTlsEntity(clusterId, bundle, null, true));
        }
    }

    /** Seals the account under its own kind, or removes the stored one when there is none (a Core account that falls back). */
    private void storeCredential(UUID clusterId, String kind, Account account) {
        Optional<BrokerCredentialEntity> existing = credentials.findByClusterIdAndKind(clusterId, kind);
        if (account == null || account.username() == null) {
            existing.ifPresent(credentials::delete);
            return;
        }
        byte[] sealed = vault.seal(SecretVault.aad(clusterId, kind), account.password());
        if (existing.isPresent()) {
            existing.get().replaceSecret(account.username(), sealed);
        } else {
            credentials.save(new BrokerCredentialEntity(clusterId, kind, account.username(), sealed));
        }
    }

    /** A seed the operator no longer lists stops being one: its node keeps no URL until one is derived for it. */
    private void releaseDroppedSeeds(UUID clusterId, List<String> seedUrls) {
        for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            if (node.getUrlSource() == ManagementUrlSource.SEED && !seedUrls.contains(node.getManagementUrl())) {
                node.releaseSeedUrl();
            }
        }
    }

    public void delete(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, ClusterPermissions.CLUSTER_WRITE);
        requireCluster(clusterId);
        gatedDelete(new ClusterDeleteParams(clusterId));
    }

    @Gated("cluster.delete")
    private void gatedDelete(ClusterDeleteParams params) {
        gate.run(Operation.of(params), () -> {
            transactions.executeWithoutResult(status -> deleteNow(params.clusterId()));
            return null;
        });
    }

    private void deleteNow(UUID clusterId) {
        ClusterEntity cluster = requireCluster(clusterId);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "DELETE_CLUSTER",
                CLUSTER,
                cluster.getName(),
                clusterId,
                null,
                Map.of(),
                false);
        clusters.delete(cluster);
        grants.revoke(CLUSTER, clusterId);
        bus.publish(new ReplicaSignal("cluster-deleted", clusterId.toString()));
        capabilityAssessments.invalidate(clusterId);
        environmentIndex.invalidate();
        audit.succeed(event, 1);
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Connects to every seed, each host name that resolves to several addresses expanded into one seed per
     * address (ADR-0175). A seed that fails is kept with its error, so the first failure can be reported when
     * none answers.
     */
    private List<Probe> connectAll(ConnectionInputs inputs, ProbeSession session) {
        List<Probe> probes = new ArrayList<>();
        for (String given : inputs.seedUrls()) {
            for (SeedExpander.Seed seed : seedExpander.expand(given)) {
                BrokerTarget target = session.node(seed.url());
                try {
                    probes.add(new Probe(seed.url(), seed.attachable(), target, brokerVersion(target), null));
                } catch (BrokerConnectionException e) {
                    probes.add(new Probe(seed.url(), seed.attachable(), null, null, e));
                }
            }
        }
        return probes;
    }

    /**
     * The broker's version from one read of its HA attributes, or null when the broker refused the read;
     * the range check then has nothing to refuse on.
     */
    private static String brokerVersion(BrokerTarget target) {
        try {
            return target.describe().version().orElse(null);
        } catch (BrokerConnectionException e) {
            if (BrokerFailures.answeredError(e).isPresent()) {
                return null;
            }
            throw e;
        }
    }

    /** A refusal naming the minimum when any reachable seed runs an older release (ADR-0142), else null. */
    private static BrokerConnectionException belowMinimum(List<Probe> reachable) {
        return reachable.stream()
                .filter(p -> BrokerVersion.support(p.version()) == BrokerVersion.Support.BELOW_MINIMUM)
                .findFirst()
                .map(p -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNSUPPORTED_VERSION,
                        "The broker at " + p.url() + " runs Artemis " + p.version() + ". Rahdar supports Artemis "
                                + BrokerVersion.MINIMUM + " and later; upgrade the broker to register it."))
                .orElse(null);
    }

    private static List<NodeEndpoint> endpoints(ClusterTopology topology) {
        return topology.nodes().stream().flatMap(n -> n.endpoints().stream()).toList();
    }

    private <T> Attempt<T> failed(AuditEvent event, BrokerConnectionException error) {
        audit.fail(event, error.getMessage());
        return new Attempt.Failed<>(error.kind(), error.getMessage());
    }

    private ClusterEntity requireCluster(UUID clusterId) {
        return clusters.findById(clusterId).orElseThrow(() -> new NotFoundException("Cluster", clusterId));
    }

    private static int endpointCount(ClusterTopology topology) {
        return topology.nodes().stream().mapToInt(n -> n.endpoints().size()).sum();
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        } catch (RuntimeException _) {
            return url;
        }
    }
}
