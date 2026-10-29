package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Kafka's internal topics all start with two underscores, and nothing else is reserved. */
class KafkaReservedNamesTest {

    private final KafkaReservedNames names = new KafkaReservedNames();

    @Test
    void theInternalTopicsAreReserved() {
        for (String internal : new String[] {"__consumer_offsets", "__transaction_state", "__share_group_state"}) {
            assertThat(names.reason(internal))
                    .get()
                    .asString()
                    .contains(internal)
                    .contains("internal topics");
        }
    }

    @Test
    void anOrdinaryNameIsNot() {
        assertThat(names.reason("orders")).isEmpty();
        assertThat(names.reason("_single")).isEmpty();
        assertThat(names.reason(null)).isEmpty();
    }
}
