package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PluginMessageTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final SubscriptionId SOURCE = SubscriptionId.of("artemis.queue", "q");

    private static PluginMessage message(byte[] body) {
        return new PluginMessage(
                "k", CLUSTER, SOURCE, new MessageRef(NODE, "1"), body, true, Map.of("h", 1), Map.of("p", "v"), 1);
    }

    @Test
    void bodyTextDecodesUtf8() {
        assertThat(message("héllo".getBytes(StandardCharsets.UTF_8)).bodyText()).isEqualTo("héllo");
    }

    @Test
    void equalityComparesEveryComponent() {
        PluginMessage base = message(new byte[] {1});

        assertThat(base)
                .isEqualTo(base)
                .isEqualTo(message(new byte[] {1}))
                .isNotEqualTo("not a message")
                .isNotEqualTo(new PluginMessage(
                        "x",
                        CLUSTER,
                        SOURCE,
                        new MessageRef(NODE, "1"),
                        new byte[] {1},
                        true,
                        Map.of("h", 1),
                        Map.of("p", "v"),
                        1))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        UUID.randomUUID(),
                        SOURCE,
                        new MessageRef(NODE, "1"),
                        new byte[] {1},
                        true,
                        Map.of("h", 1),
                        Map.of("p", "v"),
                        1))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        CLUSTER,
                        SOURCE,
                        new MessageRef(UUID.randomUUID(), "1"),
                        new byte[] {1},
                        true,
                        Map.of("h", 1),
                        Map.of("p", "v"),
                        1))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        CLUSTER,
                        SubscriptionId.of("artemis.queue", "other"),
                        new MessageRef(NODE, "1"),
                        new byte[] {1},
                        true,
                        Map.of("h", 1),
                        Map.of("p", "v"),
                        1))
                .isNotEqualTo(message(new byte[] {2}))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        CLUSTER,
                        SOURCE,
                        new MessageRef(NODE, "1"),
                        new byte[] {1},
                        false,
                        Map.of("h", 1),
                        Map.of("p", "v"),
                        1))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        CLUSTER,
                        SOURCE,
                        new MessageRef(NODE, "1"),
                        new byte[] {1},
                        true,
                        Map.of("h", 2),
                        Map.of("p", "v"),
                        1))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        CLUSTER,
                        SOURCE,
                        new MessageRef(NODE, "1"),
                        new byte[] {1},
                        true,
                        Map.of("h", 1),
                        Map.of("p", "w"),
                        1))
                .isNotEqualTo(new PluginMessage(
                        "k",
                        CLUSTER,
                        SOURCE,
                        new MessageRef(NODE, "1"),
                        new byte[] {1},
                        true,
                        Map.of("h", 1),
                        Map.of("p", "v"),
                        2));
    }

    @Test
    void toStringReportsBodyLengthNeverContent() {
        assertThat(message("secret".getBytes(StandardCharsets.UTF_8)).toString())
                .contains("body=6 bytes")
                .doesNotContain("secret");
        assertThat(message(null).toString()).contains("body=null");
    }
}
