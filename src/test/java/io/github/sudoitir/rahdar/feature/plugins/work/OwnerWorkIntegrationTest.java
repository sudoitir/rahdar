package io.github.sudoitir.rahdar.feature.plugins.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.plugin.PluginPurged;
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
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Work that runs later as its owner (plugin-runtime spec): the needs it declares are checked at publish,
 * before every run, and on turning it back on; a lost need suspends it with the reason until someone enables
 * it, whatever the owner holds in the meantime.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class OwnerWorkIntegrationTest extends PostgresIntegrationTest {

    private static final String PLUGIN = "acme-sync";

    @MockitoBean
    ResourceNames names;

    @Autowired
    OwnerWorkService service;

    @Autowired
    TeamService teams;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    ApplicationEventPublisher events;

    UUID cluster;
    UUID orders;
    UUID owner;
    UUID memberId;
    OwnerWork work;

    @BeforeEach
    void setUp() {
        cluster = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "work-" + UUID.randomUUID(), null, null))
                .getId();
        orders = teams.create("orders-" + UUID.randomUUID()).id();
        teams.addPattern(orders, new PatternRequest(cluster, PatternKind.ANY, "orders.#"));
        owner = user();
        memberId = teams.addMember(
                        orders, new MemberRequest(PrincipalType.USER, owner, null, null, role("TEAM_OPERATOR")))
                .id();
        work = (OwnerWork) service.beansFor(PLUGIN + "-" + UUID.randomUUID()).get(OwnerWorkService.BEAN_NAME);
    }

    private UUID user() {
        AppUserEntity user = AppUserEntity.local("owner-" + UUID.randomUUID(), null, "{noop}x");
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    private UUID role(String name) {
        return roles.findByName(name).orElseThrow().getId();
    }

    private List<WorkNeed> needs() {
        return List.of(
                WorkNeed.on(cluster, ResourceRef.subscription("orders.in"), "message:move"),
                WorkNeed.on(cluster, ResourceRef.destination("orders.out"), "message:send"));
    }

    @Test
    void publishingWithoutSendRightsOnATargetIsRefusedNamingThePermissionAndTheResource() {
        List<WorkNeed> toBilling = List.of(WorkNeed.on(cluster, ResourceRef.destination("billing.in"), "message:send"));

        assertThatThrownBy(() -> work.publish("forward", owner, toBilling))
                .isInstanceOf(WorkRefusedException.class)
                .hasMessageContaining("message:send on destination billing.in")
                .hasMessageContaining(cluster.toString());
        assertThat(work.status("forward")).isEmpty();
    }

    @Test
    void everyMissingNeedIsNamed() {
        List<WorkNeed> wanted = List.of(
                WorkNeed.on(cluster, ResourceRef.destination("billing.in"), "message:send"),
                WorkNeed.on(cluster, ResourceRef.subscription("billing.dlq"), "message:move"),
                WorkNeed.on(cluster, ResourceRef.subscription("orders.in"), "message:read"));

        assertThatThrownBy(() -> work.publish("forward", owner, wanted))
                .hasMessageContaining("message:send on destination billing.in")
                .hasMessageContaining("message:move on subscription billing.dlq")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("orders.in"));
    }

    @Test
    void workWhoseOwnerHoldsTheNeedsIsPublishedAndMayRun() {
        WorkStatus published = work.publish("forward", owner, needs());

        assertThat(published.state()).isEqualTo(WorkState.ACTIVE);
        assertThat(published.runnable()).isTrue();
        assertThat(work.beforeRun("forward").runnable()).isTrue();
        assertThat(work.status("forward")).get().satisfies(s -> {
            assertThat(s.ownerUserId()).isEqualTo(owner);
            assertThat(s.needs()).isEqualTo(needs());
        });
    }

    @Test
    void losingARightSuspendsTheWorkBeforeItsNextRunAndSaysWhy() {
        work.publish("forward", owner, needs());

        teams.removeMember(orders, memberId);
        WorkStatus checked = work.beforeRun("forward");

        assertThat(checked.state()).isEqualTo(WorkState.SUSPENDED);
        assertThat(checked.runnable()).isFalse();
        assertThat(checked.reason())
                .contains("message:move on subscription orders.in")
                .contains("message:send on destination orders.out")
                .contains("enabled");
        // The suspension is kept: a later reader sees it without another check.
        assertThat(work.status("forward"))
                .get()
                .satisfies(s -> assertThat(s.state()).isEqualTo(WorkState.SUSPENDED));
    }

    @Test
    void suspendedWorkStaysSuspendedWhenTheRightsReturnUntilItIsEnabled() {
        work.publish("forward", owner, needs());
        teams.removeMember(orders, memberId);
        work.beforeRun("forward");

        teams.addMember(orders, new MemberRequest(PrincipalType.USER, owner, null, null, role("TEAM_OPERATOR")));

        assertThat(work.beforeRun("forward").runnable()).isFalse();
        assertThat(work.enable("forward").runnable()).isTrue();
        assertThat(work.beforeRun("forward").runnable()).isTrue();
    }

    @Test
    void workCannotBeEnabledWhileANeedIsStillMissing() {
        work.publish("forward", owner, needs());
        teams.removeMember(orders, memberId);
        work.beforeRun("forward");

        assertThatThrownBy(() -> work.enable("forward"))
                .isInstanceOf(WorkRefusedException.class)
                .hasMessageContaining("message:move on subscription orders.in");
        assertThat(work.status("forward"))
                .get()
                .satisfies(s -> assertThat(s.runnable()).isFalse());
    }

    @Test
    void aDisabledOrUnknownOwnerHoldsNothing() {
        work.publish("forward", owner, needs());
        AppUserEntity account = users.findById(owner).orElseThrow();
        account.setDisabled(true);
        users.save(account);

        assertThat(work.beforeRun("forward").reason()).contains("unknown or disabled");
        UUID someone = UUID.randomUUID();
        List<WorkNeed> needs = needs();
        assertThatThrownBy(() -> work.publish("other", someone, needs))
                .isInstanceOf(WorkRefusedException.class)
                .hasMessageContaining("unknown or disabled");
    }

    @Test
    void aClusterWideNeedIsMetByAGrantAndNotByATeam() {
        List<WorkNeed> clusterWide = List.of(WorkNeed.onCluster(cluster, "message:read"));
        assertThatThrownBy(() -> work.publish("scan", owner, clusterWide))
                .isInstanceOf(WorkRefusedException.class)
                .hasMessageContaining("message:read on cluster " + cluster);
    }

    @Test
    void anUnknownKeyIsRefusedAndAWithdrawnWorkIsGone() {
        assertThatThrownBy(() -> work.beforeRun("nothing")).isInstanceOf(WorkRefusedException.class);

        work.publish("forward", owner, needs());
        assertThat(work.withdraw("forward")).isTrue();
        assertThat(work.withdraw("forward")).isFalse();
        assertThat(work.all()).isEmpty();
    }

    @Test
    void aPluginSeesOnlyItsOwnWorkAndPurgingItRemovesIt() {
        String pluginId = PLUGIN + "-" + UUID.randomUUID();
        OwnerWork mine = (OwnerWork) service.beansFor(pluginId).get(OwnerWorkService.BEAN_NAME);
        mine.publish("forward", owner, needs());
        work.publish("forward", owner, needs());

        assertThat(mine.all()).extracting(WorkStatus::key).containsExactly("forward");
        events.publishEvent(new PluginPurged(pluginId));

        assertThat(mine.all()).isEmpty();
        assertThat(work.all()).hasSize(1);
    }

    @Test
    void aMalformedKeyOrNoNeedsListIsRefused() {
        List<WorkNeed> needs = needs();
        assertThatThrownBy(() -> work.publish("has space", owner, needs)).isInstanceOf(WorkRefusedException.class);
        assertThatThrownBy(() -> work.publish("forward", owner, null)).isInstanceOf(WorkRefusedException.class);
        assertThatThrownBy(() -> work.publish("forward", null, needs)).isInstanceOf(WorkRefusedException.class);
    }

    @Test
    void aNeedOnAResourceNamesItsCluster() {
        ResourceRef ordersIn = ResourceRef.subscription("orders.in");
        assertThatThrownBy(() -> WorkNeed.on(null, ordersIn, "message:read"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
