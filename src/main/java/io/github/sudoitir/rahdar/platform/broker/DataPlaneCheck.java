package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.notification.BrokerEvent;
import io.github.sudoitir.openbroker.notification.Gap;
import io.github.sudoitir.openbroker.notification.NotificationListener;
import io.github.sudoitir.openbroker.notification.Notifications;
import io.github.sudoitir.openbroker.notification.StreamState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Checks a cluster's data plane before it is registered, or with an account an operator is about to
 * save: on every node that takes data-plane connections, whether the broker lets the account in, and
 * whether it may subscribe to the broker's notifications (ADR-0026, D5). One subscription per node
 * answers both, and is closed as soon as each node has answered.
 *
 * <p>A check that stayed silent about the data plane is how a wrong account, or a management account
 * the broker reserves for itself, reached a registered cluster and failed there, where the operator
 * had no obvious way back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataPlaneCheck {

    /** How long the nodes have to answer. */
    private static final Duration SETTLE = Duration.ofSeconds(10);

    private static final Duration POLL = Duration.ofMillis(50);

    private final BrokerGateway gateway;

    /**
     * Each endpoint's result, in the order given, and the cluster's notification verdict.
     *
     * @param accounts what the account came to on each endpoint
     */
    public record Result(List<AccountResult> accounts, SubscriptionVerdict verdict) {}

    /**
     * @param endpoints the nodes, in the order their results are wanted
     * @param core the data-plane account to try — for an unregistered cluster straight from the request,
     *     never from the vault
     */
    public Result check(List<NodeEndpoint> endpoints, CoreConnectionSettings core) {
        List<NodeSpec> nodes = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            NodeEndpoint e = endpoints.get(i);
            if (asked(e)) {
                nodes.add(new NodeSpec(
                        idOf(i), List.of(new Endpoint(Plane.DATA, BrokerGateway.CORE, CoreUrl.dialable(e.dataUrl())))));
            }
        }
        List<AccountResult> accounts = new ArrayList<>(
                endpoints.stream().map(e -> AccountResult.NOT_TRIED).toList());
        if (nodes.isEmpty()) {
            // Not a failure: discovery may simply not have learned a data-plane address yet.
            return new Result(accounts, new SubscriptionVerdict.NotAttempted());
        }
        try (ClusterConnection connection = gateway.dataPlane(nodes, core)) {
            Optional<Notifications> notifications = connection.notifications();
            if (notifications.isEmpty()) {
                return new Result(accounts, new SubscriptionVerdict.NotAttempted());
            }
            Map<NodeId, StreamState> states = settled(notifications.get(), nodes);
            for (NodeSpec node : nodes) {
                accounts.set(indexOf(node.id()), accountOf(states.get(node.id())));
            }
            return new Result(accounts, verdict(nodes, states));
        }
    }

    /**
     * Whether a node is asked: it has a data-plane address, and is not a passive backup, which opens no
     * data-plane acceptor, so asking it would only report a refusal that means nothing.
     */
    public static boolean asked(NodeEndpoint node) {
        return CoreUrl.dialable(node.dataUrl()) != null && !(node.isBackup() && !node.active());
    }

    /** Each node's stream once it is established or has failed, or as it stands when time runs out. */
    private static Map<NodeId, StreamState> settled(Notifications notifications, List<NodeSpec> nodes) {
        try (AutoCloseable _ = notifications.subscribe(Scope.whole(), IGNORED)) {
            Instant until = Instant.now().plus(SETTLE);
            Map<NodeId, StreamState> states = notifications.state();
            while (!allSettled(states, nodes) && Instant.now().isBefore(until)) {
                Thread.sleep(POLL);
                states = notifications.state();
            }
            return Map.copyOf(states);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return notifications.state();
        } catch (Exception e) {
            log.debug("Closing a data-plane check: {}", e.getMessage());
            return notifications.state();
        }
    }

    private static boolean allSettled(Map<NodeId, StreamState> states, List<NodeSpec> nodes) {
        return nodes.stream().map(n -> states.get(n.id())).allMatch(DataPlaneCheck::settled);
    }

    private static boolean settled(StreamState state) {
        return state != null
                && (state.phase() == StreamState.Phase.ACTIVE
                        || state.lastFailure().isPresent());
    }

    /** A refused account is {@code REJECTED}; a stream refused only its subscription still let it in. */
    private static AccountResult accountOf(StreamState state) {
        if (state == null) {
            return AccountResult.UNREACHABLE;
        }
        if (state.phase() == StreamState.Phase.ACTIVE) {
            return AccountResult.ACCEPTED;
        }
        String reason = state.lastFailure().map(BrokerException::reason).orElse("");
        return switch (reason) {
            case ErrorReasons.CREDENTIALS_REJECTED -> AccountResult.REJECTED;
            case ErrorReasons.NOT_AUTHORIZED -> AccountResult.ACCEPTED;
            default -> AccountResult.UNREACHABLE;
        };
    }

    private static SubscriptionVerdict verdict(List<NodeSpec> nodes, Map<NodeId, StreamState> states) {
        long active = nodes.stream()
                .map(n -> states.get(n.id()))
                .filter(s -> s != null && s.phase() == StreamState.Phase.ACTIVE)
                .count();
        if (active > 0) {
            return new SubscriptionVerdict.Connected((int) active, Instant.now());
        }
        return nodes.stream()
                .map(n -> states.get(n.id()))
                .filter(s -> s != null && s.lastFailure().isPresent())
                .map(s -> s.lastFailure().orElseThrow())
                .findFirst()
                .<SubscriptionVerdict>map(
                        e -> new SubscriptionVerdict.Failed(SubscriptionVerdict.kindOf(e), e.getMessage()))
                .orElseGet(() -> new SubscriptionVerdict.Failed(
                        SubscriptionVerdict.Kind.UNREACHABLE, "No node answered within " + SETTLE.toSeconds() + "s."));
    }

    private static NodeId idOf(int index) {
        return NodeId.of("node-" + index);
    }

    private static int indexOf(NodeId id) {
        return Integer.parseInt(id.value().substring("node-".length()));
    }

    private static final NotificationListener IGNORED = new NotificationListener() {
        @Override
        public void onEvent(BrokerEvent event) {
            // a check reads the stream's state, not what it carries
        }

        @Override
        public void onGap(Gap gap) {
            // as above
        }
    };
}
