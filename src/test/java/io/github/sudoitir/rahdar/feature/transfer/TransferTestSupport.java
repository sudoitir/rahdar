package io.github.sudoitir.rahdar.feature.transfer;

import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.Finding;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.FindingKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.SelectionKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferExecuteRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferPreviewRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferSelection;
import io.github.sudoitir.rahdar.kernel.jobs.BackgroundRuns;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.GrantLoader;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.ArtemisBrokers;
import io.github.sudoitir.rahdar.support.ArtemisBrokers.Broker;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * End-to-end transfer runs (cross-broker-message-transfer group 5): real brokers, the real relay
 * and real Postgres, driven through {@link TransferService} as the controller drives it.
 *
 * <p>Two clusters are registered for each test: {@code P}, the suite's shared broker, and {@code D},
 * a second broker. A real operator account is signed in, because a run re-reads its grants before
 * every batch. Every subclass shares this one Spring context; {@link TransferFaults} is a spy in it so
 * a test can stand in for Rahdar stopping between a batch's target commit and its source commit.
 */
abstract class TransferTestSupport extends PostgresIntegrationTest {

    static final Set<TransferState> ENDED = Set.of(
            TransferState.SUCCEEDED,
            TransferState.PARTIAL,
            TransferState.STOPPED,
            TransferState.FAILED,
            TransferState.RETURNED,
            TransferState.INTERRUPTED);

    @MockitoSpyBean
    TransferFaults faults;

    @Autowired
    TransferService transfers;

    @Autowired
    TransferRecovery recovery;

    @Autowired
    BackgroundRuns background;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterDirectory directory;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerGateway gateway;

    @Autowired
    SettingsService settings;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    GrantLoader grants;

    final StagingQueues staging = new StagingQueues();
    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    final Broker p = ArtemisBrokers.primary();
    final Broker d = ArtemisBrokers.second();

    /** A cluster Rahdar knows, and its nodes in the order they were given. */
    record Registered(UUID id, List<UUID> nodes) {
        UUID node() {
            return nodes.getFirst();
        }
    }

    Registered clusterP;
    Registered clusterD;
    UUID userId;
    private Authentication operator;

    private final List<Registered> registered = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    private final List<UUID> started = new ArrayList<>();

