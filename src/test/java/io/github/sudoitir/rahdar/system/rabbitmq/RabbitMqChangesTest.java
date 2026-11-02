package io.github.sudoitir.rahdar.system.rabbitmq;

import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.ETAG;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.change;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.preview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.AccessRuleClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.CreatedView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangeResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** A change is previewed, refused when its etag moved, held by the gate, audited and run under the etag. */
class RabbitMqChangesTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String VHOST =
            ResourceId.of(RabbitMqIds.VHOST, "orders").format();

    private final AuditService audit = mock(AuditService.class);
    private final RabbitMqOps ops = mock(RabbitMqOps.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final RabbitMqChanges changes = new RabbitMqChanges(ops, gate, audit, mock(ActorResolver.class));

    RabbitMqChangesTest() {
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    }

    @Test
    void aChangeRunsUnderThePreviewsEtagAndRecordsWhatChanged() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any()))
                .thenReturn(preview(ETAG, 12, change(RabbitMqIds.DESCRIPTION, "old", null)));
        when(ops.execute(eq(CLUSTER), any(), eq(Verb.DELETE), any(), eq(ETAG))).thenReturn(OperationResult.done());

        RabbitMqChangeResult result = changes.apply(new NamespaceDelete(CLUSTER, VHOST, Map.of(), ETAG));

        assertThat(result.id()).isEqualTo(VHOST);
        assertThat(result.identityBasis()).isEqualTo("CONFIGURATION");
        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().getFirst().before()).isEqualTo("old");
        assertThat(result.changes().getFirst().after()).isNull();
        verify(audit)
                .begin(
                        any(),
                        eq("DELETE_NAMESPACE"),
                        eq("NAMESPACE"),
                        eq("orders"),
                        eq(CLUSTER),
                        isNull(),
                        any(),
                        eq(false));
        verify(audit).finish(any(), eq(false), anyLong(), isNull(), any());
    }

    @Test
    void aChangeWhoseEtagIsNotThePreviewsNeverReachesTheGate() {
        when(ops.preview(eq(CLUSTER), any(), any(), any())).thenReturn(preview("etag-2", 0));

        var namespaceCreate = new NamespaceCreate(CLUSTER, VHOST, Map.of(), ETAG);
        assertThatThrownBy(() -> changes.apply(namespaceCreate)).isInstanceOf(StalePreviewException.class);
        var namespaceCreate2 = new NamespaceCreate(CLUSTER, VHOST, Map.of(), null);
        assertThatThrownBy(() -> changes.apply(namespaceCreate2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Preview first");

        verify(gate, never()).run(any(), any());
    }

    @Test
    void everyKindOfChangeIsHandedToTheGate() {
        when(ops.preview(eq(CLUSTER), any(), any(), any())).thenReturn(preview(ETAG, 0));
        when(ops.execute(eq(CLUSTER), any(), any(), any(), any())).thenReturn(OperationResult.done());
        String policy = ResourceId.of(RabbitMqIds.POLICY, "/", "ha").format();
        String binding = ResourceId.of(RabbitMqIds.BINDING, "/", "x", "q", "k").format();
        String permission = ResourceId.of(RabbitMqIds.PERMISSION, "/", "app").format();

        List<RabbitMqChanges.Change> all = List.of(
                new NamespaceCreate(CLUSTER, VHOST, Map.of(), ETAG),
                new RabbitMqChanges.NamespaceUpdate(CLUSTER, VHOST, Map.of(), ETAG),
                new NamespaceDelete(CLUSTER, VHOST, Map.of(), ETAG),
                new RabbitMqChanges.RouteCreate(CLUSTER, binding, Map.of(), ETAG),
                new RouteDelete(CLUSTER, binding, Map.of(), ETAG),
                new RabbitMqChanges.PolicySet(CLUSTER, policy, Map.of(), ETAG),
                new RabbitMqChanges.PolicyClear(CLUSTER, policy, Map.of(), ETAG),
                new RabbitMqChanges.AccessRuleSet(CLUSTER, permission, Map.of(), ETAG),
                new AccessRuleClear(CLUSTER, permission, Map.of(), ETAG));

        all.forEach(changes::apply);

        verify(gate, times(9)).run(any(), any());
        assertThat(all).extracting(c -> c.type().gateType()).doesNotHaveDuplicates();
    }

    @Test
    void theGatesEstimateNamesWhatIsDoneAndWhatTheEtagDoesNotTellApart() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any())).thenReturn(preview(ETAG, 0));

        Effect effect = changes.estimate(new RouteDelete(
                CLUSTER, ResourceId.of(RabbitMqIds.SHOVEL, "/", "s").format(), Map.of(), ETAG));

        assertThat(effect.etag()).isEqualTo(ETAG);
        assertThat(effect.detail())
                .startsWith("Delete shovel / / s.")
                .contains("RabbitMQ does not tell a recreated resource apart");
    }

    @Test
    void aCreateCarriesThePreviewsEtagWhenItIsSent() {
        ResourceId queue = ResourceId.of(RabbitMqIds.CLASSIC_QUEUE, "/", "q");
        when(ops.preview(eq(CLUSTER), eq(queue), eq(Verb.CREATE), any())).thenReturn(preview(ETAG, 0));
        when(ops.execute(eq(CLUSTER), eq(queue), eq(Verb.CREATE), any(), eq(ETAG)))
                .thenReturn(OperationResult.done());

        CreatedView created = changes.create(CLUSTER, queue, Map.of(), ETAG, "CREATE_SUBSCRIPTION", "SUBSCRIPTION");
        changes.create(CLUSTER, queue, Map.of(), " ", "CREATE_SUBSCRIPTION", "SUBSCRIPTION");

        assertThat(created.id()).isEqualTo(queue.format());
        verify(ops).execute(CLUSTER, queue, Verb.CREATE, Map.of(), null);
        Map<String, Object> entries = Map.of();
        assertThatThrownBy(
                        () -> changes.create(CLUSTER, queue, entries, "stale", "CREATE_SUBSCRIPTION", "SUBSCRIPTION"))
                .isInstanceOf(StalePreviewException.class);
    }

    @Test
    void theNounOfAResourceIsInPlainWords() {
        assertThat(RabbitMqChanges.noun(ResourceId.of(RabbitMqIds.VHOST, "v"))).isEqualTo("virtual host");
        assertThat(RabbitMqChanges.noun(ResourceId.of(RabbitMqIds.EXCHANGE_BINDING, "v", "a", "b", "k")))
                .isEqualTo("exchange binding");
        assertThat(RabbitMqChanges.noun(ResourceId.of(RabbitMqIds.TOPIC_PERMISSION, "v", "u", "e")))
                .isEqualTo("topic permission");
        assertThat(RabbitMqChanges.noun(ResourceId.of(RabbitMqIds.POLICY, "v", "p")))
                .isEqualTo("policy");
    }
}
