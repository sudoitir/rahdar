package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.LinkEvent;
import io.github.sudoitir.openbroker.messaging.LinkState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** What diagnostics says about broker links, and that it keeps nothing for a link that is gone (ADR-0211). */
class BrokerLinksTest {

    private final UUID cluster = UUID.randomUUID();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BrokerLinks links = new BrokerLinks(meters);

    private void event(String link, LinkState state) {
        links.changed(
                cluster,
                new LinkEvent(link, NodeId.of("node-1"), state, Optional.empty(), Optional.empty(), Instant.now()));
    }

    @Test
    void aProcessorsLanesTakeItsNameAndItsNameGoesWhenTheyClose() {
        event("p1-0", LinkState.OPEN);
        event("p1-1", LinkState.OPEN);
        links.label("p1", "plugin acme/work");

        assertThat(links.of("p1")).extracting(BrokerLinks.Link::owner).containsOnly("plugin acme/work");

        event("p1-0", LinkState.CLOSED);
        assertThat(links.named()).isEqualTo(1);
        event("p1-1", LinkState.CLOSED);
        assertThat(links.named()).isZero();
    }

    @Test
    void aLinkClosedBeforeItIsNamedLeavesNothingBehind() {
        event("s1", LinkState.OPEN);
        event("s1", LinkState.CLOSED);

        links.label("s1", "relay a to b");

        assertThat(links.named()).isZero();
    }

    @Test
    void aLinkThatIsNotOpenDegradesHealthAndIsCounted() {
        event("r1", LinkState.RECOVERING);

        assertThat(links.health(true).getStatus()).isNotEqualTo(Status.UP);
        assertThat(meters.get("rahdar.broker.links")
                        .tag("state", "recovering")
                        .gauge()
                        .value())
                .isEqualTo(1);

        event("r1", LinkState.OPEN);
        assertThat(links.health(true).getStatus()).isEqualTo(Status.UP);
        assertThat(meters.get("rahdar.broker.link.transitions")
                        .tag("state", "recovering")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
