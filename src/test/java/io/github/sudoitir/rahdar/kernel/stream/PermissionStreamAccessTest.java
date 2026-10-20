package io.github.sudoitir.rahdar.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.app.RahdarFeatures;
import io.github.sudoitir.rahdar.feature.alerting.AlertPermissions;
import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.replica.BusFrame;
import io.github.sudoitir.rahdar.kernel.security.AccessLoader;
import io.github.sudoitir.rahdar.kernel.security.AccessSnapshot;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ScopeHierarchy;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.TeamIndex;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * What each subscriber is shown of a frame (realtime-stream spec): the topic's permission, the resources a
 * frame names, trimming, and a change of access applying to the next frame. The permission model is the real
 * one with a stubbed database.
 */
class PermissionStreamAccessTest {

    private static final Set<String> TEAM_VIEWER =
            Set.of(Permissions.SUBSCRIPTION_READ, Permissions.DESTINATION_READ, MessagePermissions.MESSAGE_READ);

    private final UUID clusterId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orders = UUID.randomUUID();
    private final AccessLoader access = mock(AccessLoader.class);
    private final StreamTopicRegistry topics = mock(StreamTopicRegistry.class);
    private final List<TeamPatternEntity> ownedRows = new ArrayList<>();
    private final RahdarPrincipal principal = RahdarPrincipal.live(userId, "u", false);
    private PermissionStreamAccess streamAccess;

    @BeforeEach
    void setUp() {
        Map<String, CatalogueEntry> catalogue = RahdarFeatures.descriptors().stream()
                .flatMap(d -> d.permissions().stream()
                        .map(p -> new CatalogueEntry(
                                p.action(), p.label(), d.id(), d.title(), p.scope(), p.resourceTypes(), p.requires())))
                .collect(Collectors.toMap(CatalogueEntry::action, e -> e));
        FeatureRegistry features = mock(FeatureRegistry.class);
        when(features.permission(anyString()))
                .thenAnswer(i -> Optional.ofNullable(catalogue.get(i.<String>getArgument(0))));
        TeamPatternRepository patterns = mock(TeamPatternRepository.class);
        when(patterns.findAll()).thenAnswer(i -> List.copyOf(ownedRows));
        TeamShareRepository shares = mock(TeamShareRepository.class);
        when(shares.findAll()).thenReturn(List.of());
        PermissionResolver resolver = new PermissionResolver(
                mock(ScopeHierarchy.class),
                access,
                new TeamIndex(mock(TeamRepository.class), patterns, shares, mock(RolePermissionRepository.class)),
                features);
        when(topics.definition("queues")).thenReturn(TopicDef.signal("queues", Permissions.SUBSCRIPTION_READ));
        when(topics.definition("alerts")).thenReturn(TopicDef.signal("alerts", AlertPermissions.ALERT_READ));
        when(topics.definition("health")).thenReturn(TopicDef.signal("health"));
        @SuppressWarnings("unchecked")
        ObjectProvider<StreamGate> gates = mock(ObjectProvider.class);
        when(gates.orderedStream()).thenAnswer(i -> Stream.<StreamGate>empty());
        streamAccess = new PermissionStreamAccess(
                resolver, topics, JsonMapper.builder().build(), gates);
        ownedRows.add(new TeamPatternEntity(orders, clusterId, "SUBSCRIPTION", "orders.#"));
    }

    private void teamMember() {
        when(access.of(userId)).thenReturn(new AccessSnapshot(Set.of(), Map.of(orders, TEAM_VIEWER)));
    }

    private void grant(String... permissions) {
        when(access.of(userId))
                .thenReturn(new AccessSnapshot(
                        Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(permissions))), Map.of()));
    }

    private Subscriber.Held shown(String topic, BusFrame.About about) {
        Subscriber subscriber = new Subscriber(null, Set.of(topic), null, null, principal);
        return streamAccess.narrow(subscriber, clusterId, new Subscriber.Held(topic, null, null, about));
    }

    @Test
    void aTeamMemberDoesNotReceiveAnEventAboutAnotherTeamsQueue() {
        teamMember();

        assertThat(shown("queues", BusFrame.About.queue("billing.in"))).isNull();
        assertThat(shown("queues", BusFrame.About.queue("orders.in"))).isNotNull();
    }

    @Test
    void anEventListingSeveralQueuesIsTrimmedToThoseTheSubscriberMayRead() {
        teamMember();

        Subscriber.Held held = shown("queues", BusFrame.About.of(List.of("orders.in", "billing.in"), List.of()));

        assertThat(held.about().queues()).containsExactly("orders.in");
    }

    @Test
    void anEventAboutNoSingleResourceNeedsTheClusterPermission() {
        teamMember();
        assertThat(shown("queues", null)).isNull();

        grant(Permissions.SUBSCRIPTION_READ);
        assertThat(shown("queues", null)).isNotNull();
        assertThat(shown("queues", BusFrame.About.queue("billing.in"))).isNotNull();
    }

    @Test
    void aTopicNeedsItsFeaturePermission() {
        grant(Permissions.CLUSTER_READ);
        assertThat(shown("alerts", null)).isNull();

        grant(Permissions.CLUSTER_READ, AlertPermissions.ALERT_READ);
        assertThat(shown("alerts", null)).isNotNull();
    }

    @Test
    void aTopicWithNoPermissionNeedsTheClusterReadPermission() {
        teamMember();
        assertThat(shown("health", null)).isNull();

        grant(Permissions.CLUSTER_READ);
        assertThat(shown("health", null)).isNotNull();
    }

    @Test
    void revokedAccessAppliesToTheNextFrameOfAnOpenStream() {
        teamMember();
        Subscriber subscriber = new Subscriber(null, Set.of("queues"), null, null, principal);
        Subscriber.Held frame = new Subscriber.Held("queues", null, null, BusFrame.About.queue("orders.in"));
        assertThat(streamAccess.narrow(subscriber, clusterId, frame)).isNotNull();

        when(access.of(userId)).thenReturn(new AccessSnapshot(Set.of(), Map.of()));

        assertThat(streamAccess.narrow(subscriber, clusterId, frame)).isNull();
    }

    @Test
    void controlEventsReachEveryone() {
        Subscriber subscriber = new Subscriber(null, Set.of(), null, null, principal);
        Subscriber.Held ping = new Subscriber.Held(SseHub.PING, 1L, null);

        assertThat(streamAccess.narrow(subscriber, clusterId, ping)).isSameAs(ping);
    }

    @Test
    void aSubscriberWithNoPrincipalSeesNothing() {
        Subscriber subscriber = new Subscriber(null, Set.of("health"), null, null, null);

        assertThat(streamAccess.narrow(subscriber, clusterId, new Subscriber.Held("health", null, null)))
                .isNull();
    }
}
