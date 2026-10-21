package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import io.github.sudoitir.rahdar.kernel.gate.GateLease;
import io.github.sudoitir.rahdar.kernel.gate.GateLeases;
import io.github.sudoitir.rahdar.kernel.gate.GateScope;
import io.github.sudoitir.rahdar.kernel.gate.GateTicket;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Work handed to another thread acts as the operator who started it, and only while they still may (ADR-0093). */
class OperatorHandoffTest extends PostgresIntegrationTest {

    @Autowired
    OperatorHandoff handoff;

    @MockitoSpyBean
    GateLeases leases;

    @Autowired
    ActorResolver actors;

    @Autowired
    PermissionResolver perm;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    GrantLoader grants;

    private UUID userId;

    @BeforeEach
    void signIn() {
        userId = OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anotherThreadActsAsTheCapturedOperator() throws Exception {
        Operator operator = handoff.capture();
        AtomicReference<Actor> actor = new AtomicReference<>();
        AtomicReference<Boolean> can = new AtomicReference<>();

        Thread.ofVirtual()
                .start(() -> handoff.runAs(operator, () -> {
                    actor.set(actors.resolve());
                    can.set(perm.can(null, "subscription:delete"));
                }))
                .join();

        assertThat(actor.get().userId()).isEqualTo(userId);
        assertThat(actor.get().requestId()).isEqualTo(operator.actor().requestId());
        assertThat(can.get()).isTrue();
    }

    @Test
    void aWithdrawnGrantIsNoLongerHeld() {
        Operator operator = handoff.capture();
        assertThat(handoff.stillHolds(operator, null, "subscription:delete")).isTrue();

        OperatorFixture.revokeAll(userRoles, accessChanges, userId);

        assertThat(handoff.stillHolds(operator, null, "subscription:delete")).isFalse();
    }

    @Test
    void aWithdrawnGrantIsNoLongerHeldOnAQueueEither() {
        Operator operator = handoff.capture();
        ResourceRef queue = ResourceRef.subscription("orders.in");
        assertThat(handoff.stillHolds(operator, UUID.randomUUID(), queue, "subscription:purge"))
                .isTrue();

        OperatorFixture.revokeAll(userRoles, accessChanges, userId);

        assertThat(handoff.stillHolds(operator, UUID.randomUUID(), queue, "subscription:purge"))
                .isFalse();
    }

    @Test
    void aUserIsActedForAsTheirAccountStandsNow() {
        Operator operator = handoff.forUser(userId).orElseThrow();

        assertThat(operator.actor().userId()).isEqualTo(userId);
        assertThat(handoff.stillHolds(operator, null, "subscription:delete")).isTrue();

        OperatorFixture.revokeAll(userRoles, accessChanges, userId);

        assertThat(handoff.forUser(userId).orElseThrow().principal().getAuthorities())
                .isEmpty();
    }

    @Test
    void noOperatorForANullUnknownOrDisabledUser() {
        var user = users.findById(userId).orElseThrow();
        user.setDisabled(true);
        users.save(user);

        assertThat(handoff.forUser(null)).isEmpty();
        assertThat(handoff.forUser(UUID.randomUUID())).isEmpty();
        assertThat(handoff.forUser(userId)).isEmpty();
    }

    @Test
    void callAsReturnsTheValueAndLeavesTheThreadAsItWas() {
        var before = SecurityContextHolder.getContext().getAuthentication();
        Operator operator = handoff.forUser(userId).orElseThrow();
        SecurityContextHolder.clearContext();

        UUID seen = handoff.callAs(operator, () -> actors.resolve().userId());

        assertThat(seen).isEqualTo(userId);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(before).isNotNull();
    }

    @Test
    void theGatesCoverageTravelsToTheThreadThatRunsTheOperatorAndEndsWithThatRun() throws Exception {
        GateTicket ticket = new GateTicket(null, "bulk-run", null);
        AtomicInteger released = new AtomicInteger();
        doReturn(Optional.of(new GateLease(ticket, released::incrementAndGet)))
                .when(leases)
                .retain(ticket);
        Operator operator = ScopedValue.where(GateScope.COVERED, ticket).call(() -> handoff.capture());
        AtomicReference<GateTicket> seen = new AtomicReference<>();
        AtomicReference<GateTicket> nested = new AtomicReference<>();

        Thread.ofVirtual()
                .start(() -> handoff.runAs(operator, () -> {
                    seen.set(GateScope.COVERED.isBound() ? GateScope.COVERED.get() : null);
                    handoff.runAs(
                            operator, () -> nested.set(GateScope.COVERED.isBound() ? GateScope.COVERED.get() : null));
                }))
                .join();
        AtomicReference<Boolean> boundAfterwards = new AtomicReference<>();
        handoff.runAs(operator, () -> boundAfterwards.set(GateScope.COVERED.isBound()));

        assertThat(operator.covered().ticket()).isSameAs(ticket);
        assertThat(seen.get()).isSameAs(ticket);
        assertThat(nested.get()).isSameAs(ticket);
        assertThat(released).hasValue(1);
        assertThat(boundAfterwards.get()).isFalse();
    }

    @Test
    void aTicketTheGateDidNotIssueIsNotCaptured() {
        Operator operator = ScopedValue.where(GateScope.COVERED, new GateTicket(null, "bulk-run", null))
                .call(() -> handoff.capture());

        assertThat(operator.covered()).isNull();
    }

    @Test
    void anOperatorCapturedOutsideTheGateIsNotCovered() throws Exception {
        Operator operator = handoff.capture();
        AtomicReference<Boolean> bound = new AtomicReference<>();

        Thread.ofVirtual()
                .start(() -> handoff.runAs(operator, () -> bound.set(GateScope.COVERED.isBound())))
                .join();

        assertThat(operator.covered()).isNull();
        assertThat(bound.get()).isFalse();
    }
}
