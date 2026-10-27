package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.ConnectOptions;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.ConnectOptionsCustomizer;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * What Rahdar sets on every connection over the starter's options (ADR-0212): the live
 * {@link BrokerTimeouts}, so a change in Settings reaches the next call without reconnecting a cluster
 * (ADR-0010), and no context propagation, because a callback on the SDK's threads must never carry the
 * context of the operator whose request happened to open the connection.
 */
@Component
class BrokerConnectOptionsCustomizer implements ConnectOptionsCustomizer {

    private final BrokerTimeouts timeouts;

    BrokerConnectOptionsCustomizer(BrokerTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    @Override
    public ConnectOptions customize(String connection, String system, ConnectOptions options) {
        return options.withTimeouts(timeouts).withContextPropagation(UnaryOperator.identity());
    }
}
