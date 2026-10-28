package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageBytesEqualityTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @Test
    void messagesWithEqualBytesAreEqual() {
        OutboundMessage a = new OutboundMessage(
                CLUSTER, ResourceRef.destination("orders"), new byte[] {1, 2}, false, Map.of(), Map.of(), true, null);
        OutboundMessage b = new OutboundMessage(
                CLUSTER, ResourceRef.destination("orders"), new byte[] {1, 2}, false, Map.of(), Map.of(), true, null);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).hasToString(b.toString());
        assertThat(a.toString()).contains("body=2 bytes");

        PluginMessage x = new PluginMessage(
                "k",
                CLUSTER,
                SubscriptionId.of("artemis.queue", "q"),
                new MessageRef(CLUSTER, "1"),
                new byte[] {1, 2},
                false,
                Map.of(),
                Map.of(),
                1);
        PluginMessage y = new PluginMessage(
                "k",
                CLUSTER,
                SubscriptionId.of("artemis.queue", "q"),
                new MessageRef(CLUSTER, "1"),
                new byte[] {1, 2},
                false,
                Map.of(),
                Map.of(),
                1);
        PluginMessage other = new PluginMessage(
                "k",
                CLUSTER,
                SubscriptionId.of("artemis.queue", "q"),
                new MessageRef(CLUSTER, "1"),
                new byte[] {9},
                false,
                Map.of(),
                Map.of(),
                1);
        assertThat(x).isEqualTo(y).hasSameHashCodeAs(y).isNotEqualTo(other);
    }
}
