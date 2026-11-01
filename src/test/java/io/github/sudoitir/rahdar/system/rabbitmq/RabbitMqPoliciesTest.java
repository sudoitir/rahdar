package io.github.sudoitir.rahdar.system.rabbitmq;

import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.ETAG;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.change;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.preview;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicyClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicySet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyPreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A policy is listed with what it matches, and a change previews the queues and exchanges it would reach. */
class RabbitMqPoliciesTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static final Resource HA = resource(
            "rabbitmq.policy/%2F/ha",
            Map.of(
                    RabbitMqIds.PATTERN,
                    "^orders",
                    RabbitMqIds.APPLY_TO,
                    "queues",
                    RabbitMqIds.PRIORITY,
                    1L,
                    RabbitMqIds.DEFINITION_PREFIX + "max-length",
                    "1000"));
    private static final Resource LOUD = resource(
            "rabbitmq.policy/%2F/loud",
            Map.of(RabbitMqIds.PATTERN, "orders\\.eu", RabbitMqIds.APPLY_TO, "all", RabbitMqIds.PRIORITY, 7L));
    private static final Resource OPERATOR = resource(
            "rabbitmq.operator-policy/%2F/cap",
            Map.of(RabbitMqIds.PATTERN, "billing", RabbitMqIds.APPLY_TO, "all", RabbitMqIds.PRIORITY, 0L));

    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final RabbitMqOps ops = mock(RabbitMqOps.class);
    private final RabbitMqChanges changes = mock(RabbitMqChanges.class);
    private final RabbitMqPolicies policies;

    RabbitMqPoliciesTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = RabbitMqFixtures.connection(RabbitMqPoliciesTest::answer);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        policies = new RabbitMqPolicies(gateway, access, ops, changes);
    }

    private static List<Resource> answer(ResourceQuery query) {
        if (query.resourceClass().filter(c -> c == ResourceClass.SUBSCRIPTION).isPresent()) {
            return List.of(
                    resource("rabbitmq.classic-queue/%2F/orders.eu", Map.of()),
                    resource("rabbitmq.quorum-queue/%2F/orders.us", Map.of()),
                    resource("rabbitmq.stream/%2F/billing", Map.of()));
        }
        return switch (query.kind().orElseThrow().value()) {
            case RabbitMqIds.POLICY -> List.of(HA, LOUD);
            case RabbitMqIds.OPERATOR_POLICY -> List.of(OPERATOR);
            case RabbitMqIds.EXCHANGE -> List.of(resource("rabbitmq.exchange/%2F/orders", Map.of()));
            default -> List.of();
        };
    }

    @Test
    void aListHoldsEveryPolicyWithHowManyItMatches() {
        PolicyPage page = policies.list(CLUSTER, "/", null, null);

        verify(access).requireCluster(CLUSTER, RabbitMqModule.POLICY_READ);
        assertThat(page.items()).extracting(p -> p.name()).containsExactly("cap", "ha", "loud");
        assertThat(page.items()).extracting(p -> p.operator()).containsExactly(true, false, false);
        assertThat(page.items()).extracting(p -> p.matches()).containsExactly(1L, 2L, 1L);
        assertThat(page.items().get(1).definition()).containsEntry("max-length", "1000");
        assertThat(page.next()).isNull();
    }

    @Test
    void aListPagesByOffset() {
        PolicyPage first = policies.list(CLUSTER, null, null, 2);
        PolicyPage second = policies.list(CLUSTER, null, first.next(), 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.next()).isEqualTo("2");
        assertThat(second.items()).extracting(p -> p.name()).containsExactly("loud");
        assertThat(second.next()).isNull();
    }

    @Test
    void aSetPreviewListsWhatThePatternMatchesAndWhatAnotherPolicyWins() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.CREATE), any()))
                .thenReturn(preview(ETAG, 0, change(RabbitMqIds.PATTERN, null, "orders")));

        PolicyPreview preview = policies.previewSet(
                CLUSTER, "/", "mine", new PolicyRequest("orders", "queues", 2L, Map.of("max-length", "10"), null));

        verify(access).requireCluster(CLUSTER, RabbitMqModule.POLICY_WRITE);
        assertThat(preview.etag()).isEqualTo(ETAG);
        assertThat(preview.identityBasis()).isEqualTo("CONFIGURATION");
        assertThat(preview.effect().detail()).contains("as Rahdar matches it").contains("does not tell a recreated");
        assertThat(preview.matches()).extracting(m -> m.name()).containsExactly("orders.eu", "orders.us");
        assertThat(preview.matches()).extracting(m -> m.shadowedBy()).containsExactly("loud", null);
        assertThat(preview.partial()).isFalse();
    }

    @Test
    void aClearPreviewListsWhatStopsMatching() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any())).thenReturn(preview(ETAG, 0));

        PolicyPreview preview = policies.previewClear(CLUSTER, "/", "ha");

        assertThat(preview.matches()).extracting(m -> m.name()).containsExactly("orders.eu", "orders.us");
        assertThat(preview.effect().detail()).contains("2 queues and exchanges stop matching");
    }

    @Test
    void aClearPreviewOfAPolicyThatIsGoneMatchesNothing() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any())).thenReturn(preview(ETAG, 0));

        assertThat(policies.previewClear(CLUSTER, "/", "gone").matches()).isEmpty();
    }

    @Test
    void theRealCallsGoThroughTheGatedChanges() {
        PolicyRequest request = new PolicyRequest("orders", null, null, Map.of("max-length", "10"), "etag-1");

        policies.set(CLUSTER, "/", "mine", request);
        policies.clear(CLUSTER, "/", "mine", "etag-2");

        ArgumentCaptor<RabbitMqChanges.Change> change = ArgumentCaptor.forClass(RabbitMqChanges.Change.class);
        verify(changes, times(2)).apply(change.capture());
        assertThat(change.getAllValues().get(0)).isInstanceOf(PolicySet.class);
        assertThat(change.getAllValues().get(0).attributes())
                .containsEntry(RabbitMqIds.APPLY_TO, "all")
                .containsEntry(RabbitMqIds.PRIORITY, 0L)
                .containsEntry(RabbitMqIds.DEFINITION_PREFIX + "max-length", "10");
        assertThat(change.getAllValues().get(0).etag()).isEqualTo("etag-1");
        assertThat(change.getAllValues().get(1)).isInstanceOf(PolicyClear.class);
        assertThat(change.getAllValues().get(1).id()).isEqualTo("rabbitmq.policy/%2F/mine");
    }

    @Test
    void aPolicyMustBeWellFormed() {
        assertThatThrownBy(() ->
                        policies.set(CLUSTER, "/", "p", new PolicyRequest("(", null, null, Map.of("a", "1"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        policies.set(CLUSTER, "/", "p", new PolicyRequest("a", "bogus", null, Map.of("a", "1"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policies.set(CLUSTER, "/", "p", new PolicyRequest("a", null, null, Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setting");
        assertThatThrownBy(() ->
                        policies.set(CLUSTER, "/", "p", new PolicyRequest(" ", null, null, Map.of("a", "1"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern");
        assertThatThrownBy(() -> policies.clear(CLUSTER, "/", " ", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
