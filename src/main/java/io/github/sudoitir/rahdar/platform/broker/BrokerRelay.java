package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.Guarantee;
import io.github.sudoitir.openbroker.messaging.Receiver;
import io.github.sudoitir.openbroker.messaging.ReceiverOptions;
import io.github.sudoitir.openbroker.messaging.SendTarget;
import io.github.sudoitir.openbroker.messaging.Sender;
import io.github.sudoitir.openbroker.messaging.SenderOptions;
import io.github.sudoitir.openbroker.messaging.Transport;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.Scope;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Relays between two nodes through the broker SDK's transport (ADR-0097, core-transport spec): a
 * receiver on the source and a transacted sender on the target, each on its own session, so a relay
 * never holds what an operator's browse or a capture drain is waiting for.
 */
@Component
@RequiredArgsConstructor
public class BrokerRelay {

    private final BrokerGateway gateway;

    /** A registered node of a cluster. */
    public record Endpoint(UUID clusterId, UUID nodeId) {}

    /**
     * A relay between two nodes along {@code route}. Close it when the run ends or either node fails;
     * the broker rolls back whatever it had not committed.
     *
     * @throws BrokerConnectionException when either node cannot be reached
     */
    public RelayLink link(Endpoint source, Endpoint target, RelayLink.Route route) {
        try {
            ClusterConnection from = gateway.cluster(source.clusterId());
            ClusterConnection to = gateway.cluster(target.clusterId());
            ReceiverOptions options = route.staged()
                    ? ReceiverOptions.consume().withGuarantee(Guarantee.TRANSACTED)
                    : browse(from, route);
            String owner = "relay " + route.sourceQueue() + " to " + route.targetQueue();
            Supplier<Receiver> receivers = () -> {
                Receiver receiver = transport(from)
                        .receiver(
                                ResourceIds.of(from, ResourceClass.SUBSCRIPTION, route.sourceQueue()),
                                Scope.node(BrokerGateway.idOf(source.nodeId())),
                                options);
                gateway.label(receiver.id(), owner);
                return receiver;
            };
            Sender sender = transport(to)
                    .sender(
                            SendTarget.subscription(
                                    ResourceIds.of(to, ResourceClass.SUBSCRIPTION, route.targetQueue())),
                            Scope.node(BrokerGateway.idOf(target.nodeId())),
                            SenderOptions.defaults().withGuarantee(Guarantee.TRANSACTED));
            gateway.label(sender.id(), owner);
            return new RelayLink(receivers, sender, route);
        } catch (BrokerException e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Could not open a relay session: " + e.getMessage(), e);
        }
    }

    private static ReceiverOptions browse(ClusterConnection from, RelayLink.Route route) {
        ReceiverOptions browse = ReceiverOptions.browse(BrokerMessages.filter(from, route.browseFilter()));
        return route.producedAtOrBefore().map(browse::producedAtOrBefore).orElse(browse);
    }

    private static Transport transport(ClusterConnection c) {
        Optional<Transport> transport = c.transport();
        return transport.orElseThrow(() -> new BrokerConnectionException(
                BrokerConnectionException.Kind.UNREACHABLE,
                "This cluster has no data-plane connection, so messages cannot be relayed through it."));
    }
}
