package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.ConnectOptions;
import io.github.sudoitir.openbroker.ConnectRequest;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.TlsProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    /** The system a new cluster is registered with, and a seed is probed as, until registration offers a choice. */
    public static final String SYSTEM = "artemis";

    /** The protocol names of the Artemis driver's endpoints. */
    public static final String JOLOKIA = "jolokia";

    public static final String CORE = "core";

    /**
     * Where large message bodies are spooled while a relay or a drain holds them. Nothing needs one
     * once Rahdar has restarted: a resumed run or a drain receives again.
     */
    static final Path SPOOL = Path.of(System.getProperty("java.io.tmpdir"), "rahdar-spool");

    private final BrokerDrivers drivers;
    private final NodeDirectory directory;
    private final ConnectionSettingsSource settings;
    private final BrokerTls tls;
    private final RahdarCallInterceptor interceptor;
    private final BrokerTimeouts timeouts;
    private final BrokerTelemetry telemetry;
    private final Map<UUID, Held> clusters = new ConcurrentHashMap<>();

    /** A cluster's connection, and the watch on its transport's links, closed with it. */
    private record Held(ClusterConnection connection, ClusterSpec spec, AutoCloseable watch) {}

    public BrokerGateway(
            BrokerDrivers drivers,
            NodeDirectory directory,
            ConnectionSettingsSource settings,
            BrokerTls tls,
            RahdarCallInterceptor interceptor,
            BrokerTimeouts timeouts,
            BrokerTelemetry telemetry) {
        this.drivers = drivers;
        this.directory = directory;
        this.settings = settings;
        this.tls = tls;
        this.interceptor = interceptor;
        this.timeouts = timeouts;
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
                        .map(t -> t.watch(telemetry.listener(id)))
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

    /** Addresses reached with credentials and TLS given before the cluster exists. */
    public ProbeSession probe(BrokerConnectionSettings with) {
        CredentialSource credentials = plane -> with.hasCredentials()
                ? Credentials.usernamePassword(with.username(), with.password())
                : new Credentials.None();
        return new ProbeSession(
                spec -> drivers.connect(new ConnectRequest(SYSTEM, spec, credentials, options(tls.forCluster(with)))));
    }

    /**
     * Data-plane addresses checked with an account given for the check, before the cluster exists or
     * before an edit of its account is saved. Close it when the check ends.
     */
    public ClusterConnection dataPlane(List<NodeSpec> nodes, CoreConnectionSettings with) {
        CredentialSource credentials = plane -> with.hasCredentials()
                ? Credentials.usernamePassword(with.username(), with.password())
                : new Credentials.None();
        TlsProvider dataTls = tls.forCluster(new BrokerConnectionSettings(
                with.clusterId(), with.username(), with.password(), with.tlsBundle(), with.verifyHostname()));
        return drivers.connect(new ConnectRequest(SYSTEM, ClusterSpec.ofNodes(nodes), credentials, options(dataTls)));
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

    private ConnectRequest request(UUID clusterId, ClusterSpec spec) {
        CredentialSource credentials = plane -> switch (plane) {
            case MANAGEMENT -> {
                BrokerConnectionSettings s = settings.jolokiaSettings(clusterId);
                yield s.hasCredentials()
                        ? Credentials.usernamePassword(s.username(), s.password())
                        : new Credentials.None();
            }
            case DATA -> {
                CoreConnectionSettings s = settings.coreSettings(clusterId);
                yield s.hasCredentials()
                        ? Credentials.usernamePassword(s.username(), s.password())
                        : new Credentials.None();
            }
        };
        return new ConnectRequest(
                directory.system(clusterId),
                spec,
                credentials,
                options(tls.forCluster(settings.jolokiaSettings(clusterId))));
    }

    private ConnectOptions options(TlsProvider clusterTls) {
        return ConnectOptions.defaults()
                .withInterceptor(interceptor)
                .withTls(clusterTls)
                .withClock(Clock.systemUTC())
                .withContextPropagation(UnaryOperator.identity())
                .withSpoolDirectory(SPOOL)
                .withTimeouts(timeouts)
                .withObserver(telemetry.observer());
    }

    /** Spool files left by a Rahdar that stopped mid-relay are useless now. */
    @EventListener(ApplicationReadyEvent.class)
    void sweepSpool() {
        if (!Files.exists(SPOOL)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(SPOOL)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(SPOOL))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException _) {
                            // One undeletable file must not stop the rest being swept.
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not sweep the large-message spool " + SPOOL, e);
        }
    }
}
