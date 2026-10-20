package io.github.sudoitir.rahdar.feature.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.BulkOutcome;
import io.github.sudoitir.openbroker.messaging.MessageAction.Delete;
import io.github.sudoitir.openbroker.messaging.MessageAction.Move;
import io.github.sudoitir.openbroker.messaging.MessageAction.Retry;
import io.github.sudoitir.openbroker.messaging.MessageField;
import io.github.sudoitir.openbroker.messaging.MessageFilter;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageDeleteParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageExpireParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageMoveParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageRetryParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.QueuePurgeParams;
import io.github.sudoitir.rahdar.feature.messages.MessageService.Outcome;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.SendMessageRequest;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessageDetailView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessagePageView;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard.Requirement;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.BulkResult;
import io.github.sudoitir.rahdar.platform.broker.MessageExpiry;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.governance.ClearViewAudit;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.GovernContext;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import io.github.sudoitir.rahdar.platform.governance.MessageContent;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Message browse and the destructive message operations with the broker and persistence stubbed: how a
 * queue is resolved onto a node, what each operation asks the broker for, and what is audited as it goes.
 */
class MessageServiceTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE_A = UUID.randomUUID();
    private static final UUID NODE_B = UUID.randomUUID();
    private static final String ETAG = "W/\"orders@b\"";

    private final QueueLocator locator = mock(QueueLocator.class);
    private final ClusterDirectory directory = mock(ClusterDirectory.class);
    private final BrokerMessages messages = mock(BrokerMessages.class);
    private final MessageExpiry expiry = mock(MessageExpiry.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final SseHub hub = mock(SseHub.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final ClearViewAudit clearViews = mock(ClearViewAudit.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final AuditEvent event = mock(AuditEvent.class);
    private final ClusterNode nodeA = node(NODE_A, "node-a", "http://a/jolokia", "tcp://a", true);
    private final ClusterNode nodeB = node(NODE_B, "node-b", "http://b/jolokia", "tcp://b", true);

    private MessageService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // No provider is armed: the gate runs every action as it is.
        when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        service = new MessageService(
                locator, directory, messages, expiry, audit, actors, settings, hub, access, policy, clearViews, gate);
        when(locator.locate(CLUSTER, "target"))
                .thenReturn(List.of(new QueueLocation(NODE_A, "target", "target", "ANYCAST", 1)));
        when(locator.locate(CLUSTER, "t")).thenReturn(List.of(new QueueLocation(NODE_A, "t", "t", "ANYCAST", 1)));
        when(locator.locate(CLUSTER, "orders"))
                .thenReturn(List.of(
                        new QueueLocation(NODE_A, "orders", "orders.addr", "ANYCAST", 5),
                        new QueueLocation(NODE_B, "orders", "orders.addr", "ANYCAST", 50)));
        when(directory.nodes(CLUSTER)).thenReturn(List.of(nodeA, nodeB));
        when(actors.resolve()).thenReturn(new Actor("alice", "127.0.0.1", "req", null));
        when(audit.begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(event);
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(100);
        when(messages.identity(eq(CLUSTER), any(UUID.class), anyString())).thenReturn(ETAG);
        when(messages.queueId(eq(CLUSTER), anyString()))
                .thenAnswer(i -> ResourceId.of("artemis.queue", i.<String>getArgument(1)));
        when(policy.context(eq(CLUSTER), anyString(), anyString()))
                .thenReturn(GovernContext.masked(CLUSTER, "orders.addr"));
        when(policy.govern(any(), any())).thenAnswer(invocation -> {
            MessageContent content = invocation.getArgument(1);
            return new GovernedMessage(
                    content.headers(), content.properties(), content.body(), List.of(), List.of(), Map.of(), 1);
        });
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static ClusterNode node(UUID id, String name, String jolokia, String core, boolean active) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        when(node.getManagementUrl()).thenReturn(jolokia);
        when(node.getDataUrl()).thenReturn(core);
        when(node.getActive()).thenReturn(active);
        return node;
    }

    private static BrowsedMessage message(long id, String body, BodyEncoding encoding) {
        return new BrowsedMessage(
                id,
                3,
                true,
                4,
                1_000L,
                0L,
                body == null ? 0 : body.length(),
                "group-1",
                "corr-1",
                "reply",
                "user-1",
                body,
                encoding,
                Compression.NONE,
                "application/json",
                false,
                null,
                Map.of("tenant", "acme"),
                Map.of("attempt", 2L),
                Map.of("seq", 9L),
                Map.of("ratio", 0.5),
                Map.of("urgent", true));
    }

    private void browses(List<BrowsedMessage> served) {
        when(messages.browse(eq(CLUSTER), any(UUID.class), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(new BrowsePage(served, 42L, null));
    }

    /** What the broker reports for an action over the busiest node's queue. */
    private static BulkOutcome done(long count) {
        return BulkOutcome.of(count);
    }

    // ---- browse and detail ------------------------------------------------------

    @Test
    void browseReadsTheBusiestNode() {
        browses(List.of(message(1, "hello", BodyEncoding.TEXT)));

        MessagePageView page = service.browse(CLUSTER, "orders", null, "priority > 3", 2, 500);

        // Both nodes are live and hold the queue; the one holding more messages is the one that is opened.
        verify(messages).browse(CLUSTER, NODE_B, "orders", 2, MessageService.BROKER_PAGE_CAP, "priority > 3");
        assertThat(page.node()).isEqualTo(NODE_B);
        assertThat(page.count()).isEqualTo(42L);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(MessageService.BROKER_PAGE_CAP);
        assertThat(page.data()).singleElement().satisfies(row -> {
            assertThat(row.messageId()).isEqualTo(1);
            assertThat(row.groupId()).isEqualTo("group-1");
            assertThat(row.correlationId()).isEqualTo("corr-1");
            assertThat(row.bodyPreview()).isEqualTo("hello");
            assertThat(row.propertyCount()).isEqualTo(5);
        });
        verify(clearViews).recordClear(any(), eq("QUEUE"), eq("orders"), anyList());
    }

    @Test
    void aRequestedNodeIsTheOneBrowsed() {
        browses(List.of());

        MessagePageView page = service.browse(CLUSTER, "orders", NODE_A, null, 1, 50);

        verify(messages).browse(CLUSTER, NODE_A, "orders", 1, 50, null);
        assertThat(page.node()).isEqualTo(NODE_A);
        assertThat(page.data()).isEmpty();
    }

    @Test
    void aLongBodyIsCutToAPreview() {
        browses(List.of(message(1, "x".repeat(500), BodyEncoding.TEXT)));

        assertThat(service.browse(CLUSTER, "orders", null, null, 1, 50)
                        .data()
                        .get(0)
                        .bodyPreview())
                .hasSize(200);
    }

    @Test
    void aMessageWithNoBodyHasNoPreview() {
        browses(List.of(message(1, null, BodyEncoding.TEXT)));

        assertThat(service.browse(CLUSTER, "orders", null, null, 1, 50)
                        .data()
                        .get(0)
                        .bodyPreview())
                .isNull();
    }

    @Test
    void detailFindsOneMessageAndListsItsTypedProperties() {
        browses(List.of(message(1, "a", BodyEncoding.TEXT), message(2, "b", BodyEncoding.BASE64)));

        MessageDetailView detail = service.detail(CLUSTER, "orders", 2, null, null);

        assertThat(detail.messageId()).isEqualTo(2);
        assertThat(detail.bodyEncoding()).isEqualTo("BASE64");
        assertThat(detail.bodyCompression()).isNull();
        assertThat(detail.body()).isEqualTo("b");
        assertThat(detail.contentType()).isEqualTo("application/json");
        assertThat(detail.userId()).isEqualTo("user-1");
        assertThat(detail.stringProperties()).containsEntry("tenant", "acme");
        assertThat(detail.intProperties()).containsEntry("attempt", 2L);
        assertThat(detail.longProperties()).containsEntry("seq", 9L);
        assertThat(detail.doubleProperties()).containsEntry("ratio", 0.5);
        assertThat(detail.booleanProperties()).containsEntry("urgent", true);
        verify(clearViews).recordClear(any(), eq("MESSAGE"), eq("orders/2"), anyList());
    }

    @Test
    void aMaskedPropertyIsListedWithTheStringsWhateverItsOriginalType() {
        browses(List.of(message(1, "a", BodyEncoding.TEXT)));
        Mockito.doAnswer(invocation -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("attempt", "[redacted]");
                    properties.put("nothing", null);
                    properties.put("seq", 9L);
                    properties.put("stray", 5L);
                    return new GovernedMessage(Map.of(), properties, "a", List.of(), List.of(), Map.of(), 1);
                })
                .when(policy)
                .govern(any(), any());

        MessageDetailView detail = service.detail(CLUSTER, "orders", 1, null, null);

        assertThat(detail.stringProperties())
                .containsEntry("attempt", "[redacted]")
                .containsEntry("nothing", "null");
        assertThat(detail.longProperties()).containsEntry("seq", 9L);
        // A number the broker did not report under either integer map is not guessed at.
        assertThat(detail.stringProperties()).containsEntry("stray", "5");
    }

    @Test
    void aBytesMessageReadAsCompressedTextIsDetailedAsTextWithItsCompression() {
        BrowsedMessage m = message(3, "{\"a\":1}", BodyEncoding.TEXT);
        BrowsedMessage gzipped = new BrowsedMessage(
                m.messageId(),
                4,
                m.durable(),
                m.priority(),
                m.timestamp(),
                m.expiration(),
                m.size(),
                m.groupId(),
                m.correlationId(),
                m.replyTo(),
                m.userId(),
                m.body(),
                BodyEncoding.TEXT,
                Compression.GZIP,
                m.contentType(),
                false,
                null,
                m.stringProperties(),
                m.intProperties(),
                m.longProperties(),
                m.doubleProperties(),
                m.booleanProperties());
        browses(List.of(gzipped));

        MessageDetailView detail = service.detail(CLUSTER, "orders", 3, null, null);

        assertThat(detail.type()).isEqualTo(4);
        assertThat(detail.bodyEncoding()).isEqualTo("TEXT");
        assertThat(detail.bodyCompression()).isEqualTo("gzip");
        assertThat(detail.body()).isEqualTo("{\"a\":1}");
        // Text, so the content policy masks it field by field instead of withholding it as binary.
        assertThat(MessageService.content(gzipped).base64()).isFalse();
        assertThat(service.detail(CLUSTER, "orders", 3, null, null).withheld()).isEmpty();
    }

    @Test
    void detailOfAnAbsentMessageIs404() {
        browses(List.of(message(1, "a", BodyEncoding.TEXT)));

        assertThatThrownBy(() -> service.detail(CLUSTER, "orders", 99, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- resolving a queue ----------------------------------------------------------

    @Test
    void anUnknownQueueIs404() {
        when(locator.locate(CLUSTER, "nope")).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolve(CLUSTER, "nope", null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aQueueOnlyOnNodesWithoutAManagementUrlIsUnreachable() {
        ClusterNode bare = node(NODE_A, "node-a", null, null, true);
        when(directory.nodes(CLUSTER)).thenReturn(List.of(bare));

        assertThatThrownBy(() -> service.resolve(CLUSTER, "orders", null))
                .isInstanceOfSatisfying(BrokerConnectionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BrokerConnectionException.Kind.UNREACHABLE);
                    assertThat(e.getMessage()).contains("No manageable node holds queue 'orders'");
                });
    }

    @Test
    void aRequestedNodeThatDoesNotHoldTheQueueIs404() {
        UUID otherNode = UUID.randomUUID();

        assertThatThrownBy(() -> service.resolve(CLUSTER, "orders", otherNode)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRequestedNodeIsHonouredEvenWhenItHoldsFewerMessages() {
        assertThat(service.resolve(CLUSTER, "orders", NODE_A).node().getId()).isEqualTo(NODE_A);
    }

    @Test
    void withoutARequestedNodeALiveOneIsPreferredOverTheBusiestPassiveOne() {
        ClusterNode passiveBusy = node(NODE_B, "node-b", "http://b/jolokia", "tcp://b", false);
        when(directory.nodes(CLUSTER)).thenReturn(List.of(nodeA, passiveBusy));

        assertThat(service.resolve(CLUSTER, "orders", null).node().getId()).isEqualTo(NODE_A);
    }

    @Test
    void withNoLiveNodeTheBusiestOneIsUsed() {
        ClusterNode passiveA = node(NODE_A, "node-a", "http://a/jolokia", "tcp://a", false);
        ClusterNode passiveB = node(NODE_B, "node-b", "http://b/jolokia", "tcp://b", false);
        when(directory.nodes(CLUSTER)).thenReturn(List.of(passiveA, passiveB));

        assertThat(service.resolve(CLUSTER, "orders", null).node().getId()).isEqualTo(NODE_B);
    }

    // ---- send -----------------------------------------------------------------------------

    private static SendMessageRequest sendRequest() {
        return new SendMessageRequest(MessageKind.TEXT, true, "{}", Map.of("correlationId", "c-1"), Map.of("p", 2));
    }

    @Test
    void aSendDryRunIsAuditedAndNeverReachesTheBroker() {
        Attempt<Outcome> result = service.send(CLUSTER, "orders", null, sendRequest(), true);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class,
                        ok -> assertThat(ok.value()).isEqualTo(new Outcome.DryRun(1, 100, false, NODE_B)));
        verify(audit).succeed(event, 1);
        verify(messages, never()).send(any(), any(), any(), any());
        verify(hub, never()).publishAbout(any(UUID.class), anyString(), anyCollection(), anyCollection());
    }

    @Test
    void aSendWithAnInvalidPropertyNameIsRefusedBeforeItIsAudited() {
        SendMessageRequest bad =
                new SendMessageRequest(MessageKind.TEXT, true, "{}", Map.of(), Map.of("x-long-name", "v"));

        assertThatThrownBy(() -> service.send(CLUSTER, "orders", null, bad, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'x-long-name'");
        verify(audit, never())
                .begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), any(Boolean.class));
        verify(messages, never()).send(any(), any(), any(), any());
    }

    @Test
    void aBytesSendWhoseBodyIsNotBase64IsRefusedBeforeItIsAudited() {
        SendMessageRequest bad = new SendMessageRequest(MessageKind.BYTES, true, "not base64!", Map.of(), Map.of());

        assertThatThrownBy(() -> service.send(CLUSTER, "orders", null, bad, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
        verify(audit, never())
                .begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), any(Boolean.class));
        verify(messages, never()).send(any(), any(), any(), any());
    }

    @Test
    void aSendWithAnUnknownHeaderIsRefusedBeforeItIsAudited() {
        SendMessageRequest bad = new SendMessageRequest(MessageKind.TEXT, true, "{}", Map.of("h", 1), Map.of());

        assertThatThrownBy(() -> service.send(CLUSTER, "orders", null, bad, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported header 'h'");
        verify(audit, never())
                .begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void aSendGoesToTheQueuesAddressThroughItsNodeAndRefreshesTheQueuesTopic() {
        Attempt<Outcome> result = service.send(CLUSTER, "orders", NODE_A, sendRequest(), false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(1, NODE_A)));
        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(messages).send(eq(CLUSTER), eq(NODE_A), eq("orders.addr"), sent.capture());
        assertThat(sent.getValue().headers().durable()).contains(true);
        assertThat(sent.getValue().headers().correlationId()).contains("c-1");
        assertThat(sent.getValue().body().bytes().orElseThrow().toByteArray()).isEqualTo("{}".getBytes());
        verify(audit).succeed(event, 1);
        verify(hub).publishAbout(eq(CLUSTER), eq("queues"), anyCollection(), anyCollection());
    }

    @Test
    void aSendThatTheBrokerRefusesIsReportedAndAuditedAsFailed() {
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.CREDENTIALS_REJECTED, "denied"))
                .when(messages)
                .send(any(), any(), any(), any());

        Attempt<Outcome> result = service.send(CLUSTER, "orders", null, sendRequest(), false);

        assertThat(result)
                .isEqualTo(new Attempt.Failed<Outcome>(BrokerConnectionException.Kind.CREDENTIALS_REJECTED, "denied"));
        verify(audit).fail(event, "denied");
        verify(hub, never()).publishAbout(any(UUID.class), anyString(), anyCollection(), anyCollection());
    }

    @Test
    void thePublishIsDeferredUntilCommitInsideATransaction() {
        TransactionSynchronizationManager.initSynchronization();

        service.send(CLUSTER, "orders", null, sendRequest(), false);

        verify(hub, never()).publishAbout(any(UUID.class), anyString(), anyCollection(), anyCollection());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(hub).publishAbout(eq(CLUSTER), eq("queues"), anyCollection(), anyCollection());
    }

    // ---- move, retry, delete, expire ----------------------------------------------------------

    private static MessageActionRequest ids(Long... ids) {
        return new MessageActionRequest(List.of(ids), null, null);
    }

    private static MessageActionRequest filter(String filter, String target) {
        return new MessageActionRequest(null, filter, target);
    }

    private void counts(String filter, long count) {
        when(messages.count(CLUSTER, NODE_B, "orders", filter)).thenReturn(count);
    }

    @Test
    void aMoveWithoutATargetIsRefusedAndTheAuditRowClosedAsFailed() {
        var request = ids(1L);

        assertThatThrownBy(() -> service.execute(CLUSTER, "orders", null, MessageAction.MOVE, request, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MOVE requires a target queue.");
        verify(audit).fail(event, "MOVE requires a target queue.");
        verify(messages, never()).byIds(any(), any(), any(), anyList(), any());
    }

    @Test
    void aMoveWithABlankTargetIsRefusedToo() {
        var request = new MessageActionRequest(List.of(1L), null, " ");

        assertThatThrownBy(() -> service.execute(CLUSTER, "orders", null, MessageAction.MOVE, request, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aByIdDryRunNeedsNoBrokerCall() {
        Attempt<Outcome> result =
                service.execute(CLUSTER, "orders", null, MessageAction.DELETE, ids(1L, 2L, 3L), true, false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class,
                        ok -> assertThat(ok.value()).isEqualTo(new Outcome.DryRun(3, 100, false, NODE_B)));
        verify(audit).succeed(event, 3);
        verify(messages, never()).count(any(), any(), any(), any());
        verify(messages, never()).byIds(any(), any(), any(), anyList(), any());
    }

    @Test
    void aByIdDryRunOverTheCapSaysSo() {
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(2);

        Attempt<Outcome> result =
                service.execute(CLUSTER, "orders", null, MessageAction.EXPIRE, ids(1L, 2L, 3L), true, false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class,
                        ok -> assertThat(ok.value()).isEqualTo(new Outcome.DryRun(3, 2, true, NODE_B)));
    }

    @Test
    void aByFilterDryRunAsksTheBrokerToCount() {
        counts("priority > 3", 7L);

        Attempt<Outcome> result = service.execute(
                CLUSTER, "orders", null, MessageAction.DELETE, filter("priority > 3", null), true, false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class,
                        ok -> assertThat(ok.value()).isEqualTo(new Outcome.DryRun(7, 100, false, NODE_B)));
        verify(messages, never()).byFilter(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void aRetryAllDryRunCountsTheWholeQueue() {
        counts(null, 12L);

        Attempt<Outcome> result = service.execute(
                CLUSTER,
                "orders",
                null,
                MessageAction.RETRY,
                new MessageActionRequest(null, "ignored", null),
                true,
                false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class,
                        ok -> assertThat(ok.value()).isEqualTo(new Outcome.DryRun(12, 100, false, NODE_B)));
    }

    @Test
    void aRetryAllRetriesEverythingAndRefreshesTheQueues() {
        counts(null, 12L);
        when(access.holds(CLUSTER, MessagePermissions.MESSAGE_SEND)).thenReturn(true);
        when(messages.byFilter(CLUSTER, NODE_B, "orders", null, Optional.empty(), new Retry()))
                .thenReturn(done(12));

        Attempt<Outcome> result = service.execute(
                CLUSTER, "orders", null, MessageAction.RETRY, new MessageActionRequest(null, null, null), false, false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(12, NODE_B)));
        verify(audit).succeed(event, 12);
        verify(hub).publishAbout(eq(CLUSTER), eq("queues"), anyCollection(), anyCollection());
    }

    @Test
    void byFilterOperationsEachCallTheirOwnBrokerOperation() {
        counts("f", 1L);
        when(messages.byFilter(
                        CLUSTER,
                        NODE_B,
                        "orders",
                        "f",
                        Optional.empty(),
                        new Move(ResourceId.of("artemis.queue", "target"))))
                .thenReturn(done(1));
        when(messages.byFilter(CLUSTER, NODE_B, "orders", "f", Optional.empty(), new Delete()))
                .thenReturn(done(2));
        when(expiry.byFilter(CLUSTER, NODE_B, "orders", "f")).thenReturn(3L);

        assertThat(service.execute(CLUSTER, "orders", null, MessageAction.MOVE, filter("f", "target"), false, false))
                .isInstanceOfSatisfying(
                        Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(1, NODE_B)));
        assertThat(service.execute(CLUSTER, "orders", null, MessageAction.DELETE, filter("f", null), false, false))
                .isInstanceOfSatisfying(
                        Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(2, NODE_B)));
        assertThat(service.execute(CLUSTER, "orders", null, MessageAction.EXPIRE, filter("f", null), false, false))
                .isInstanceOfSatisfying(
                        Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(3, NODE_B)));
    }

    @Test
    void anActionOverTheCapIsRefusedUnlessOverridden() {
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(5);
        counts("f", 9L);
        when(messages.byFilter(CLUSTER, NODE_B, "orders", "f", Optional.empty(), new Delete()))
                .thenReturn(done(9));

        var request = filter("f", null);

        assertThatThrownBy(() -> service.execute(CLUSTER, "orders", null, MessageAction.DELETE, request, false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(audit).fail(event, "Over the safety cap (9 > 5).");

        assertThat(service.execute(CLUSTER, "orders", null, MessageAction.DELETE, request, false, true))
                .isInstanceOfSatisfying(
                        Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(9, NODE_B)));
    }

    @Test
    void byIdOperationsCallTheirOwnBrokerOperation() {
        when(access.holds(CLUSTER, MessagePermissions.MESSAGE_SEND)).thenReturn(true);
        when(messages.byIds(eq(CLUSTER), eq(NODE_B), eq("orders"), eq(List.of(1L, 2L)), any()))
                .thenReturn(done(2));
        when(expiry.byIds(CLUSTER, NODE_B, "orders", List.of(1L, 2L))).thenReturn(new BulkResult(2, List.of(), null));

        for (MessageAction action : MessageAction.values()) {
            MessageActionRequest request = new MessageActionRequest(List.of(1L, 2L), null, "target");
            assertThat(service.execute(CLUSTER, "orders", null, action, request, false, false))
                    .as(action.name())
                    .isInstanceOfSatisfying(
                            Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(2, NODE_B)));
        }
    }

    @Test
    void anIdOperationThatStoppedPartWayIsReportedAsPartialNotFailed() {
        ResourceId queue = ResourceId.of("artemis.queue", "orders");
        when(messages.byIds(CLUSTER, NODE_B, "orders", List.of(1L, 2L, 3L), new Delete()))
                .thenReturn(new BulkOutcome(
                        1,
                        List.of(
                                new MessageRef(queue, Shard.node(NodeId.of("b")), "2"),
                                new MessageRef(queue, Shard.node(NodeId.of("b")), "3")),
                        "connection reset"));

        Attempt<Outcome> result =
                service.execute(CLUSTER, "orders", null, MessageAction.DELETE, ids(1L, 2L, 3L), false, false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class,
                        ok -> assertThat(ok.value())
                                .isEqualTo(new Outcome.Partial(1, List.of(2L, 3L), "connection reset", NODE_B)));
        verify(audit).failPartial(event, 1, "Stopped after 1 of 3: connection reset");
        verify(audit, never()).succeed(any(), anyLong());
        verify(hub).publishAbout(eq(CLUSTER), eq("queues"), anyCollection(), anyCollection());
    }

    @Test
    void aBrokerFailureDuringAnActionIsAuditedAndReturned() {
        when(messages.count(any(), any(), anyString(), anyString()))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "down"));

        Attempt<Outcome> result =
                service.execute(CLUSTER, "orders", null, MessageAction.DELETE, filter("f", null), false, false);

        assertThat(result).isEqualTo(new Attempt.Failed<Outcome>(BrokerConnectionException.Kind.UNREACHABLE, "down"));
        verify(audit).fail(event, "down");
    }

    @Test
    void aFilterTheBrokerRejectsClosesTheAuditRowAndRethrows() {
        when(messages.count(any(), any(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("bad selector"));

        var request = filter("f", null);

        assertThatThrownBy(() -> service.execute(CLUSTER, "orders", null, MessageAction.DELETE, request, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(audit).fail(event, "bad selector");
    }

    @Test
    void theAuditParametersNameTheFilterOrTheIdCountAndTheTarget() {
        when(messages.byFilter(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(done(0));
        when(messages.byIds(any(), any(), anyString(), anyList(), any())).thenReturn(done(0));

        service.execute(CLUSTER, "orders", null, MessageAction.MOVE, filter("f", "t"), false, false);
        service.execute(
                CLUSTER,
                "orders",
                null,
                MessageAction.MOVE,
                new MessageActionRequest(List.of(1L, 2L), null, "t"),
                false,
                false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(audit, times(2))
                .begin(
                        any(),
                        eq("MOVE_MESSAGES"),
                        eq("QUEUE"),
                        eq("orders"),
                        eq(CLUSTER),
                        eq(NODE_B),
                        params.capture(),
                        eq(false));
        assertThat(params.getAllValues().get(0)).containsEntry("filter", "f").containsEntry("target", "t");
        assertThat(params.getAllValues().get(1)).containsEntry("ids", 2).containsEntry("target", "t");
    }

    // ---- purge --------------------------------------------------------------------------------

    @Test
    void aPurgeDryRunEstimatesFromTheQueueDepth() {
        counts(null, 150L);

        Attempt<Outcome> result = service.purge(CLUSTER, "orders", null, true, false);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Attempt.Ok.class,
                        ok -> assertThat(ok.value()).isEqualTo(new Outcome.DryRun(150, 100, true, NODE_B)));
        verify(messages, never()).purge(any(), any(), any());
    }

    @Test
    void aPurgeOverTheCapNeedsAnOverride() {
        counts(null, 150L);
        when(messages.purge(CLUSTER, NODE_B, "orders")).thenReturn(150L);

        assertThatThrownBy(() -> service.purge(CLUSTER, "orders", null, false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(audit).fail(event, "Over the safety cap (150 > 100).");
        verify(messages, never()).purge(any(), any(), any());

        assertThat(service.purge(CLUSTER, "orders", null, false, true))
                .isInstanceOfSatisfying(
                        Attempt.Ok.class, ok -> assertThat(ok.value()).isEqualTo(new Outcome.Affected(150, NODE_B)));
        verify(hub).publishAbout(eq(CLUSTER), eq("queues"), anyCollection(), anyCollection());
    }

    @Test
    void aPurgeUnderTheCapRunsAndAuditsTheRemovedCount() {
        counts(null, 3L);
        when(messages.purge(CLUSTER, NODE_B, "orders")).thenReturn(3L);

        service.purge(CLUSTER, "orders", null, false, false);

        verify(audit).succeed(event, 3);
    }

    @Test
    void aPurgeThatCannotReachTheBrokerIsAuditedAndReturned() {
        when(messages.count(any(), any(), anyString(), any()))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "down"));

        assertThat(service.purge(CLUSTER, "orders", null, false, false))
                .isEqualTo(new Attempt.Failed<Outcome>(BrokerConnectionException.Kind.UNREACHABLE, "down"));
        verify(audit).fail(event, "down");
    }

    // ---- what each operation asks of the caller's access -----------------------------------------------

    private List<Requirement> required() {
        ArgumentCaptor<List<Requirement>> needs = ArgumentCaptor.forClass(List.class);
        verify(access).requireAll(eq(CLUSTER), needs.capture());
        return needs.getValue();
    }

    @Test
    void browsingNeedsMessageReadOnTheQueue() {
        browses(List.of(message(1, "hello", BodyEncoding.TEXT)));

        service.browse(CLUSTER, "orders", null, null, 1, 50);

        verify(access).requireResource(CLUSTER, ResourceRef.subscription("orders"), MessagePermissions.MESSAGE_READ);
    }

    @Test
    void purgingNeedsQueuePurgeOnTheQueueBeforeAnythingIsLookedUp() {
        service.purge(CLUSTER, "orders", null, true, false);

        verify(access)
                .requireResource(CLUSTER, ResourceRef.subscription("orders"), MessagePermissions.SUBSCRIPTION_PURGE);
    }

    @Test
    void sendingNeedsTheQueueToBeReadableAndMessageSendOnItsAddress() {
        service.send(
                CLUSTER,
                "orders",
                null,
                new SendMessageRequest(MessageKind.TEXT, true, "hi", Map.of(), Map.of()),
                true);

        verify(access).requireResource(CLUSTER, ResourceRef.subscription("orders"), Permissions.SUBSCRIPTION_READ);
        verify(access)
                .requireResource(CLUSTER, ResourceRef.destination("orders.addr"), MessagePermissions.MESSAGE_SEND);
    }

    @Test
    void aQueueWhoseAddressTheCallerMayNotUseIsNotFoundToThemNamingNoAddress() {
        doThrow(new NotFoundException("address", "orders.addr"))
                .when(access)
                .requireResource(CLUSTER, ResourceRef.destination("orders.addr"), MessagePermissions.MESSAGE_SEND);

        SendMessageRequest request = new SendMessageRequest(MessageKind.TEXT, true, "hi", Map.of(), Map.of());

        assertThatThrownBy(() -> service.send(CLUSTER, "orders", null, request, true))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("queue orders does not exist.");
    }

    @Test
    void deletingNeedsMessageDeleteOnTheQueue() {
        service.execute(CLUSTER, "orders", null, MessageAction.DELETE, filter("f", null), true, false);

        assertThat(required())
                .containsExactly(
                        new Requirement(ResourceRef.subscription("orders"), MessagePermissions.MESSAGE_DELETE));
    }

    @Test
    void movingNeedsMessageMoveOnTheSourceAndMessageSendOnTheTargetsAddress() {
        when(locator.locate(CLUSTER, "billing"))
                .thenReturn(List.of(new QueueLocation(NODE_A, "billing", "billing.addr", "ANYCAST", 1)));

        service.execute(CLUSTER, "orders", null, MessageAction.MOVE, filter("f", "billing"), true, false);

        assertThat(required())
                .containsExactly(
                        new Requirement(ResourceRef.subscription("orders"), MessagePermissions.MESSAGE_MOVE),
                        new Requirement(ResourceRef.subscription("billing"), Permissions.SUBSCRIPTION_READ),
                        new Requirement(ResourceRef.destination("billing.addr"), MessagePermissions.MESSAGE_SEND));
    }

    @Test
    void aMoveToAQueueNoNodeKnowsIsRefusedAsOneThatMayNotBeReadIsAndNothingIsGuessedFromItsName() {
        NotFoundException unknown = new NotFoundException("A queue or address named in the request does not exist.");
        when(access.unreadableAmongSeveral(CLUSTER)).thenReturn(unknown);

        assertThatThrownBy(() -> service.execute(
                        CLUSTER, "orders", null, MessageAction.MOVE, filter("f", "ghost"), false, false))
                .isSameAs(unknown);
        verify(access, never()).requireAll(any(), any());
        verify(messages, never()).byFilter(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void aRetryNeedsMessageSendOnEveryAddressTheDeadLettersCameFrom() {
        origins("a", "b");
        counts(null, 3L);

        service.execute(
                CLUSTER, "orders", null, MessageAction.RETRY, new MessageActionRequest(null, null, null), true, false);

        verify(access)
                .requireAll(
                        CLUSTER,
                        List.of(
                                new Requirement(ResourceRef.destination("a"), MessagePermissions.MESSAGE_SEND),
                                new Requirement(ResourceRef.destination("b"), MessagePermissions.MESSAGE_SEND)));
    }

    @Test
    void aRetryByACallerWhoMaySendAnywhereDoesNotLookAtTheOriginalAddresses() {
        when(access.holds(CLUSTER, MessagePermissions.MESSAGE_SEND)).thenReturn(true);
        counts(null, 3L);

        service.execute(
                CLUSTER, "orders", null, MessageAction.RETRY, new MessageActionRequest(null, null, null), true, false);

        verify(messages, never()).countBy(any(), any(), any(), any());
    }

    @Test
    void aRetryOfEverythingByACallerWhoMayNotSendEverywhereRetriesOnlyTheMessagesFromTheCheckedAddresses() {
        origins("a", "b");
        counts(null, 4L);
        when(messages.byFilter(CLUSTER, NODE_B, "orders", fromOrigin("a"), new Retry()))
                .thenReturn(done(2));
        when(messages.byFilter(CLUSTER, NODE_B, "orders", fromOrigin("b"), new Retry()))
                .thenReturn(done(1));

        Attempt<Outcome> result = service.execute(
                CLUSTER, "orders", null, MessageAction.RETRY, new MessageActionRequest(null, null, null), false, false);

        assertThat(result).isEqualTo(new Attempt.Ok<Outcome>(new Outcome.Affected(3, NODE_B)));
        verify(messages, never()).byFilter(any(), any(), any(), any(String.class), any(), any());
    }

    @Test
    void aRetryByIdsLeavesOutAMessageThatDidNotComeFromACheckedAddress() {
        origins("a");
        when(messages.ids(CLUSTER, NODE_B, "orders", fromOrigin("a"))).thenReturn(Set.of(1L));
        when(messages.byIds(CLUSTER, NODE_B, "orders", List.of(1L), new Retry()))
                .thenReturn(done(1));

        service.execute(
                CLUSTER,
                "orders",
                null,
                MessageAction.RETRY,
                new MessageActionRequest(List.of(1L, 99L), null, null),
                false,
                false);

        verify(messages).byIds(CLUSTER, NODE_B, "orders", List.of(1L), new Retry());
        verify(messages, never()).byIds(CLUSTER, NODE_B, "orders", List.of(1L, 99L), new Retry());
    }

    @Test
    void aRetryWhoseOriginsCannotBeReadDoesNotProceed() {
        when(messages.countBy(CLUSTER, NODE_B, "orders", MessageField.header("origin")))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "down"));

        Attempt<Outcome> result = service.execute(
                CLUSTER, "orders", null, MessageAction.RETRY, new MessageActionRequest(null, null, null), false, false);

        assertThat(result).isEqualTo(new Attempt.Failed<Outcome>(BrokerConnectionException.Kind.UNREACHABLE, "down"));
        verify(messages, never()).byFilter(any(), any(), any(), any(String.class), any(), any());
    }

    // ---- the policy's view of a message ----------------------------------------------------------------

    @Test
    void contentCarriesTheIdentifyingHeadersAndEveryTypedProperty() {
        MessageContent content = MessageService.content(message(1, "b", BodyEncoding.BASE64));

        assertThat(content.headers())
                .containsEntry("correlationId", "corr-1")
                .containsEntry("groupId", "group-1")
                .containsEntry("userId", "user-1")
                .containsEntry("replyTo", "reply");
        assertThat(content.properties())
                .containsEntry("tenant", "acme")
                .containsEntry("attempt", 2L)
                .containsEntry("seq", 9L)
                .containsEntry("ratio", 0.5)
                .containsEntry("urgent", true);
        assertThat(content.base64()).isTrue();
        assertThat(content.contentType()).isEqualTo("application/json");
        assertThat(MessageService.content(message(1, "b", BodyEncoding.TEXT)).base64())
                .isFalse();
    }

    // ---- the approval gate ---------------------------------------------------------------------------------

    private Operation gatedOperation() {
        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(gate).run(operation.capture(), any());
        return operation.getValue();
    }

    @Test
    void aDryRunIsNeverGated() {
        counts(null, 4L);

        service.purge(CLUSTER, "orders", null, true, false);
        service.execute(
                CLUSTER,
                "orders",
                null,
                MessageAction.DELETE,
                new MessageActionRequest(List.of(1L, 2L), null, null),
                true,
                false);

        verify(gate, never()).run(any(), any());
    }

    @Test
    void eachRealActionPassesTheGateAsItsOwnTypeWithTheRequestAsItsParameters() {
        MessageActionRequest request = new MessageActionRequest(List.of(1L, 2L), null, "target");
        when(access.holds(CLUSTER, MessagePermissions.MESSAGE_SEND)).thenReturn(true);
        when(messages.byIds(any(), any(), anyString(), anyList(), any())).thenReturn(done(2));
        when(expiry.byIds(any(), any(), anyString(), anyList())).thenReturn(new BulkResult(2, List.of(), null));

        service.execute(CLUSTER, "orders", null, MessageAction.MOVE, request, false, true);
        service.execute(CLUSTER, "orders", null, MessageAction.RETRY, request, false, true);
        service.execute(CLUSTER, "orders", null, MessageAction.DELETE, request, false, true);
        service.execute(CLUSTER, "orders", null, MessageAction.EXPIRE, request, false, true);

        ArgumentCaptor<Operation> operations = ArgumentCaptor.forClass(Operation.class);
        verify(gate, times(4)).run(operations.capture(), any());
        assertThat(operations.getAllValues())
                .extracting(Operation::params)
                .containsExactly(
                        new MessageMoveParams(CLUSTER, "orders", null, request, true),
                        new MessageRetryParams(CLUSTER, "orders", null, request, true),
                        new MessageDeleteParams(CLUSTER, "orders", null, request, true),
                        new MessageExpireParams(CLUSTER, "orders", null, request, true));
    }

    @Test
    void aPurgePassesTheGateAsAQueuePurge() {
        counts(null, 3L);
        when(messages.purge(CLUSTER, NODE_B, "orders")).thenReturn(3L);

        service.purge(CLUSTER, "orders", null, false, false);

        assertThat(gatedOperation().params()).isEqualTo(new QueuePurgeParams(CLUSTER, "orders", null, false));
    }

    @Test
    void aHeldActionTouchesNeitherTheBrokerNorTheAuditTrail() {
        doThrow(new OperationHeldException(UUID.randomUUID(), "Purge queue orders", Instant.now()))
                .when(gate)
                .run(any(), any());

        MessageActionRequest request = new MessageActionRequest(List.of(1L), null, null);

        assertThatThrownBy(() -> service.purge(CLUSTER, "orders", null, false, false))
                .isInstanceOf(OperationHeldException.class);
        assertThatThrownBy(() -> service.execute(CLUSTER, "orders", null, MessageAction.DELETE, request, false, false))
                .isInstanceOf(OperationHeldException.class);

        verify(messages, never()).purge(any(), any(), any());
        verify(messages, never()).byIds(any(), any(), any(), anyList(), any());
        verify(audit, never())
                .begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void aGatedActionStillAuthorizesFirst() {
        doThrow(new AccessDeniedException("no")).when(access).requireAll(eq(CLUSTER), anyList());

        MessageActionRequest request = new MessageActionRequest(List.of(1L), null, null);

        assertThatThrownBy(() -> service.execute(CLUSTER, "orders", null, MessageAction.DELETE, request, false, false))
                .isInstanceOf(AccessDeniedException.class);

        verify(gate, never()).run(any(), any());
    }

    @Test
    void whatAnActionReachesIsReadWithoutAnAuditRowAndPinsTheQueuesIdentity() {
        counts("priority > 3", 7L);

        assertThat(service.reach(
                        CLUSTER,
                        "orders",
                        null,
                        MessageAction.DELETE,
                        new MessageActionRequest(null, "priority > 3", null)))
                .isEqualTo(new MessageService.Reach(7, ETAG));
        assertThat(service.reach(
                        CLUSTER,
                        "orders",
                        null,
                        MessageAction.DELETE,
                        new MessageActionRequest(List.of(1L, 2L, 3L), null, null)))
                .isEqualTo(new MessageService.Reach(3, ETAG));
        verify(audit, never())
                .begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void aPurgeReachIsTheQueueDepthAndTheIdentityTheBrokerGivesTheQueueOnItsNode() {
        counts(null, 12L);

        assertThat(service.reachOfPurge(CLUSTER, "orders", null)).isEqualTo(new MessageService.Reach(12, ETAG));
        verify(messages).identity(CLUSTER, NODE_B, "orders");
    }

    private void origins(String... addresses) {
        Map<String, Long> byOrigin = new LinkedHashMap<>();
        for (String address : addresses) {
            byOrigin.put(address, 1L);
        }
        when(messages.countBy(CLUSTER, NODE_B, "orders", MessageField.header("origin")))
                .thenReturn(byOrigin);
    }

    private static MessageFilter fromOrigin(String address) {
        return MessageFilter.portable("header.origin = '" + address + "'");
    }
}
