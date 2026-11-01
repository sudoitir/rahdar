package io.github.sudoitir.rahdar.feature.messages.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.web.ApiExceptionHandler;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.MessageExpiry;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.broker.WholeClusterOperations;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import io.github.sudoitir.rahdar.platform.governance.ClearViewAudit;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Messages of a cluster read as one (ADR-0224): a classic or quorum queue is refused before the broker is read, a
 * stream is read as one log, and a send an exchange could not route is a 409 that names the exchange and the key.
 */
class MessageControllerWholeClusterTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final ResourceId QUEUE = ResourceId.of("rabbitmq.quorum-queue", "orders");
    private static final ResourceId EXCHANGE = ResourceId.of("rabbitmq.exchange", "orders");
    private static final String BASE = "/clusters/" + CLUSTER + "/resources/";

    private final BrokerMessages broker = mock(BrokerMessages.class);
    private final ResourceTargets targets = mock(ResourceTargets.class);
    private final ClusterKinds kinds = mock(ClusterKinds.class);
    private final AuditService audit = mock(AuditService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(100);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(mock(AuditEvent.class));
        MessageService service = new MessageService(
                mock(QueueLocator.class),
                mock(ClusterDirectory.class),
                broker,
                mock(MessageExpiry.class),
                audit,
                mock(ActorResolver.class),
                settings,
                mock(SseHub.class),
                mock(ClusterAccessGuard.class),
                mock(ContentPolicy.class),
                mock(ClearViewAudit.class),
                mock(OperationGate.class),
                mock(SystemExtensions.class),
                mock(WholeClusterOperations.class));
        when(kinds.readAsOne(CLUSTER)).thenReturn(true);
        mvc = standaloneSetup(new MessageController(service, targets, kinds))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aQuorumQueueIsRefusedWithTheDriversReasonAndNoMessageIsRead() throws Exception {
        when(targets.require(eq(CLUSTER), eq("rabbitmq.quorum-queue"), eq("orders"), any(ResourceClass[].class)))
                .thenReturn(new Target(QUEUE, ResourceClass.SUBSCRIPTION));
        when(broker.unavailableBecause(CLUSTER, "messages.browse", QUEUE))
                .thenReturn(Optional.of("A quorum queue cannot be read without taking its messages."));

        mvc.perform(get(BASE + "rabbitmq.quorum-queue/orders/messages"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("unavailable-on-kind")))
                .andExpect(jsonPath("$.detail").value("A quorum queue cannot be read without taking its messages."));

        verify(broker).unavailableBecause(CLUSTER, "messages.browse", QUEUE);
        verifyNoMoreInteractions(broker);
    }

    @Test
    void aSendThatNoQueueReceivedIsA409NamingTheExchangeAndTheKey() throws Exception {
        when(targets.require(eq(CLUSTER), eq("rabbitmq.exchange"), eq("orders"), any(ResourceClass[].class)))
                .thenReturn(new Target(EXCHANGE, ResourceClass.DESTINATION));
        when(broker.sendById(eq(CLUSTER), eq(EXCHANGE), eq(ResourceClass.DESTINATION), eq("eu"), any()))
                .thenThrow(BrokerFailures.notRouted("orders", "eu"));

        mvc.perform(post(BASE + "rabbitmq.exchange/orders/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"durable\":true,\"body\":\"secret\",\"routingKey\":\"eu\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("not-routed")))
                .andExpect(jsonPath("$.detail")
                        .value("No queue received the message: the exchange 'orders' has no binding that matches"
                                + " routing key 'eu'. Nothing was stored."));
    }

    @Test
    void aStreamAskedForAPartitionItDoesNotHaveIsABadRequestAndNothingIsRead() throws Exception {
        when(kinds.log(CLUSTER, "rabbitmq.stream")).thenReturn(true);
        when(targets.require(eq(CLUSTER), eq("rabbitmq.stream"), eq("orders"), any(ResourceClass[].class)))
                .thenReturn(new Target(ResourceId.of("rabbitmq.stream", "orders"), ResourceClass.SUBSCRIPTION));

        mvc.perform(get(BASE + "rabbitmq.stream/orders/messages").param("partition", "2"))
                .andExpect(status().isBadRequest());

        verify(broker, never()).browseLog(any(), any(), anyInt(), any(), anyInt(), any());
    }
}
