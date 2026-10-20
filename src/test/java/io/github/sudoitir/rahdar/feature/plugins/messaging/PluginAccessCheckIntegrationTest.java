package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.AccessCheck;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.ResourceNames;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A registration or a send acts for a user and is allowed only on the queue or address that user holds the
 * permission on, through a team or a share, as well as through a grant (plugin-runtime spec, ADR-0111).
 */
@ExtendWith(AdminAuthenticationExtension.class)
class PluginAccessCheckIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    ResourceNames names;

    @Autowired
    AccessCheck access;

    @Autowired
    TeamService teams;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    UUID cluster;
    UUID operator;

    @BeforeEach
    void setUp() {
        cluster = clusters.save(new ClusterEntity("msg-" + UUID.randomUUID(), null, null))
                .getId();
        UUID orders = teams.create("orders-" + UUID.randomUUID()).id();
        teams.addPattern(orders, new PatternRequest(cluster, PatternKind.ANY, "orders.#"));
        AppUserEntity user = AppUserEntity.local("op-" + UUID.randomUUID(), null, "{noop}x");
        user.setMustChangePassword(false);
        operator = users.save(user).getId();
        teams.addMember(
                orders,
                new MemberRequest(
                        PrincipalType.USER,
                        operator,
                        null,
                        null,
                        roles.findByName("TEAM_OPERATOR").orElseThrow().getId()));
    }

    @Test
    void aTapIsAllowedOnATeamsQueueAndRefusedOnAnotherTeamsQueueNamingBoth() {
        assertThat(access.denial(operator, cluster, AccessCheck.needsOf(RegistrationMode.TAP, "orders.in")))
                .isEmpty();

        assertThat(access.denial(operator, cluster, AccessCheck.needsOf(RegistrationMode.TAP, "billing.in")))
                .get()
                .asString()
                .contains("message:read")
                .contains("subscription billing.in");
    }

    @Test
    void aConsumerNeedsPurgeToo() {
        assertThat(access.denial(operator, cluster, AccessCheck.needsOf(RegistrationMode.CONSUME, "orders.in")))
                .isEmpty();
        assertThat(AccessCheck.needsOf(RegistrationMode.CONSUME, "orders.in"))
                .extracting(AccessCheck.Need::permission)
                .containsExactly("message:read", "subscription:purge");
    }

    @Test
    void aSendIsAllowedToATeamsAddressAndRefusedToAnother() {
        assertThat(access.denial(operator, cluster, AccessCheck.sendNeeds(ResourceRef.destination("orders.out"))))
                .isEmpty();

        assertThat(access.denial(operator, cluster, AccessCheck.sendNeeds(ResourceRef.destination("billing.in"))))
                .get()
                .asString()
                .contains("message:send")
                .contains("destination billing.in");
    }

    @Test
    void aDisabledOrUnknownUserIsRefused() {
        assertThat(access.denial(
                        UUID.randomUUID(), cluster, AccessCheck.sendNeeds(ResourceRef.destination("orders.out"))))
                .get()
                .asString()
                .contains("no longer exists");
        assertThat(access.denial(null, cluster, AccessCheck.sendNeeds(ResourceRef.destination("orders.out"))))
                .isPresent();
    }
}
