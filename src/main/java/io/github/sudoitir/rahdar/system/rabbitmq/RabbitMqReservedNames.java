package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.rahdar.platform.broker.ReservedNames;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** RabbitMQ's own exchanges and queues, such as {@code amq.direct}, {@code amq.topic} and {@code amq.gen-…}. */
@Component
class RabbitMqReservedNames implements ReservedNames {

    private static final String PREFIX = "amq.";

    @Override
    public String system() {
        return RabbitMqModule.SYSTEM;
    }

    @Override
    public Optional<String> reason(String name) {
        return name != null && name.startsWith(PREFIX)
                ? Optional.of("'" + name + "' starts with '" + PREFIX + "', which RabbitMQ keeps for its own exchanges"
                        + " and queues.")
                : Optional.empty();
    }
}
