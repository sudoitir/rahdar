package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.access.Assessment;
import io.github.sudoitir.openbroker.access.Capability;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import io.github.sudoitir.rahdar.platform.broker.UnsupportedSystemException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.Account;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.Checked;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.DiscoveredNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.Resolved;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerTlsEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerTlsRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Connection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilityView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.KafkaConnectionDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RegisteredRef;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RegistrationCheck;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registering and editing a cluster of a system that is reached through its driver (ADR-0213): the system's
 * {@link ClusterRegistrar} reaches the cluster and says what it found, and this stores the cluster, its nodes, its
 * account and its identity claim in one transaction. The same cluster is never registered twice: its own id, as
 * its brokers report it, is claimed in {@code broker_identity}.
 */
@Component
@RequiredArgsConstructor
class SystemRegistration {

    /** The kind of the claim a driver-reported cluster id makes in {@code broker_identity}. */
    static final String CLUSTER_ID = "CLUSTER_ID";

    private static final String DATA = "DATA";
    private static final String REGISTER_CLUSTER = "REGISTER_CLUSTER";
    private static final String UPDATE_CONNECTION = "UPDATE_CLUSTER_CONNECTION";
    private static final String CLUSTER = "CLUSTER";

    private final List<ClusterRegistrar> registrars;
    private final BrokerSystems systems;
    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;
    private final BrokerCredentialRepository credentials;
    private final BrokerTlsRepository tlsRepository;
    private final BrokerIdentityClaims identityClaims;
    private final SecretVault vault;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final PermissionResolver permissions;
    private final ApplicationEventPublisher eventPublisher;
    private final ClusterEnvironmentIndex environmentIndex;
    private final TransactionTemplate transactions;
    private final BrokerGateway gateway;

    /** Whether the system registers through a {@link ClusterRegistrar}; Artemis registers from Jolokia seeds. */
    boolean handles(String system) {
        return registrars.stream().anyMatch(r -> r.system().equals(system));
    }

    /**
     * Refuses a system Rahdar has no driver for and a connection shaped for another system, naming the systems it
     * supports.
     */
    void requireSupported(RegisterClusterRequest request) {
        Set<String> loaded = systems.ids();
        String shaped = request.connection().system();
        if (!loaded.contains(request.system())) {
            throw new UnsupportedSystemException(
                    "Rahdar has no driver for the system '" + request.system() + "'.", loaded);
        }
        if (!request.system().equals(shaped)) {
            throw new UnsupportedSystemException(
                    "The connection is shaped for '" + shaped + "', but the cluster's system is '" + request.system()
                            + "'.",
                    loaded);
        }
    }

    /** Reaches the cluster once and reports; nothing is stored. */
    RegistrationCheck check(RegisterClusterRequest request) {
        Checked checked = registrar(request.system()).check(request.connection());
        return withHolder(checked.view(), null);
    }

    /** Registers the cluster; refuses one that cannot be reached, and one that is already registered. */
    UUID register(RegisterClusterRequest request) {
        ClusterRegistrar registrar = registrar(request.system());
        Connection connection = request.connection();
        String name = request.name() != null ? request.name() : registrar.defaultName(connection);
        Checked checked = registrar.check(connection);
        RegistrationCheck view = checked.view();
        if (!checked.passed()) {
            AuditEvent event = begin(REGISTER_CLUSTER, name, null, registrar.auditParams(connection));
            audit.fail(event, view.failure().message());
            throw failedCheck(view);
        }
        Optional<UUID> holder = identityClaims.holder(CLUSTER_ID, view.clusterId());
        if (holder.isPresent()) {
            AuditEvent event = begin(REGISTER_CLUSTER, name, null, registrar.auditParams(connection));
            throw refusal(holder.get(), event);
        }
        return transactions.execute(status -> save(request, registrar, name, checked));
    }

