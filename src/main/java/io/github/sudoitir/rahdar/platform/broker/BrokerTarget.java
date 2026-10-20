package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.model.ClusterMember;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import java.util.List;

/**
 * One node, reached through a broker SDK connection: what a probe, a check or a per-node command
 * works on. Failures surface as Rahdar's broker exceptions ({@link BrokerFailures}).
 */
public record BrokerTarget(ClusterConnection connection, NodeId node) {

    public Scope scope() {
        return Scope.node(node);
    }

    /** One read of the node's HA attributes. */
    public NodeDescription describe() {
        try {
            return connection.nodes().describe(scope()).orThrow().getFirst();
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }

    /** The cluster's members as this node knows them. */
    public List<ClusterMember> members() {
        try {
            return connection.nodes().members(scope()).orThrow();
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }

    /** Runs an operation on this node alone, in Rahdar's failures. */
    public OperationResult execute(ResourceId resource, Operation operation) {
        try {
            return connection.operations().execute(resource, operation, scope()).orThrow();
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }
}
