package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.platform.broker.SystemExtension;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Connection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.ConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.KafkaConnectionDetail;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registers clusters of a system that is reached through its driver rather than through Jolokia seeds
 * (ADR-0213). The system reaches the cluster and reads what it found; {@link ClusterService} stores the
 * cluster, its nodes, its account and its identity claim in one transaction, and audits the attempt.
 */
public interface ClusterRegistrar extends SystemExtension {

    /** One broker or controller the check found, as it is stored. */
    record DiscoveredNode(String nativeId, String name, List<Endpoint> endpoints) {}

    /**
     * What a check found. {@code view} is what the client sees; {@code nodes} are the nodes reached, to store.
     * The cluster's own id is {@code view.clusterId()}.
     */
    record Checked(ConnectionCheck view, List<DiscoveredNode> nodes) {

        public boolean passed() {
            return view.failure() == null;
        }
    }

    /** The account a connection signs in with, held in memory only; the secret is sealed when it is stored. */
    record Account(String username, String secret) {

        /** The secret is never printed. */
        @Override
        public String toString() {
            return "Account[username=" + username + ", secret=" + SecretRedactor.MASK + "]";
        }
    }

    /** A connection resolved against what is stored: the connection to check, and the account to store, if it changed. */
    record Resolved(Connection connection, Account account) {}

    /**
     * Reaches the cluster once with this connection and reports; persists nothing and never throws for a cluster
     * that cannot be reached, which is a failed check.
     *
     * @throws IllegalArgumentException when the connection is malformed for the system
     */
    Checked check(Connection connection);

    /** The account the connection carries, or null when it carries none. */
    Account accountOf(Connection connection);

    /** A name for a cluster registered without one, such as its first bootstrap server's host. */
    String defaultName(Connection connection);

    /** What an audit entry records of the connection: where it points and how, never a secret. */
    Map<String, Object> auditParams(Connection connection);

    /** Stores the system's own rows for the cluster, replacing any, in the caller's transaction. */
    void store(UUID clusterId, Connection connection);

    /** How the registered cluster is reached, for the connection settings: never a secret. */
    KafkaConnectionDetail detail(UUID clusterId);

    /**
     * The registered cluster's connection with the edit laid over it.
     *
     * @throws IllegalArgumentException when a change needs a secret that was not given again
     */
    Resolved edited(UUID clusterId, ConnectionUpdate update);
}
