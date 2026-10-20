package io.github.sudoitir.rahdar.platform.clusters.internal.persistence;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.CoreUrl;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlSource;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEndpoint.Source;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * Maps the {@code broker_node} table. One row per broker
 * endpoint. HA state is written by dirty-checking these fields (ADR-0011), so the
 * row's {@code id} never changes and {@code audit_event.node_id} stays valid.
 */
@Entity
@Table(name = "broker_node")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerNodeEntity implements ClusterNode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "name", nullable = false)
    private String name;

    @Getter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "broker_node_endpoint", joinColumns = @JoinColumn(name = "node_id"))
    private Set<BrokerNodeEndpoint> endpoints = new HashSet<>();

    @Column(name = "ha_designation", nullable = false)
    private String haDesignation = "NONE";

    @Column(name = "ha_group")
    private String haGroup;

    @Column(name = "state", nullable = false)
    private String state = "UNKNOWN";

    @Column(name = "version")
    private String version;

    @Column(name = "native_node_id")
    private String nativeNodeId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "url_checked_at")
    private Instant urlCheckedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "url_problem")
    private ManagementUrlProblem urlProblem;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_kind")
    private BrokerConnectionException.Kind lastErrorKind;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "replica_sync")
    private Boolean replicaSync;

    @Column(name = "observed_cycle")
    private Long observedCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_brain", nullable = false)
    private SplitBrainStatus splitBrain = SplitBrainStatus.NONE;

    @Column(name = "clock_measured_at")
    private Instant clockMeasuredAt;

    @Column(name = "clock_offset_ms")
    private Long clockOffsetMs;

    @Column(name = "clock_uncertainty_ms")
    private Integer clockUncertaintyMs;

    /** A row learned from the broker's topology: named after its connector, no management URL yet. */
    public static BrokerNodeEntity discovered(UUID clusterId, String connector, String haDesignation, String nodeId) {
        BrokerNodeEntity n = new BrokerNodeEntity();
        n.clusterId = clusterId;
        n.name = connector;
        n.setEndpoint(Plane.DATA, BrokerGateway.CORE, CoreUrl.dialable(connector), Source.DISCOVERED);
        n.haDesignation = haDesignation;
        n.haGroup = nodeId;
        n.nativeNodeId = nodeId;
        return n;
    }

    /** A row created directly from a registration seed (no matching topology entry). */
    public static BrokerNodeEntity fromSeed(UUID clusterId, String name, String haDesignation, String nodeId) {
        BrokerNodeEntity n = new BrokerNodeEntity();
        n.clusterId = clusterId;
        n.name = name;
        n.haDesignation = haDesignation;
        n.haGroup = nodeId;
        n.nativeNodeId = nodeId;
        return n;
    }

    /** Discovery merge: enrich a row with what the topology reports, leaving a manual data URL and the management URL as they are. */
    public void mergeDiscovered(String connector, String haDesignation, String nodeId) {
        if (connector != null && !isDataUrlManual()) {
            setEndpoint(Plane.DATA, BrokerGateway.CORE, CoreUrl.dialable(connector), Source.DISCOVERED);
        }
        this.haDesignation = haDesignation;
        this.haGroup = nodeId;
        if (nodeId != null) {
            this.nativeNodeId = nodeId;
        }
    }

    @Override
    public String getManagementUrl() {
        return endpoint(Plane.MANAGEMENT).map(BrokerNodeEndpoint::getUrl).orElse(null);
    }

    @Override
    public String getDataUrl() {
        return endpoint(Plane.DATA).map(BrokerNodeEndpoint::getUrl).orElse(null);
    }

    @Override
    public ManagementUrlSource getUrlSource() {
        return endpoint(Plane.MANAGEMENT)
                .map(e -> ManagementUrlSource.valueOf(e.getSource().name()))
                .orElse(null);
    }

    @Override
    public boolean isDataUrlManual() {
        return endpoint(Plane.DATA).map(e -> e.getSource() == Source.MANUAL).orElse(false);
    }

    @Override
    public List<Endpoint> getEndpoints() {
        return endpoints.stream()
                .map(e -> new Endpoint(e.getPlane(), e.getProtocol(), e.getUrl()))
                .toList();
    }

    private Optional<BrokerNodeEndpoint> endpoint(Plane plane) {
        return endpoints.stream().filter(e -> e.getPlane() == plane).findFirst();
    }

    /** The node's one endpoint on a plane, replacing any it had; a {@code null} url removes it. */
    private void setEndpoint(Plane plane, String protocol, String url, Source source) {
        endpoints.removeIf(e -> e.getPlane() == plane);
        if (url != null) {
            endpoints.add(new BrokerNodeEndpoint(plane, protocol, url, source));
        }
    }

    private void setManagementUrl(String url, Source source) {
        setEndpoint(Plane.MANAGEMENT, BrokerGateway.JOLOKIA, url, source);
    }

    /**
     * A management URL the operator gave as a seed. A URL already set manually stays manual; any other
     * source becomes {@code SEED}, because the operator has now named it.
     */
    public void attachSeedUrl(String managementUrl) {
        setManagementUrl(managementUrl, getUrlSource() == ManagementUrlSource.MANUAL ? Source.MANUAL : Source.SEED);
        this.urlProblem = null;
    }

    /** A management URL built from the cluster's pattern and proved by the broker's NodeID (ADR-0175). */
    public void attachDerivedUrl(String managementUrl) {
        setManagementUrl(managementUrl, Source.DERIVED);
        this.urlProblem = null;
        this.urlCheckedAt = null;
    }

    /** The attempt to derive a management URL failed: the node has none, and says why. */
    public void recordUrlProblem(ManagementUrlProblem problem, Instant checkedAt) {
        setManagementUrl(null, null);
        this.urlProblem = problem;
        this.urlCheckedAt = checkedAt;
    }

    /** The account, the pattern or the TLS bundle changed, so the reason a URL was not derived may no longer hold. */
    public void clearUrlProblem() {
        this.urlProblem = null;
        this.urlCheckedAt = null;
    }

    /** A seed the operator no longer lists: the node keeps no URL until one is derived again. */
    public void releaseSeedUrl() {
        setManagementUrl(null, null);
        this.urlProblem = null;
    }

    /** The {@code PATCH} override: an operator supplies a reachable URL; discovery must never overwrite it. */
    public void applyManualUrl(String managementUrl) {
        setManagementUrl(managementUrl, Source.MANUAL);
        this.urlProblem = null;
    }

    /**
     * The {@code PATCH} override for the data URL: discovery stores the
     * broker-advertised connector, which is often unreachable from where Rahdar
     * runs (ADR-0026). Marks the data URL manual so discovery leaves it alone.
     */
    public void applyManualDataUrl(String dataUrl) {
        setEndpoint(Plane.DATA, BrokerGateway.CORE, CoreUrl.dialable(dataUrl), Source.MANUAL);
    }

    /** What one HA read of a broker reported; a null {@code version} or {@code nativeNodeId} leaves the stored one. */
    public record HaObservation(
            Boolean active,
            String state,
            String haDesignation,
            Boolean replicaSync,
            String version,
            String nativeNodeId) {}

    /** The refresh loop's write: HA state tagged with the cycle it was observed in (ADR-0012). */
    public void applyHaState(HaObservation observed, long observedCycle, Instant lastSeenAt) {
        this.active = observed.active();
        this.state = observed.state();
        this.haDesignation = observed.haDesignation();
        this.replicaSync = observed.replicaSync();
        this.observedCycle = observedCycle;
        if (observed.version() != null) {
            this.version = observed.version();
        }
        if (observed.nativeNodeId() != null) {
            this.nativeNodeId = observed.nativeNodeId();
        }
        this.lastSeenAt = lastSeenAt;
        this.lastError = null;
        this.lastErrorKind = null;
    }

    /** The corroborated split-brain verdict for this node's NodeID. */
    public void recordSplitBrain(SplitBrainStatus status) {
        this.splitBrain = status;
    }

    /** An unanswered tier-A probe: record it without disturbing the last-known-good HA state. */
    public void recordError(Instant seenAt, String error, BrokerConnectionException.Kind kind) {
        this.lastSeenAt = seenAt;
        this.lastError = error;
        this.lastErrorKind = kind;
    }

    /**
     * The measured disagreement between this broker's clock and Rahdar's (ADR-0053).
     *
     * <p>Persisted so a restart does not begin blind, and so an operator can see the
     * number Rahdar actually acted on rather than being told a flow timed out for
     * reasons kept in memory.
     */
    public void recordClockOffset(long offsetMs, long uncertaintyMs, Instant measuredAt) {
        this.clockOffsetMs = offsetMs;
        this.clockUncertaintyMs = (int) Math.min(uncertaintyMs, Integer.MAX_VALUE);
        this.clockMeasuredAt = measuredAt;
    }
}
