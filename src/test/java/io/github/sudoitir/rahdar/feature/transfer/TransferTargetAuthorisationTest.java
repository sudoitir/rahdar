package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.queues.QueuePermissions;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.SelectionKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferPreviewRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferSelection;
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
 * Messages are sent into the target queue, so the target address the caller is checked against is the one the
 * queue is bound to, whatever address the request names (team-access spec).
 */
@ExtendWith(MockitoExtension.class)
class TransferTargetAuthorisationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @Mock
    ClusterAccessGuard access;

    @InjectMocks
    TransferService service;

    private static TransferPreviewRequest request(String targetQueue, String targetAddress) {
        return new TransferPreviewRequest(
                TransferMode.MOVE,
                "orders.in",
                UUID.randomUUID(),
                new TransferSelection(SelectionKind.ALL, null, null),
                CLUSTER,
                UUID.randomUUID(),
                targetQueue,
                targetAddress);
    }

    private static Optional<QueueLocation> boundTo(String queue, String address) {
        return Optional.of(new QueueLocation(UUID.randomUUID(), queue, address, "ANYCAST", 0));
    }

    @Test
    void anExistingTargetIsCheckedAgainstTheAddressItIsBoundToNotTheOneTheRequestNames() {
        String address =
                service.authorisedTargetAddress(request("billing.in", null), boundTo("billing.in", "billing.addr"));

        assertThat(address).isEqualTo("billing.addr");
        verify(access)
                .requireAll(
                        CLUSTER,
                        List.of(
                                new Requirement(ResourceRef.queue("billing.in"), Permissions.QUEUE_READ),
                                new Requirement(ResourceRef.address("billing.addr"), MessagePermissions.MESSAGE_SEND)));
    }

    @Test
    void anAddressOfTheCallersOwnCannotBeUsedToReachAnotherTeamsQueue() {
        // The caller may send to orders.out, not to billing.addr: the queue is theirs to reach only through the latter.
        doThrow(new NotFoundException("A queue or address named in the request does not exist."))
                .when(access)
                .requireAll(eq(CLUSTER), any());

        var request = request("billing.in", "orders.out");
        var bound = boundTo("billing.in", "billing.addr");

        assertThatThrownBy(() -> service.authorisedTargetAddress(request, bound))
                .isInstanceOf(NotFoundException.class)
                .hasMessageNotContaining("billing");
    }

    @Test
    void anAddressThatIsNotTheQueuesOwnIsRefusedOnceTheCallerMayReachTheQueue() {
        assertThatThrownBy(() -> service.authorisedTargetAddress(
                        request("orders.out", "orders.other"), boundTo("orders.out", "orders.out")))
                .isInstanceOfSatisfying(TransferRefusedException.class, e -> {
                    assertThat(e.slug()).isEqualTo("transfer-target-address");
                    assertThat(e.getMessage()).contains("orders.out");
                });
    }

    @Test
    void aTargetQueueTheBrokerWouldCreateIsCheckedAsACreationUnderItsAddress() {
        String address = service.authorisedTargetAddress(request("orders.new", "orders.addr"), Optional.empty());

        assertThat(address).isEqualTo("orders.addr");
        verify(access).requireCreate(CLUSTER, ResourceRef.queue("orders.new"), QueuePermissions.QUEUE_CREATE);
        verify(access)
                .requireAll(
                        CLUSTER,
                        List.of(new Requirement(ResourceRef.address("orders.addr"), MessagePermissions.MESSAGE_SEND)));
    }

    @Test
    void aQueueToBeCreatedWithNoAddressNamedIsBoundToAnAddressOfItsOwnName() {
        assertThat(service.authorisedTargetAddress(request("orders.new", null), Optional.empty()))
                .isEqualTo("orders.new");
    }
}
