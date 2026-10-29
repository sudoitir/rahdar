package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.ConnectOptions;
import io.github.sudoitir.openbroker.ConnectRequest;
import io.github.sudoitir.openbroker.ConnectionReport;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.TlsProvider;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerConnectOptions;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerProperties;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.SslBundleTls;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Rahdar's one way to a broker (ADR-0203): a broker SDK connection per registered cluster, built
 * from its nodes, its credentials (asked on every connect, so a rotated secret applies at once) and
 * its TLS bundle, and reconfigured when its nodes change rather than reconnected, so receivers and
 * notification subscriptions on unchanged nodes go on. A node's SDK id is its Rahdar id.
 *
 * <p>{@link #probe} is for addresses that are not registered nodes yet: a seed during registration or
 * discovery. Close what it returns.
 */
@Slf4j
@Component
public class BrokerGateway implements DisposableBean {

    /** The protocol names of the Artemis driver's endpoints. */
    public static final String JOLOKIA = "jolokia";

    public static final String CORE = "core";

    private final BrokerDrivers drivers;
    private final NodeDirectory directory;
    private final List<ConnectionOptions> connections;
    private final SslBundleTls tls;
    private final OpenBrokerConnectOptions connectOptions;
    private final OpenBrokerProperties brokerProperties;
    private final BrokerTelemetry telemetry;
    private final Map<UUID, Held> clusters = new ConcurrentHashMap<>();

    /** A cluster's connection, and the watch on its transport's links, closed with it. */
    private record Held(ClusterConnection connection, ClusterSpec spec, AutoCloseable watch) {}

    public BrokerGateway(
            BrokerDrivers drivers,
            NodeDirectory directory,
            List<ConnectionOptions> connections,
            SslBundleTls tls,
            OpenBrokerConnectOptions connectOptions,
            OpenBrokerProperties brokerProperties,
            BrokerTelemetry telemetry) {
        this.drivers = drivers;
        this.directory = directory;
        this.connections = connections;
        this.tls = tls;
        this.connectOptions = connectOptions;
        this.brokerProperties = brokerProperties;
        this.telemetry = telemetry;
    }

    /** The cluster's connection, following its registered nodes. */
    public ClusterConnection cluster(UUID clusterId) {
        ClusterSpec spec = specFor(clusterId);
        Held held = clusters.compute(clusterId, (id, existing) -> {
            if (existing == null) {
                ClusterConnection connection = drivers.connect(request(id, spec));
                AutoCloseable watch = connection
                        .transport()
                        .map(t -> t.watch(telemetry.listener(id, directory.system(id))))
                        .orElse(() -> {});
                return new Held(connection, spec, watch);
            }
            if (!existing.spec().equals(spec)) {
                existing.connection().reconfigure(spec);
                return new Held(existing.connection(), spec, existing.watch());
            }
            return existing;
        });
        return held.connection();
    }

    /** Addresses that are not registered nodes, reached with a registered cluster's credentials and TLS. */
    public ProbeSession probe(UUID clusterId) {
        return new ProbeSession(spec -> drivers.connect(request(clusterId, spec)));
    }

    /**
     * Jolokia seeds reached with credentials and TLS given before the cluster exists. The probe is the one of the
     * system that is registered from Jolokia seeds (see {@link ConnectionOptions.Entry}); a system registered from
     * bootstrap servers verifies through its driver instead.
     */
    public ProbeSession probe(BrokerConnectionSettings with) {
        CredentialSource credentials = plane -> with.hasCredentials()
                ? Credentials.usernamePassword(with.username(), with.password())
                : new Credentials.None();
        return new ProbeSession(spec -> drivers.connect(new ConnectRequest(
                seedSystem(),
                spec,
                credentials,
                options(with.clusterId(), seedSystem(), tls(with.hasTls(), with.tlsBundle(), with.verifyHostname())))));
    }

    /**
     * Data-plane addresses checked with an account given for the check, before the cluster exists or
     * before an edit of its account is saved. Close it when the check ends.
     */
    public ClusterConnection dataPlane(List<NodeSpec> nodes, CoreConnectionSettings with) {
        CredentialSource credentials = plane -> with.hasCredentials()
                ? Credentials.usernamePassword(with.username(), with.password())
                : new Credentials.None();
        TlsProvider dataTls = tls(with.hasTls(), with.tlsBundle(), with.verifyHostname());
        return drivers.connect(new ConnectRequest(
                seedSystem(),
                ClusterSpec.ofNodes(nodes),
                credentials,
                options(with.clusterId(), seedSystem(), dataTls)));
    }

    /**
     * Reaches a cluster that is not registered once and reports what it found; nothing stays open. The driver
     * reaches every node it learns of and names each one's outcome in the report.
     */
    public ConnectionReport verify(
            String system,
            ClusterSpec spec,
            CredentialSource credentials,
            Optional<ConnectionOptions.TlsBundle> bundle) {
        TlsProvider clusterTls =
                bundle.map(b -> tls(true, b.name(), b.verifyHostname())).orElseGet(TlsProvider::none);
        return drivers.verify(new ConnectRequest(system, spec, credentials, options(null, system, clusterTls)));
    }

    /** A registered node, through its cluster's connection. */
    public BrokerTarget node(UUID clusterId, UUID nodeId) {
        return new BrokerTarget(cluster(clusterId), idOf(nodeId));
    }

    /** Names what a broker link is for, as diagnostics shows it (ADR-0211). */
    public void label(String linkId, String owner) {
        telemetry.label(linkId, owner);
    }

    /** The clusters this holds a connection for. */
    Set<UUID> clusterIds() {
        return Set.copyOf(clusters.keySet());
    }

    /** Closes a cluster's connection: it was removed. */
    public void forget(UUID clusterId) {
        Held held = clusters.remove(clusterId);
        if (held != null) {
            try {
                held.watch().close();
            } catch (Exception e) {
                log.debug("Closing the link watch of cluster {} failed: {}", clusterId, e.toString());
            }
            held.connection().close();
        }
    }

    @Override
    public void destroy() {
        clusters.keySet().forEach(this::forget);
    }

    /** The registered node an SDK node id names. */
    public static UUID nodeOf(NodeId node) {
        return UUID.fromString(node.value());
    }

    public static NodeId idOf(UUID node) {
        return NodeId.of(node.toString());
    }

    private ClusterSpec specFor(UUID clusterId) {
        List<NodeSpec> nodes = new ArrayList<>();
        for (NodeDirectory.KnownNode n : directory.nodes()) {
            if (!Objects.equals(n.clusterId(), clusterId)) {
                continue;
            }
            nodes.add(new NodeSpec(idOf(n.id()), n.endpoints()));
        }
        return ClusterSpec.ofNodes(nodes);
    }

    /** The system whose clusters are registered from Jolokia seeds, which the seed and data-plane probes speak. */
    private String seedSystem() {
        return connections.stream()
                .filter(c -> c.entry() == ConnectionOptions.Entry.JOLOKIA_SEEDS)
                .map(ConnectionOptions::system)
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "unsupported-on-system", "No installed system is registered from Jolokia seeds."));
    }

    /**
     * A system's broker accounts, read from Rahdar's database on every connect. A driver asks on its own
     * threads, a notification stream's among them, and backs off and asks again on a BrokerException;
     * anything else would end that thread for good, so a failed read is reported as the broker being
     * unavailable. Every system's credentials go through it.
     */
    static CredentialSource guarded(CredentialSource accounts) {
        return plane -> {
            try {
                return accounts.forPlane(plane);
            } catch (DataAccessException e) {
                throw new BrokerException(
                        Status.UNAVAILABLE,
                        ErrorInfo.of(ErrorReasons.UNREACHABLE),
                        Dispatch.NOT_SENT,
                        null,
                        "Rahdar could not read the cluster's broker credentials from its database.",
                        e);
            }
        };
    }

    private ConnectRequest request(UUID clusterId, ClusterSpec spec) {
        String system = directory.system(clusterId);
        ConnectionOptions how = connections.stream()
                .filter(c -> c.system().equals(system))
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "unsupported-on-system", "No connection options for the system " + system + "."));
        TlsProvider clusterTls = how.tls(clusterId)
                .map(b -> tls(true, b.name(), b.verifyHostname()))
                .orElseGet(TlsProvider::none);
        return new ConnectRequest(
                system,
                how.spec(clusterId, spec.nodes()),
                guarded(how.credentials(clusterId)),
                options(clusterId, system, clusterTls));
    }

    private ConnectOptions options(UUID clusterId, String system, TlsProvider clusterTls) {
        return connectOptions.forConnection(String.valueOf(clusterId), system, clusterTls);
    }

    /** The cluster's TLS from its SSL bundle (ADR-0009): none when it names no bundle. */
    private TlsProvider tls(boolean enabled, String bundle, boolean verifyHostname) {
        return enabled ? tls.forBundle(bundle, verifyHostname) : TlsProvider.none();
    }

    /** Spool files left by a Rahdar that stopped mid-relay are useless now. */
    @EventListener(ApplicationReadyEvent.class)
    void sweepSpool() {
        Path spool = brokerProperties.client().spoolDirectory();
        if (spool == null || !Files.exists(spool)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(spool)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(spool))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException _) {
                            // One undeletable file must not stop the rest being swept.
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not sweep the large-message spool " + spool, e);
        }
    }
}
