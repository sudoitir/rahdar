package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.AccessRuleClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.AccessRuleSet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceUpdate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicyClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicySet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteDelete;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The RabbitMQ operations the approval gate holds: their types, parameters, traits and what an approver reads. */
class RabbitMqGatedOperationsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID ENVIRONMENT = UUID.randomUUID();
    private static final String VHOST =
            ResourceId.of(RabbitMqIds.VHOST, "orders").format();
    private static final String POLICY =
            ResourceId.of(RabbitMqIds.POLICY, "/", "ha").format();
    private static final String BINDING =
            ResourceId.of(RabbitMqIds.BINDING, "/", "x", "q", "k").format();
    private static final String PERMISSION =
            ResourceId.of(RabbitMqIds.PERMISSION, "/", "app").format();
    private static final String ETAG = "e";

    private final RabbitMqChanges changes = mock(RabbitMqChanges.class);
    private final ClusterEnvironmentIndex clusters = mock(ClusterEnvironmentIndex.class);
    private final RabbitMqGatedOperations beans = new RabbitMqGatedOperations();

    RabbitMqGatedOperationsTest() {
        when(clusters.labelOf(CLUSTER)).thenReturn("prod-eu");
        when(clusters.environmentOf(CLUSTER)).thenReturn(ENVIRONMENT);
    }

    private List<GatedOperation<?>> all() {
        return List.of(
                beans.namespaceCreate(changes, clusters),
                beans.namespaceUpdate(changes, clusters),
                beans.namespaceDelete(changes, clusters),
                beans.routeCreate(changes, clusters),
                beans.routeDelete(changes, clusters),
                beans.policySet(changes, clusters),
                beans.policyClear(changes, clusters),
                beans.accessRuleSet(changes, clusters),
                beans.accessRuleClear(changes, clusters));
    }

    @Test
    void eachTypeHasItsOwnParametersRecord() {
        List<GatedOperation<?>> operations = all();

        assertThat(operations)
                .extracting(GatedOperation::type)
                .containsExactly(
                        "namespace.create",
                        "namespace.update",
                        "namespace.delete",
                        "route.create",
                        "route.delete",
                        "policy.set",
                        "policy.clear",
                        "access-rule.set",
                        "access-rule.clear");
        assertThat(operations).extracting(GatedOperation::paramsType).doesNotHaveDuplicates();
        assertThat(operations).allSatisfy(o -> {
            assertThat(o.paramsType().isRecord()).isTrue();
            assertThat(o.version()).isEqualTo(1);
            assertThat(o.mode()).isEqualTo(ExecutionMode.ON_APPROVAL);
        });
    }

    @Test
    void aDeletionIsDestructiveAndAnAccessChangeIsAccessControl() {
        assertThat(beans.namespaceDelete(changes, clusters).traits(new NamespaceDelete(CLUSTER, VHOST, Map.of(), ETAG)))
                .containsExactly(Trait.DESTRUCTIVE);
        assertThat(beans.routeDelete(changes, clusters).traits(new RouteDelete(CLUSTER, BINDING, Map.of(), ETAG)))
                .containsExactly(Trait.DESTRUCTIVE);
        assertThat(beans.accessRuleSet(changes, clusters)
                        .traits(new AccessRuleSet(CLUSTER, PERMISSION, Map.of(), ETAG)))
                .containsExactly(Trait.ACCESS_CONTROL);
        assertThat(beans.accessRuleClear(changes, clusters)
                        .traits(new AccessRuleClear(CLUSTER, PERMISSION, Map.of(), ETAG)))
                .containsExactlyInAnyOrder(Trait.ACCESS_CONTROL, Trait.DESTRUCTIVE);
        assertThat(beans.policySet(changes, clusters).traits(new PolicySet(CLUSTER, POLICY, Map.of(), ETAG)))
                .isEmpty();
        assertThat(beans.namespaceCreate(changes, clusters).traits(new NamespaceCreate(CLUSTER, VHOST, Map.of(), ETAG)))
                .isEmpty();
        assertThat(beans.namespaceUpdate(changes, clusters).traits(new NamespaceUpdate(CLUSTER, VHOST, Map.of(), ETAG)))
                .isEmpty();
        assertThat(beans.routeCreate(changes, clusters).traits(new RouteCreate(CLUSTER, BINDING, Map.of(), ETAG)))
                .isEmpty();
        assertThat(beans.policyClear(changes, clusters).traits(new PolicyClear(CLUSTER, POLICY, Map.of(), ETAG)))
                .containsExactly(Trait.DESTRUCTIVE);
    }

    @Test
    void anApproverReadsWhatIsDoneWhereAndWithWhich() {
        GatedOperation<NamespaceDelete> delete = beans.namespaceDelete(changes, clusters);
        NamespaceDelete params = new NamespaceDelete(CLUSTER, VHOST, Map.of(), ETAG);

        assertThat(delete.scope(params)).isEqualTo(OperationScope.cluster(CLUSTER, ENVIRONMENT));
        assertThat(delete.summary(params)).isEqualTo("Delete virtual host orders on prod-eu");
        assertThat(delete.display(params))
                .containsExactly(DisplayRow.of("Cluster", "prod-eu"), DisplayRow.of("Virtual host", "orders"));
        assertThat(delete.redactedPaths()).isEmpty();
    }

    @Test
    void theSettingsOfAPolicyAreRowsWithoutTheDriversPrefix() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(RabbitMqIds.PATTERN, "^orders");
        attributes.put(RabbitMqIds.PRIORITY, 3);

        List<DisplayRow> rows =
                beans.policySet(changes, clusters).display(new PolicySet(CLUSTER, POLICY, attributes, ETAG));

        assertThat(rows)
                .contains(
                        DisplayRow.of("Policy", "/ / ha"),
                        DisplayRow.of("pattern", "^orders"),
                        DisplayRow.of("priority", "3"));
    }

    @Test
    void theEstimateAndTheReplayAreTheChangesOwn() {
        GatedOperation<PolicyClear> clear = beans.policyClear(changes, clusters);
        PolicyClear params = new PolicyClear(CLUSTER, POLICY, Map.of(), ETAG);
        Effect effect = new Effect(1, "changes", ETAG, "Clear policy.");
        when(changes.estimate(params)).thenReturn(effect);

        assertThat(clear.estimate(params)).isSameAs(effect);
        clear.replay(params);

        verify(changes).replay(params);
        assertThat(Set.of(Trait.DESTRUCTIVE)).isEqualTo(clear.traits(params));
    }
}
