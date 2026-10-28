package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.platform.broker.ReservedNames;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Kafka's internal topics, such as {@code __consumer_offsets}, {@code __transaction_state} and {@code __share_group_state}. */
@Component
class KafkaReservedNames implements ReservedNames {

    private static final String INTERNAL_PREFIX = "__";

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public Optional<String> reason(String name) {
        return name != null && name.startsWith(INTERNAL_PREFIX)
                ? Optional.of("'" + name + "' starts with '" + INTERNAL_PREFIX
                        + "', which Kafka keeps for its internal topics.")
                : Optional.empty();
    }
}