    @BeforeEach
    void transferFixture() {
        userId = OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);
        operator = SecurityContextHolder.getContext().getAuthentication();
        // Small batches so a few dozen messages take several, and no pacing to wait on.
        settings.put(TransferSettings.BATCH_SIZE, "10");
        settings.put(TransferSettings.MESSAGES_PER_SECOND, "100000");
        clusterP = register("p", p);
        clusterD = register("d", d);
    }

    @AfterEach
    void transferCleanUp() {
        // A test may have signed in someone with fewer rights; the clean-up needs every one.
        SecurityContextHolder.getContext().setAuthentication(operator);
        for (UUID run : started) {
            background.requestStop(run);
        }
        for (UUID run : started) {
            awaitIdle(run);
            // A run a test left stopped or failed still holds its messages in staging.
            quietly(() -> p.destroyQueue(StagingQueues.queueName(run)));
        }
        cleanups.reversed().forEach(TransferTestSupport::quietly);
        for (Registered c : registered) {
            gateway.forget(c.id());
            clusters.deleteById(c.id());
        }
        for (String key : List.of(
                TransferSettings.BATCH_SIZE,
                TransferSettings.MESSAGES_PER_SECOND,
                TransferSettings.MAX_CONCURRENT_RUNS,
                TransferSettings.CAPACITY_THRESHOLD_PERCENT,
                TransferSettings.CAPACITY_WAIT,
                BrokerSettings.BULK_CAP)) {
            settings.reset(key);
        }
        SecurityContextHolder.clearContext();
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException _) {
            // best effort
        }
    }

    /** Register {@code brokers} as one cluster, each node with its host-reachable Core URL. */
    Registered register(String name, Broker... brokers) {
        Attempt<ClusterDetail> attempt = clusterService.register(new RegisterClusterRequest(
                List.of(brokers).stream().map(Broker::jolokiaUrl).toList(),
                name + "-" + sfx,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register " + name + ": " + attempt);
        }
        UUID id = ok.value().id();
        List<UUID> nodes = new ArrayList<>();
        for (Broker broker : brokers) {
            ClusterNode node = directory.nodes(id).stream()
                    .filter(n -> broker.jolokiaUrl().equals(n.getManagementUrl()))
                    .findFirst()
                    .orElseThrow();
            clusterService.overrideNodeUrl(id, node.getId(), new NodeOverrideRequest(null, broker.coreUrl()));
            nodes.add(node.getId());
        }
        Registered r = new Registered(id, nodes);
        registered.add(r);
        return r;
    }

    /** A queue for this test, removed afterwards. */
    String queue(Broker broker, String name) {
        return queue(broker, name, null);
    }

    String queue(Broker broker, String name, String filter) {
        String q = name + "." + sfx;
        broker.createQueue(q, filter);
        cleanups.add(() -> broker.destroyQueue(q));
        return q;
    }

    /** Run {@code cleanup} after the test, even when it fails. */
    void afterTest(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    // ---- runs ------------------------------------------------------------------

    static TransferSelection all() {
        return new TransferSelection(SelectionKind.ALL, null, null);
    }

    static TransferSelection filter(String filter) {
        return new TransferSelection(SelectionKind.FILTER, null, filter);
    }

    static TransferSelection ids(List<Long> ids) {
        return new TransferSelection(SelectionKind.IDS, ids, null);
    }

    TransferRunView preview(
            TransferMode mode,
            Registered from,
            UUID fromNode,
            String sourceQueue,
            TransferSelection selection,
            Registered to,
            UUID toNode,
            String targetQueue) {
        return transfers.preview(
                from.id(),
                new TransferPreviewRequest(mode, sourceQueue, fromNode, selection, to.id(), toNode, targetQueue, null));
    }

    /** From P's queue to D's queue. */
    TransferRunView preview(TransferMode mode, String sourceQueue, TransferSelection selection, String targetQueue) {
        return preview(mode, clusterP, clusterP.node(), sourceQueue, selection, clusterD, clusterD.node(), targetQueue);
    }

    /** Execute as the operator would: every warning acknowledged, a move confirmed by name. */
    TransferRunView execute(TransferRunView preview, boolean override) {
        List<String> warnings = preview.findings().stream()
                .filter(f -> f.kind() == FindingKind.WARN)
                .map(Finding::code)
                .toList();
        TransferRunView run = transfers.execute(
                preview.source().clusterId(),
                preview.id(),
                new TransferExecuteRequest(
                        preview.planHash(), override, warnings, preview.source().queue()));
        started.add(run.id());
        return run;
    }

    TransferRunView execute(TransferRunView preview) {
        return execute(preview, false);
    }

    TransferRunView resume(TransferRunView run) {
        TransferRunView resumed = transfers.resume(run.source().clusterId(), run.id());
        started.add(resumed.id());
        return resumed;
    }

    TransferRunView get(TransferRunView run) {
        return transfers.get(run.source().clusterId(), run.id());
    }

    /** Wait for the run to reach a state that ends its segment, and for its thread to end. */
    TransferRunView awaitEnded(TransferRunView run) {
        return awaitEnded(run, Duration.ofSeconds(90));
    }

    TransferRunView awaitEnded(TransferRunView run, Duration timeout) {
        TransferRunView ended = await(run, r -> ENDED.contains(r.state()), timeout);
        awaitIdle(run.id());
        return ended;
    }

    TransferRunView await(TransferRunView run, Predicate<TransferRunView> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        TransferRunView now = get(run);
        while (!condition.test(now)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("The run did not get there within " + timeout + "; it is " + now.state() + " ("
                        + now.lastError() + "), delivered " + now.delivered());
            }
            LockSupport.parkNanos(50_000_000);
            now = get(run);
        }
        return now;
    }

    void awaitIdle(UUID runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (background.isActive(runId)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Run " + runId + " did not end");
            }
            LockSupport.parkNanos(20_000_000);
        }
    }
}
