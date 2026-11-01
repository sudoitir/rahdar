package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RabbitMQ's own exchanges and queues all start with {@code amq.}, and nothing else is reserved. */
class RabbitMqReservedNamesTest {

    private final RabbitMqReservedNames names = new RabbitMqReservedNames();

    @Test
    void theBrokersOwnNamesAreReserved() {
        for (String own : new String[] {"amq.direct", "amq.topic", "amq.gen-JzTY20BRgKO-HjmUJj0wLg"}) {
            assertThat(names.reason(own)).get().asString().contains(own).contains("amq.");
        }
    }

    @Test
    void anOrdinaryNameIsNot() {
        assertThat(names.reason("orders")).isEmpty();
        assertThat(names.reason("amqp")).isEmpty();
        assertThat(names.reason(null)).isEmpty();
    }

    @Test
    void itBelongsToRabbitMq() {
        assertThat(names.system()).isEqualTo("rabbitmq");
    }
}
