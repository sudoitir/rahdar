package io.github.sudoitir.rahdar.feature.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Who sees a broker event on the stream: the readers of its address, and of the name it routes to. */
class BrokerEventStreamGateTest {

    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private final BrokerEventStreamGate gate = new BrokerEventStreamGate(permissions);
    private final RahdarPrincipal principal = RahdarPrincipal.live(UUID.randomUUID(), "u", false);
    private final UUID clusterId = UUID.randomUUID();

    private boolean mayRead(String json) {
        return gate.mayRead(principal, clusterId, JsonMapper.builder().build().readTree(json));
    }

    @Test
    void anEventAboutNoAddressBelongsToTheCluster() {
        assertThat(mayRead("{\"kind\":\"CONNECTION_CREATED\"}")).isFalse();

        when(permissions.can(principal, clusterId, Permissions.CLUSTER_READ)).thenReturn(true);
        assertThat(mayRead("{\"kind\":\"CONNECTION_CREATED\"}")).isTrue();
    }

    @Test
    void anEventIsReadThroughItsAddress() {
        when(permissions.can(principal, clusterId, ResourceRef.destination("orders"), Permissions.DESTINATION_READ))
                .thenReturn(true);

        assertThat(mayRead("{\"address\":\"orders\"}")).isTrue();
        assertThat(mayRead("{\"address\":\"billing\"}")).isFalse();
    }

    @Test
    void anEventNamingAQueueNeedsThatQueueToo() {
        when(permissions.can(eq(principal), eq(clusterId), eq(ResourceRef.destination("orders")), any()))
                .thenReturn(true);
        String event = "{\"address\":\"orders\",\"routingName\":\"billing.in\"}";

        assertThat(mayRead(event)).isFalse();

        when(permissions.can(
                        principal, clusterId, ResourceRef.subscription("billing.in"), Permissions.SUBSCRIPTION_READ))
                .thenReturn(true);
        assertThat(mayRead(event)).isTrue();
    }
}