    private UUID save(RegisterClusterRequest request, ClusterRegistrar registrar, String name, Checked checked) {
        Connection connection = request.connection();
        String clusterIdentity = checked.view().clusterId();
        ClusterEntity cluster = clusters.saveAndFlush(
                new ClusterEntity(request.system(), name, request.description(), request.environmentId()));
        UUID clusterId = cluster.getId();
        AuditEvent event = begin(REGISTER_CLUSTER, name, clusterId, registrar.auditParams(connection));
        // The check above sees committed clusters only. The claim settles two registrations of the same cluster
        // running at once: it waits for the other to commit, then finds the cluster taken, and throwing rolls this
        // cluster back (ADR-0167).
        Optional<UUID> holder = identityClaims.claim(clusterId, Map.of(CLUSTER_ID, Set.of(clusterIdentity)));
        if (holder.isPresent()) {
            throw refusal(holder.get(), event);
        }
        storeAccount(clusterId, registrar.accountOf(connection));
        storeTls(clusterId, connection.tlsBundle());
        checked.nodes()
                .forEach(n -> nodes.save(BrokerNodeEntity.reported(clusterId, n.name(), n.nativeId(), n.endpoints())));
        registrar.store(clusterId, connection);
        eventPublisher.publishEvent(new ClusterRegistered(clusterId));
        environmentIndex.invalidate();
        audit.succeed(event, checked.nodes().size());
        return clusterId;
    }

    /** How the cluster is reached, as stored. */
    KafkaConnectionDetail detail(UUID clusterId) {
        return registrarOf(clusterId).detail(clusterId);
    }

    /**
     * What the account may do, from the driver's own assessment of the open connection. A cluster that cannot be
     * reached answers unknown with the reason, so the cluster screen still opens.
     */
    CapabilitiesView capabilities(UUID clusterId) {
        Map<String, Assessment> found = new HashMap<>();
        String failure = null;
        try {
            gateway.cluster(clusterId).access().probe().assessments().forEach((c, a) -> found.put(c.id(), a));
        } catch (RuntimeException e) {
            failure = "The cluster could not be reached: " + e.getMessage();
        }
        String reason = failure;
        Function<String, CapabilityView> of = id -> {
            Assessment a = found.get(id);
            return a == null
                    ? new CapabilityView("UNKNOWN", reason != null ? reason : "Not assessed.", null)
                    : new CapabilityView(a.status().name(), a.reason(), null);
        };
        CapabilityView io = worst(
                of.apply(Capability.BROWSE.id()), of.apply(Capability.SEND.id()), of.apply(Capability.RECEIVE.id()));
        return new CapabilitiesView(
                of.apply(Capability.MANAGEMENT_READ.id()),
                of.apply(Capability.MANAGEMENT_WRITE.id()),
                of.apply(Capability.NOTIFICATIONS.id()),
                io,
                new CapabilityView(
                        "UNKNOWN", "Slow-consumer detection reads Artemis consumers; it does not apply here.", null),
                List.of());
    }

    private static CapabilityView worst(CapabilityView... views) {
        for (String status : List.of("UNAVAILABLE", "UNKNOWN")) {
            for (CapabilityView v : views) {
                if (v.status().equals(status)) {
                    return v;
                }
            }
        }
        return views[0];
    }

    /** What an edit of the connection would find, nothing stored. */
    RegistrationCheck checkUpdate(UUID clusterId, UpdateClusterRequest request) {
        ClusterRegistrar registrar = registrarOf(clusterId);
        Resolved resolved = registrar.edited(clusterId, request.connection());
        return withHolder(registrar.check(resolved.connection()).view(), clusterId);
    }

