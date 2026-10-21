package io.github.sudoitir.rahdar.kernel.stream.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.app.RahdarFeatures;
import io.github.sudoitir.rahdar.feature.events.BrokerEventService;
import io.github.sudoitir.rahdar.feature.events.web.EventViews.BrokerEventView;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.InstalledFeatures;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.kernel.stream.StreamTopicRegistry;
import io.github.sudoitir.rahdar.kernel.stream.Subscriber;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/v1/stream}: subscriber registration, topic parsing, no-buffering header.
 *
 * <p>The emitter has no timeout (the heartbeat keeps it open), so there is no
 * async result to dispatch — the assertions here are all on what the controller
 * does synchronously before it returns the emitter.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class StreamControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @MockitoBean
    SseHub hub;

    @MockitoBean
    BrokerEventService events;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
    }

    @Test
    void openingTheStreamRegistersASubscriberForTheRequestedTopics() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "subscriptions,health"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> captor = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), captor.capture());
        assertThat(captor.getValue().topics()).containsExactlyInAnyOrder("subscriptions", "health");
    }

    @Test
    void aNewSubscriberIsGreetedAtOnceSoTheClientSeesTheStreamOpen() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream").param("clusterId", clusterId.toString()))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> registered = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), registered.capture());
        verify(hub).greet(registered.getValue());
    }

    @Test
    void theRrTopicIsAccepted() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "rr"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> captor = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), captor.capture());
        assertThat(captor.getValue().topics()).containsExactly("rr");
    }

    @Test
    void unknownTopicsFallBackToTheFullDefaultSet() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "bogus"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> captor = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), captor.capture());
        assertThat(captor.getValue().topics()).containsExactlyInAnyOrder("topology", "health", "subscriptions");
    }

    @Test
    void aLastEventIdReplaysTheMissedEventsBeforeLiveDelivery() throws Exception {
        UUID clusterId = UUID.randomUUID();
        when(events.since(clusterId, 10L, 500)).thenReturn(List.of(view(11L), view(12L)));

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events")
                        .header("Last-Event-ID", "10"))
                .andExpect(request().asyncStarted());

        verify(hub, times(2)).replayTo(any(), any(Subscriber.class), eq("events"), any(), any());
    }

    @Test
    void noLastEventIdReplaysNothing() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events"))
                .andExpect(request().asyncStarted());

        verify(hub, Mockito.never()).replayTo(any(), any(), any(), any(), any());
    }

    @Test
    void aDisabledFeaturesTopicIsIgnored() {
        var env = new MockEnvironment().withProperty("rahdar.features.alerting.enabled", "false");
        var registry = new FeatureRegistry(new InstalledFeatures(RahdarFeatures.descriptors()), env, event -> {});
        var topics = new StreamTopicRegistry(registry, List.of());
        SseHub localHub = mock(SseHub.class);
        var controller =
                new StreamController(localHub, mock(ClusterAccessGuard.class), topics, mock(ReplicaRegistry.class));
        UUID clusterId = UUID.randomUUID();

        controller.stream(
                clusterId, "alerts,topology", null, null, new MockHttpServletRequest(), new MockHttpServletResponse());

        ArgumentCaptor<Subscriber> captor = ArgumentCaptor.forClass(Subscriber.class);
        verify(localHub).register(eq(clusterId), captor.capture());
        assertThat(captor.getValue().topics()).containsExactly("topology");
    }

    @Test
    void theLastEventIdMayBeAQueryParameter() throws Exception {
        UUID clusterId = UUID.randomUUID();
        when(events.since(clusterId, 10L, 500)).thenReturn(List.of(view(11L)));

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events")
                        .param("lastEventId", "10"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> subscriber = ArgumentCaptor.forClass(Subscriber.class);
        InOrder order = inOrder(hub);
        order.verify(hub).register(eq(clusterId), subscriber.capture());
        order.verify(hub).replayTo(any(), eq(subscriber.getValue()), eq("events"), any(), eq("11"));
        order.verify(hub).release(subscriber.getValue(), Map.of("events", 11L));
    }

    @Test
    void aReplayThatHitTheCapIsFollowedByAResync() throws Exception {
        UUID clusterId = UUID.randomUUID();
        when(events.since(clusterId, 10L, 500))
                .thenReturn(LongStream.rangeClosed(11, 510)
                        .mapToObj(StreamControllerTest::view)
                        .toList());

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events")
                        .param("lastEventId", "10"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> subscriber = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), subscriber.capture());
        InOrder order = inOrder(hub);
        order.verify(hub).replayTo(any(), eq(subscriber.getValue()), eq("events"), any(), eq("510"));
        order.verify(hub).sendTo(eq(subscriber.getValue()), eq(SseHub.RESYNC), any());
        order.verify(hub).release(subscriber.getValue(), Map.of("events", 510L));
    }

    @Test
    void aReplicaThatIsDrainingRefusesANewStream() {
        for (ReplicaRegistry.State state : List.of(ReplicaRegistry.State.DRAINING, ReplicaRegistry.State.STOPPED)) {
            ReplicaRegistry replica = mock(ReplicaRegistry.class);
            when(replica.state()).thenReturn(state);
            SseHub localHub = mock(SseHub.class);
            var controller = new StreamController(
                    localHub, mock(ClusterAccessGuard.class), mock(StreamTopicRegistry.class), replica);

            assertThatThrownBy(() -> controller.stream(
                            UUID.randomUUID(),
                            "subscriptions",
                            null,
                            null,
                            new MockHttpServletRequest(),
                            new MockHttpServletResponse()))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

            verifyNoInteractions(localHub);
        }
    }

    private static BrokerEventView view(long seq) {
        return new BrokerEventView(
                seq,
                Instant.now(),
                Instant.now(),
                "CONSUMER_CREATED",
                "a",
                "r",
                "c",
                "s",
                "conn",
                "1.2.3.4",
                "u",
                null,
                Map.of());
    }

    @Test
    void theStreamSetsTheNoProxyBufferingHeader() throws Exception {
        UUID clusterId = UUID.randomUUID();

        MvcResult result = mvc.perform(get("/api/v1/stream").param("clusterId", clusterId.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        verify(hub).register(eq(clusterId), any(Subscriber.class));
    }
}
