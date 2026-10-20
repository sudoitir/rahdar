package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A registered broker node as other modules read it: identity, endpoints and the last observed
 * HA state. Nodes are written only by this module; see {@link ClusterDirectory}.
 */
public interface ClusterNode {

    UUID getId();

    UUID getClusterId();

    String getName();

    /** Where the node is managed; {@code null} for a node Rahdar cannot manage yet. */
    String getManagementUrl();

    /** Where the node sends and receives messages; {@code null} while none is known. */
    String getDataUrl();

    /** Every endpoint the node is reached at, with its plane and protocol. */
    List<Endpoint> getEndpoints();

    /** The broker's own id for the node, shared by a primary and its backup; {@code null} until first read. */
    String getNativeNodeId();

    /** Whether the node reported itself live at the last read; {@code null} before any read. */
    Boolean getActive();

    String getLastError();

    Boolean getReplicaSync();

    /** How the node is configured for high availability: {@code PRIMARY}, {@code BACKUP} or {@code NONE}. */
    String getHaDesignation();

    String getState();

    String getVersion();

    Instant getLastSeenAt();

    /** The last split-brain verdict for this node's NodeID, written by the replica that owns the cluster. */
    SplitBrainStatus getSplitBrain();

    /** The scrape cycle the HA state was last observed in; {@code null} before any read. */
    Long getObservedCycle();

    /** Where the management URL came from; {@code null} while the node has none. */
    ManagementUrlSource getUrlSource();

    /** Why the node has no management URL; {@code null} once it has one, or before an attempt. */
    ManagementUrlProblem getUrlProblem();

    /** The class of the last probe failure, set with {@link #getLastError()}. */
    BrokerConnectionException.Kind getLastErrorKind();

    /** An operator set the data URL, so discovery must not replace it. */
    boolean isDataUrlManual();
}
