package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard.Requirement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What a refused resource looks like (team-access spec): not found when it cannot be read, forbidden and naming
 * the permission when it can, and every resource of a composite operation checked before anything is done.
 */
class ClusterAccessGuardTest {

    private final UUID cluster = UUID.randomUUID();
    private final PermissionResolver perm = mock(PermissionResolver.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ClusterAccessGuard guard = new ClusterAccessGuard(perm, events);

    private static final ResourceRef ORDERS = ResourceRef.subscription("orders.in");
    private static final ResourceRef BILLING = ResourceRef.subscription("billing.in");
    private static final ResourceRef BILLING_ADDRESS = ResourceRef.destination("billing.in");

    private void canRead(ResourceRef resource) {
        when(perm.can(cluster, resource, resource.readPermission())).thenReturn(true);
    }

    private void canDo(ResourceRef resource, String permission) {
        canRead(resource);
        when(perm.can(cluster, resource, permission)).thenReturn(true);
    }

    @Test
    void aResourceThatMayBeReadAndActedOnPasses() {
        canDo(ORDERS, "subscription:purge");

        assertThatCode(() -> guard.requireResource(cluster, ORDERS, "subscription:purge"))
                .doesNotThrowAnyException();
    }

    @Test
    void aResourceThatCannotBeReadIsNotFoundLikeAMissingOne() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING, "subscription:purge"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("subscription", "billing.in").getMessage());
    }

    @Test
    void anUnreadableResourceOfAClusterTheCallerCannotSeeIsTheClusterNotFound() {
        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING, "subscription:purge"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("cluster", cluster).getMessage());
    }

    @Test
    void aReadableResourceWithoutThePermissionIsForbiddenNamingBoth() {
        canRead(ORDERS);

        assertThatThrownBy(() -> guard.requireResource(cluster, ORDERS, "subscription:purge"))
                .isInstanceOfSatisfying(ResourceForbiddenException.class, e -> {
                    assertThat(e.permission()).isEqualTo("subscription:purge");
                    assertThat(e.kind()).isEqualTo("subscription");
                    assertThat(e.name()).isEqualTo("orders.in");
                    assertThat(e.getMessage()).contains("subscription:purge", "orders.in");
                });
    }

    @Test
    void anAddressIsReadThroughAddressRead() {
        canRead(BILLING_ADDRESS);

        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING_ADDRESS, "message:send"))
                .isInstanceOfSatisfying(
                        ResourceForbiddenException.class,
                        e -> assertThat(e.kind()).isEqualTo("destination"));
    }

    @Test
    void aCompositeIsCheckedForReadsBeforeAnyRefusalAndNamesNoneOfTheUnreadable() {
        // The source is readable but not movable; the target cannot be read at all.
        canRead(ORDERS);
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        List<Requirement> requirements =
                List.of(new Requirement(ORDERS, "message:move"), new Requirement(BILLING_ADDRESS, "message:send"));

        assertThatThrownBy(() -> guard.requireAll(cluster, requirements))
                .isInstanceOf(NotFoundException.class)
                .hasMessageNotContaining("billing.in")
                .hasMessageNotContaining("orders.in");
    }

    @Test
    void aCompositeWhoseResourcesAreAllReadableIsRefusedOnTheFirstMissingPermission() {
        canDo(ORDERS, "message:move");
        canRead(BILLING_ADDRESS);

        assertThatThrownBy(() -> guard.requireAll(
                        cluster,
                        List.of(
                                new Requirement(ORDERS, "message:move"),
                                new Requirement(BILLING_ADDRESS, "message:send"))))
                .isInstanceOfSatisfying(
                        ResourceForbiddenException.class,
                        e -> assertThat(e.permission()).isEqualTo("message:send"));
    }

    @Test
    void mayAllIsTheSameQuestionWithoutTheRefusal() {
        canDo(ORDERS, "subscription:purge");
        canRead(BILLING);

        assertThat(guard.mayAll(cluster, List.of(new Requirement(ORDERS, "subscription:purge"))))
                .isTrue();
        assertThat(guard.mayAll(cluster, List.of(new Requirement(BILLING, "subscription:purge"))))
                .isFalse();
    }

    // ---- creating --------------------------------------------------------------------------------

    @Test
    void aNameTheCallerMayCreateIsAllowedWithoutBeingReadableFirst() {
        when(perm.can(cluster, ResourceRef.subscription("orders.retry"), "subscription:create"))
                .thenReturn(true);

        assertThatCode(() ->
                        guard.requireCreate(cluster, ResourceRef.subscription("orders.retry"), "subscription:create"))
                .doesNotThrowAnyException();
    }

    @Test
    void aNameOutsideTheCallersPatternsIsRefusedNamingWhereTheyMayCreate() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);
        when(perm.patternsHolding(cluster, ResourceType.SUBSCRIPTION, "subscription:create"))
                .thenReturn(List.of("orders.#"));

        assertThatThrownBy(() ->
                        guard.requireCreate(cluster, ResourceRef.subscription("misc.temp"), "subscription:create"))
                .isInstanceOfSatisfying(ResourceForbiddenException.class, e -> {
                    assertThat(e.permission()).isEqualTo("subscription:create");
                    assertThat(e.name()).isEqualTo("misc.temp");
                    assertThat(e.getMessage()).contains("subscription:create", "misc.temp", "orders.#");
                });
    }

    @Test
    void aCallerWithNoPatternsIsToldNothingAboutWhereTheyMayCreate() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() ->
                        guard.requireCreate(cluster, ResourceRef.subscription("misc.temp"), "subscription:create"))
                .isInstanceOfSatisfying(
                        ResourceForbiddenException.class,
                        e -> assertThat(e.getMessage()).doesNotContain("under"));
    }

    @Test
    void creatingOnAClusterTheCallerCannotSeeIsTheClusterNotFound() {
        ResourceRef misc = ResourceRef.subscription("misc.temp");

        assertThatThrownBy(() -> guard.requireCreate(cluster, misc, "subscription:create"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("cluster", cluster).getMessage());
    }

    // ---- visibility ------------------------------------------------------------------------------

    @Test
    void aClusterTheCallerCannotSeeIsNotFoundToList() {
        assertThatThrownBy(() -> guard.requireVisible(cluster)).isInstanceOf(NotFoundException.class);

        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatCode(() -> guard.requireVisible(cluster)).doesNotThrowAnyException();
    }

    @Test
    void aPatternTheCallerHoldsOnEveryNameOfPasses() {
        when(perm.canOnAll(cluster, ResourceType.SUBSCRIPTION, "orders.#", "capture:write"))
                .thenReturn(true);

        assertThatCode(() -> guard.requireOnAll(cluster, ResourceType.SUBSCRIPTION, "orders.#", "capture:write"))
                .doesNotThrowAnyException();
    }

    @Test
    void aPatternThatCouldReachOtherNamesIsForbiddenAndAsksForANarrowerOne() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireOnAll(cluster, ResourceType.SUBSCRIPTION, "#", "capture:write"))
                .isInstanceOfSatisfying(ResourceForbiddenException.class, e -> {
                    assertThat(e.permission()).isEqualTo("capture:write");
                    assertThat(e.name()).isEqualTo("#");
                    assertThat(e.getMessage()).contains("Narrow it");
                });
    }

    @Test
    void aPatternOnAClusterTheCallerCannotSeeIsTheClusterNotFound() {
        assertThatThrownBy(() -> guard.requireOnAll(cluster, ResourceType.SUBSCRIPTION, "#", "capture:write"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("cluster", cluster).getMessage());
    }

    // ---- refusals are published for the audit trail ----------------------------------------------------

    @Test
    void aForbiddenResourceIsRefusedAsForbidden() {
        canRead(ORDERS);

        assertThatThrownBy(() -> guard.requireResource(cluster, ORDERS, "subscription:purge"))
                .isInstanceOf(ResourceForbiddenException.class);

        verify(events).publishEvent(new AccessRefused(cluster, "subscription:purge", ORDERS, false));
    }

    @Test
    void anUnreadableResourceIsRefusedAsHiddenOnTheReadPermission() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING, "subscription:purge"))
                .isInstanceOf(NotFoundException.class);

        verify(events).publishEvent(new AccessRefused(cluster, "subscription:read", BILLING, true));
    }

    @Test
    void aClusterTheCallerCannotSeeIsRefusedAsHidden() {
        assertThatThrownBy(() -> guard.requireCluster(cluster, "cluster:write")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> guard.requireVisible(cluster)).isInstanceOf(NotFoundException.class);

        verify(events).publishEvent(new AccessRefused(cluster, "cluster:write", null, true));
        verify(events).publishEvent(new AccessRefused(cluster, "cluster:read", null, true));
    }

    @Test
    void aCheckThatPassesPublishesNothing() {
        canDo(ORDERS, "subscription:purge");

        guard.requireResource(cluster, ORDERS, "subscription:purge");

        verify(events, never()).publishEvent(ArgumentMatchers.any(AccessRefused.class));
    }
}
