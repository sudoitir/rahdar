package io.github.sudoitir.rahdar.feature.resources.web;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.feature.resources.ConnectionControlService;
import io.github.sudoitir.rahdar.feature.resources.ConnectionControlService.CloseResult;
import io.github.sudoitir.rahdar.feature.resources.web.ConnectionViews.ConnectionCloseView;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closing connections, sessions and an address's consumers (ADR-0057), beside the
 * cross-node views the targets are listed in.
 *
 * <p>The by-id routes name a <b>node</b>, which no other mutating route in Rahdar
 * does. That is not an oversight: a connection identifier is issued by one node
 * and means nothing on another, so a cluster-wide close by id would either do
 * nothing or hit an unrelated connection. Only the address-scoped close names the
 * cluster, and it fans out and reports per node.
 *
 * <p>Every route takes {@code ?dryRun=true}, which reads the target and reports
 * who would be disconnected — client id, user, sessions, consumers, and the
 * in-flight messages that would return to their queue — without closing anything.
 * The address-scoped close is additionally checked against {@code safety.bulk-cap}
 * and answers {@code 422 bulk-cap-exceeded} unless {@code ?override=true}.
 *
 * <p>A target that has already gone answers 200 with {@code alreadyGone}, not an
 * error: the requested state holds. Authorization goes through
 * {@code ClusterAccessGuard}, so a caller with no grant gets a 404 and cannot
 * learn that the cluster exists.
 */
@RestController
@RequestMapping("/clusters/{clusterId}")
@RequiredArgsConstructor
public class ConnectionControlController {

    private final ConnectionControlService control;
    private final ResourceTargets targets;

    /**
     * Close a connection, a channel of one, the connection behind a consumer, or every consumer connection of
     * a destination. The first three name a {@code node}: a connection id is issued by one node and means
     * nothing on another. A consumer row names its channel but not its connection, so the link is walked
     * server-side: the client never supplies the connection id it did not read.
     */
    @PostMapping("/resources/{kind}/{path}:close")
    public ConnectionCloseView close(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(required = false) UUID node,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override) {
        Target target = targets.require(
                clusterId,
                kind,
                path,
                ResourceClass.CONNECTION,
                ResourceClass.CHANNEL,
                ResourceClass.CONSUMER,
                ResourceClass.DESTINATION);
        String name = target.name();
        return respond(
                switch (target.resourceClass()) {
                    case CONNECTION -> control.closeConnection(clusterId, requireNode(node), name, dryRun);
                    case CHANNEL -> control.closeSession(clusterId, requireNode(node), name, dryRun);
                    case CONSUMER -> control.closeConsumerConnection(clusterId, requireNode(node), name, dryRun);
                    default -> control.closeAddressConsumers(clusterId, name, dryRun, override);
                });
    }

    private static UUID requireNode(UUID node) {
        if (node == null) {
            throw new IllegalArgumentException(
                    "A connection, channel or consumer is closed on one node: say which with ?node.");
        }
        return node;
    }

    private static ConnectionCloseView respond(Attempt<CloseResult> attempt) {
        return switch (attempt) {
            case Attempt.Ok<CloseResult>(var value) -> ConnectionCloseView.of(value);
            case Attempt.Failed<CloseResult>(var kind, var detail) -> throw new BrokerConnectionException(kind, detail);
        };
    }
}
