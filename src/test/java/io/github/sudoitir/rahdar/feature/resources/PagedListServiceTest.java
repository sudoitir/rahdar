package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ChannelView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConnectionView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConsumerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.DestinationView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.TeamIndex;
import io.github.sudoitir.rahdar.kernel.security.TeamRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ResponseCreator;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PagedListServiceTest {

    private static final String URL_A = "http://a:8161/console/jolokia";
    private static final String URL_B = "http://b:8161/console/jolokia";

    @Mock
    ClusterDirectory nodes;

    @Mock
    BrokerGateway gateway;

    /** Permissive by default: an unstubbed void call is a no-op, i.e. access granted.
     * The guard's real behaviour is covered by {@code ClusterScopeAuthorizationTest}. */
    @Mock
    ClusterAccessGuard clusterAccess;

    /** Sees everything; what a restricted caller sees is covered by the integration tests. */
    @Mock
    PermissionResolver permissions;

    @Mock
    ResourceFilter everything;

    @Mock
    TeamIndex teams;

    PagedListService service;

    @BeforeEach
    void setUp() {
        when(permissions.filter(any(), any())).thenReturn(everything);
        when(everything.readable(any())).thenReturn(true);
        when(everything.allowedActions(any())).thenReturn(List.of());
        service = new PagedListService(nodes, new ResourceViewMapper(), clusterAccess, permissions, teams, gateway);
    }

    /** An agent that answers with these fixtures, in order. */
    private static FixtureJolokia agent(String... fixtures) {
        FixtureJolokia server = new FixtureJolokia();
        for (String fixture : fixtures) {
            server.expect().andRespond(body(fixture));
        }
        return server;
    }

    /** An agent that answers nothing: the connection closes before any response. */
    private static FixtureJolokia unreachable() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withException(new ConnectException("Connection refused")));
        return server;
    }

    /** The cluster as the gateway gives it: these agents, one per node. */
    private void serving(UUID clusterId, Map<UUID, FixtureJolokia> agents) {
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(agents));
    }

    private static ResponseCreator body(String fixture) {
        try {
            String json = new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
            return withSuccess(json, MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BrokerNodeEntity node(UUID clusterId, String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl(url);
        try {
            var id = BrokerNodeEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(n, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return n;
    }

    @Test
    void anAddressRowNamesTheTeamThatOwnsIt() {
        UUID clusterId = oneNodeServing("search-broker.json", "list-addresses.json");
        TeamRef orders = new TeamRef(UUID.randomUUID(), "Orders");
        when(teams.ownerTeamOf(clusterId, ResourceRef.destination("orders.in"))).thenReturn(Optional.of(orders));
        when(teams.ownerTeamOf(clusterId, ResourceRef.destination("billing.in")))
                .thenReturn(Optional.empty());

        PagedView<DestinationView> page = service.destinations(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(page.data())
                .extracting(DestinationView::name, DestinationView::ownerTeam)
                .containsExactlyInAnyOrder(Tuple.tuple("orders.in", orders), Tuple.tuple("billing.in", null));
    }

    @Test
    void oneNodeDownStillReturnsRowsFromTheOthersTaggedByNode() {
        UUID clusterId = UUID.randomUUID();
        BrokerNodeEntity a = node(clusterId, "node-a", URL_A);
        BrokerNodeEntity b = node(clusterId, "node-b", URL_B);
        when(nodes.nodes(clusterId)).thenReturn(List.of(a, b));
        serving(
                clusterId,
                orderedMap(a.getId(), unreachable(), b.getId(), agent("search-broker.json", "list-consumers.json")));

        PagedView<ConsumerView> page = service.consumers(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).nodeName()).isEqualTo("node-b");
        assertThat(page.data().get(0).subscriptionName()).isEqualTo("SPIKE.A.q000");
    }

    @Test
    void aConsumerIsFoundByTheSessionItRunsInAsWellAsByItsQueue() {
        UUID clusterId = UUID.randomUUID();
        BrokerNodeEntity b = node(clusterId, "node-b", URL_B);
        when(nodes.nodes(clusterId)).thenReturn(List.of(b));
        serving(clusterId, Map.of(b.getId(), agent("search-broker.json", "list-consumers.json")));

        // A session row links to its consumers by session id, which is not the field the listing
        // used to filter on (the queue name).
        PagedView<ConsumerView> bySession =
                service.consumers(clusterId, ResourceQuery.of("dbb33b79-a798-11f1", 1, 50, null));
        PagedView<ConsumerView> byQueue = service.consumers(clusterId, ResourceQuery.of("spike.a.q000", 1, 50, null));
        PagedView<ConsumerView> neither = service.consumers(clusterId, ResourceQuery.of("no-such-thing", 1, 50, null));

        assertThat(bySession.data()).extracting(ConsumerView::subscriptionName).containsExactly("SPIKE.A.q000");
        assertThat(byQueue.data()).hasSize(1);
        assertThat(neither.data()).isEmpty();
    }

    @Test
    void everyNodeDownRethrowsTheClassifiedFailure() {
        UUID clusterId = UUID.randomUUID();
        BrokerNodeEntity a = node(clusterId, "node-a", URL_A);
        when(nodes.nodes(clusterId)).thenReturn(List.of(a));
        FixtureJolokia refusing = new FixtureJolokia();
        refusing.expect().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        serving(clusterId, Map.of(a.getId(), refusing));

        ResourceQuery firstPage = ResourceQuery.of(null, 1, 50, null);

        assertThatThrownBy(() -> service.consumers(clusterId, firstPage))
                .isInstanceOf(BrokerConnectionException.class)
                .extracting(e -> ((BrokerConnectionException) e).kind())
                .isEqualTo(BrokerConnectionException.Kind.CREDENTIALS_REJECTED);
    }

    @Test
    void concurrentReadsOfTheSameListShareOneBrokerCall() throws Exception {
        UUID clusterId = UUID.randomUUID();
        BrokerNodeEntity a = node(clusterId, "node-a", URL_A);
        when(nodes.nodes(clusterId)).thenReturn(List.of(a));
        FixtureJolokia only = agent("search-broker.json", "list-consumers.json");
        serving(clusterId, Map.of(a.getId(), only));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PagedView<ConsumerView>>> reads = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                reads.add(pool.submit(() -> {
                    start.await();
                    return service.consumers(clusterId, ResourceQuery.of(null, 1, 50, null));
                }));
            }
            start.countDown();
            for (var read : reads) {
                assertThat(read.get().data()).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }

        // The broker name, then the one list every read shared.
        assertThat(only.requests()).hasSize(2);
    }

    @Test
    void noManageableNodeIsAnUnreachableProblem() {
        UUID clusterId = UUID.randomUUID();
        when(nodes.nodes(clusterId)).thenReturn(List.of());

        ResourceQuery firstPage = ResourceQuery.of(null, 1, 50, null);

        assertThatThrownBy(() -> service.consumers(clusterId, firstPage)).isInstanceOf(BrokerConnectionException.class);
    }

    // ---- what the caller may see ---------------------------------------------------------------

    private static final String QUEUE = "SPIKE.A.q000";

    private static <K, V> Map<K, V> orderedMap(K k1, V v1, K k2, V v2) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /** A filter that lets the named resources through and nothing else. */
    private void readable(UUID clusterId, String[] queues, String[] addresses) {
        ResourceFilter readableQueues = reading(queues);
        ResourceFilter readableAddresses = reading(addresses);
        when(permissions.filter(clusterId, ResourceType.SUBSCRIPTION)).thenReturn(readableQueues);
        when(permissions.filter(clusterId, ResourceType.DESTINATION)).thenReturn(readableAddresses);
    }

    private static ResourceFilter reading(String... names) {
        ResourceFilter filter = Mockito.mock(ResourceFilter.class);
        List<String> readable = List.of(names);
        when(filter.readable(any())).thenAnswer(call -> readable.contains(call.<String>getArgument(0)));
        when(filter.allowedActions(any())).thenReturn(List.of());
        return filter;
    }

    private UUID oneNodeServing(String... fixtures) {
        UUID clusterId = UUID.randomUUID();
        BrokerNodeEntity b = node(clusterId, "node-b", URL_B);
        when(nodes.nodes(clusterId)).thenReturn(List.of(b));
        serving(clusterId, Map.of(b.getId(), agent(fixtures)));
        return clusterId;
    }

    @Test
    void aConsumerOfAQueueTheCallerCannotReadIsNotListedOrCounted() {
        UUID clusterId = oneNodeServing("search-broker.json", "list-consumers.json");
        readable(clusterId, new String[] {"another.queue"}, new String[] {});

        PagedView<ConsumerView> page = service.consumers(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(page.data()).isEmpty();
        assertThat(page.count()).isZero();
    }

    @Test
    void aSessionIsSeenOnlyThroughTheConsumersAndProducersTheCallerMayRead() {
        UUID clusterId = oneNodeServing(
                "search-broker.json", "list-sessions.json", "list-consumers.json", "list-producers.json");
        // The queue of the one consumer is readable; the address of the one producer is not.
        readable(clusterId, new String[] {QUEUE}, new String[] {});

        PagedView<ChannelView> page = service.channels(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(page.data()).hasSize(1);
        assertThat(page.count()).isEqualTo(1);
        ChannelView seen = page.data().get(0);
        assertThat(ResourceIds.nameOf(seen.id())).startsWith("dbb33b79");
        assertThat(seen.consumerCount()).isEqualTo(1);
        assertThat(seen.producerCount()).isZero();
    }

    @Test
    void aConnectionIsSeenOnlyThroughTheSessionsTheCallerSees() {
        UUID clusterId = oneNodeServing(
                "search-broker.json",
                "list-connections.json",
                "list-sessions.json",
                "list-consumers.json",
                "list-producers.json");
        readable(clusterId, new String[] {QUEUE}, new String[] {});

        PagedView<ConnectionView> page = service.connections(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(page.data()).extracting(c -> ResourceIds.nameOf(c.id())).containsExactly("c1db1ea7");
        assertThat(page.data().get(0).channelCount()).isEqualTo(1);
        assertThat(page.count()).isEqualTo(1);
    }

    @Test
    void connectionReadOnTheClusterShowsEveryConnectionAndSessionUntrimmed() {
        UUID clusterId = oneNodeServing("search-broker.json", "list-connections.json", "list-sessions.json");
        when(permissions.can(clusterId, ResourcePermissions.CONNECTION_READ)).thenReturn(true);
        when(permissions.can(clusterId, ResourcePermissions.CONNECTION_CLOSE)).thenReturn(true);

        PagedView<ConnectionView> connectionsPage = service.connections(clusterId, ResourceQuery.of(null, 1, 50, null));
        PagedView<ChannelView> sessionsPage = service.channels(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(connectionsPage.data()).hasSize(5);
        assertThat(connectionsPage.data())
                .allSatisfy(c -> assertThat(c.allowedActions()).containsExactly(ResourcePermissions.CONNECTION_CLOSE));
        assertThat(sessionsPage.data()).hasSize(4);
    }

    @Test
    void aRowWithoutConnectionCloseCarriesNoConnectionAction() {
        UUID clusterId = oneNodeServing("search-broker.json", "list-consumers.json");
        readable(clusterId, new String[] {QUEUE}, new String[] {});

        PagedView<ConsumerView> page = service.consumers(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).allowedActions()).isEmpty();
    }
}