    /** Saves an edit of the connection once the edited connection has been reached. */
    void update(UUID clusterId, UpdateClusterRequest request) {
        ClusterEntity cluster =
                clusters.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        ClusterRegistrar registrar = registrarOf(clusterId);
        Resolved resolved = registrar.edited(clusterId, request.connection());
        Map<String, Object> params = registrar.auditParams(resolved.connection());
        AuditEvent event = begin(UPDATE_CONNECTION, cluster.getName(), clusterId, params);
        Checked checked = registrar.check(resolved.connection());
        if (!checked.passed()) {
            audit.fail(event, checked.view().failure().message());
            throw failedCheck(checked.view());
        }
        try {
            transactions.executeWithoutResult(status -> saveUpdate(clusterId, request, registrar, resolved, checked));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
        audit.succeed(event, checked.nodes().size());
    }

    private void saveUpdate(
            UUID clusterId,
            UpdateClusterRequest request,
            ClusterRegistrar registrar,
            Resolved resolved,
            Checked checked) {
        ClusterEntity cluster =
                clusters.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        cluster.edit(
                request.name() != null ? request.name() : cluster.getName(),
                request.description() != null ? request.description() : cluster.getDescription(),
                null);
        clusters.save(cluster);
        if (resolved.account() != null) {
            storeAccount(clusterId, resolved.account());
        }
        storeTls(clusterId, resolved.connection().tlsBundle());
        mergeNodes(clusterId, checked.nodes());
        identityClaims
                .claim(clusterId, Map.of(CLUSTER_ID, Set.of(checked.view().clusterId())))
                .ifPresent(holder -> {
                    throw new ConflictException(
                            "cluster-already-registered",
                            "The edited connection reaches a cluster that is registered.");
                });
        registrar.store(clusterId, resolved.connection());
        environmentIndex.invalidate();
    }

    /** The nodes the check reached are added or refreshed; one it did not reach stays, as it may only be down. */
    private void mergeNodes(UUID clusterId, List<DiscoveredNode> reached) {
        Map<String, BrokerNodeEntity> stored = new HashMap<>();
        nodes.findByClusterIdOrderByNameAsc(clusterId).forEach(n -> stored.put(n.getNativeNodeId(), n));
        for (DiscoveredNode node : reached) {
            BrokerNodeEntity existing = stored.get(node.nativeId());
            if (existing == null) {
                nodes.save(BrokerNodeEntity.reported(clusterId, node.name(), node.nativeId(), node.endpoints()));
            } else {
                existing.mergeReported(node.name(), node.endpoints());
            }
        }
    }

    private void storeAccount(UUID clusterId, Account account) {
        Optional<BrokerCredentialEntity> existing = credentials.findByClusterIdAndKind(clusterId, DATA);
        if (account == null || account.secret() == null) {
            existing.ifPresent(credentials::delete);
            return;
        }
        byte[] sealed = vault.seal(SecretVault.aad(clusterId, DATA), account.secret());
        if (existing.isPresent()) {
            existing.get().replaceSecret(account.username(), sealed);
        } else {
            credentials.save(new BrokerCredentialEntity(clusterId, DATA, account.username(), sealed));
        }
    }

    private void storeTls(UUID clusterId, String bundle) {
        Optional<BrokerTlsEntity> existing = tlsRepository.findByClusterId(clusterId);
        if (bundle == null || bundle.isBlank()) {
            existing.ifPresent(tlsRepository::delete);
        } else if (existing.isPresent()) {
            existing.get().update(bundle, existing.get().getClientCertRef(), true);
        } else {
            tlsRepository.save(new BrokerTlsEntity(clusterId, bundle, null, true));
        }
    }

    /** The check with the registered cluster that holds this one, when the caller may read it. */
    private RegistrationCheck withHolder(RegistrationCheck view, UUID except) {
        if (view.clusterId() == null) {
            return view;
        }
        return identityClaims
                .holder(CLUSTER_ID, view.clusterId())
                .filter(id -> !id.equals(except))
                .filter(id -> permissions.can(id, Permissions.CLUSTER_READ))
                .flatMap(id -> clusters.findById(id).map(c -> new RegisteredRef(id, c.getName())))
                .map(view::withAlreadyRegistered)
                .orElse(view);
    }

    /** A registered cluster holds this one: named when the caller may read it, hidden when not. */
    private ClusterAlreadyRegisteredException refusal(UUID holder, AuditEvent attempt) {
        ClusterAlreadyRegisteredException refusal = permissions.can(holder, Permissions.CLUSTER_READ)
                ? new ClusterAlreadyRegisteredException(
                        holder,
                        clusters.findById(holder).map(ClusterEntity::getName).orElse(""),
                        List.of())
                : ClusterAlreadyRegisteredException.hidden();
        audit.fail(attempt, refusal.getMessage());
        return refusal;
    }

    private static ConflictException failedCheck(RegistrationCheck view) {
        return new ConflictException(
                "connection-check-failed",
                view.failure().message() + " " + view.failure().next());
    }

    private AuditEvent begin(String action, String name, UUID clusterId, Map<String, Object> params) {
        return audit.begin(actorResolver.resolve(), action, CLUSTER, name, clusterId, null, params, false);
    }

    private ClusterRegistrar registrar(String system) {
        return registrars.stream()
                .filter(r -> r.system().equals(system))
                .findFirst()
                .orElseThrow(() -> new UnsupportedSystemException(
                        "Rahdar has no driver for the system '" + system + "'.", systems.ids()));
    }

    private ClusterRegistrar registrarOf(UUID clusterId) {
        return registrar(systems.idOf(clusterId));
    }
}
