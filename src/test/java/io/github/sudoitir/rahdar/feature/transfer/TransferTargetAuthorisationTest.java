package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.feature.transfer.TransferService.Target;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard.Requirement;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Messages are sent into the target subscription, so the destination the caller is checked against is the one the
 * subscription is bound to, whatever destination the request names (team-access spec).
 */
@ExtendWith(MockitoExtension.class)
class TransferTargetAuthorisationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @Mock
    ClusterAccessGuard access;

    @InjectMocks
    TransferService service;

    private static Target target(String subscription, String destination) {
        return new Target(subscription, destination);
    }

    private static Optional<QueueLocation> boundTo(String queue, String address) {
        return Optional.of(new QueueLocation(UUID.randomUUID(), queue, address, "ANYCAST", 0));
    }

    @Test
    void anExistingTargetIsCheckedAgainstTheAddressItIsBoundToNotTheOneTheRequestNames() {
        String address = service.authorisedTargetAddress(
                CLUSTER, target("billing.in", null), boundTo("billing.in", "billing.addr"));

        assertThat(address).isEqualTo("billing.addr");
        verify(access)
                .requireAll(
                        CLUSTER,
                        List.of(
                                new Requirement(ResourceRef.subscription("billing.in"), Permissions.SUBSCRIPTION_READ),
                                new Requirement(
                                        ResourceRef.destination("billing.addr"), MessagePermissions.MESSAGE_SEND)));
    }

    @Test
    void anAddressOfTheCallersOwnCannotBeUsedToReachAnotherTeamsQueue() {
        // The caller may send to orders.out, not to billing.addr: the queue is theirs to reach only through the latter.
        doThrow(new NotFoundException("A queue or address named in the request does not exist."))
                .when(access)
                .requireAll(eq(CLUSTER), any());

        var target = target("billing.in", "orders.out");
        var bound = boundTo("billing.in", "billing.addr");

        assertThatThrownBy(() -> service.authorisedTargetAddress(CLUSTER, target, bound))
                .isInstanceOf(NotFoundException.class)
                .hasMessageNotContaining("billing");
    }

    @Test
    void anAddressThatIsNotTheQueuesOwnIsRefusedOnceTheCallerMayReachTheQueue() {
        assertThatThrownBy(() -> service.authorisedTargetAddress(
                        CLUSTER, target("orders.out", "orders.other"), boundTo("orders.out", "orders.out")))
                .isInstanceOfSatisfying(TransferRefusedException.class, e -> {
                    assertThat(e.slug()).isEqualTo("transfer-target-destination");
                    assertThat(e.getMessage()).contains("orders.out");
                });
    }

    @Test
    void aTargetQueueTheBrokerWouldCreateIsCheckedAsACreationUnderItsAddress() {
        String address =
                service.authorisedTargetAddress(CLUSTER, target("orders.new", "orders.addr"), Optional.empty());

        assertThat(address).isEqualTo("orders.addr");
        verify(access)
                .requireCreate(
                        CLUSTER, ResourceRef.subscription("orders.new"), ResourcePermissions.SUBSCRIPTION_CREATE);
        verify(access)
                .requireAll(
                        CLUSTER,
                        List.of(new Requirement(
                                ResourceRef.destination("orders.addr"), MessagePermissions.MESSAGE_SEND)));
    }

    @Test
    void aQueueToBeCreatedWithNoAddressNamedIsBoundToAnAddressOfItsOwnName() {
        assertThat(service.authorisedTargetAddress(CLUSTER, target("orders.new", null), Optional.empty()))
                .isEqualTo("orders.new");
    }
}
