package io.github.sudoitir.rahdar.system.rabbitmq;

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
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.Change;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceUpdate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicyClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.PolicySet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated RabbitMQ operations (ADR-0223): virtual-host, binding, policy and permission changes, and deleting a
 * shovel. Each runs as the requester once approved and pins the state the operator previewed, so one approved after
 * the resource changed is refused as stale. The approver reads that RabbitMQ does not tell a recreated resource
 * apart.
 */
@Configuration(proxyBeanMethods = false)
class RabbitMqGatedOperations {

    @Bean
    GatedOperation<NamespaceCreate> namespaceCreate(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.NAMESPACE_CREATE, NamespaceCreate.class, changes, clusters);
    }

    @Bean
    GatedOperation<NamespaceUpdate> namespaceUpdate(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.NAMESPACE_UPDATE, NamespaceUpdate.class, changes, clusters);
    }

    @Bean
    GatedOperation<NamespaceDelete> namespaceDelete(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.NAMESPACE_DELETE, NamespaceDelete.class, changes, clusters);
    }

    @Bean
    GatedOperation<RouteCreate> routeCreate(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.ROUTE_CREATE, RouteCreate.class, changes, clusters);
    }

    @Bean
    GatedOperation<RouteDelete> routeDelete(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.ROUTE_DELETE, RouteDelete.class, changes, clusters);
    }

    @Bean
    GatedOperation<PolicySet> policySet(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.POLICY_SET, PolicySet.class, changes, clusters);
    }

    @Bean
    GatedOperation<PolicyClear> policyClear(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.POLICY_CLEAR, PolicyClear.class, changes, clusters);
    }

    @Bean
    GatedOperation<AccessRuleSet> accessRuleSet(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.ACCESS_RULE_SET, AccessRuleSet.class, changes, clusters);
    }

    @Bean
    GatedOperation<AccessRuleClear> accessRuleClear(RabbitMqChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(Type.ACCESS_RULE_CLEAR, AccessRuleClear.class, changes, clusters);
    }

    /**
     * What the operations share: run again as the requester after approval, scoped to the cluster. A deletion is
     * destructive; a change of access is access control.
     */
    private record Operation<P extends Record & Change>(
            Type kind, Class<P> paramsType, RabbitMqChanges changes, ClusterEnvironmentIndex clusters)
            implements GatedOperation<P> {

        private static final int MAX_ROWS = 20;

        @Override
        public String type() {
            return kind.gateType();
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Set<Trait> traits(P params) {
            Set<Trait> traits = new HashSet<>();
            if (kind.destructive()) {
                traits.add(Trait.DESTRUCTIVE);
            }
            if (kind == Type.ACCESS_RULE_SET || kind == Type.ACCESS_RULE_CLEAR) {
                traits.add(Trait.ACCESS_CONTROL);
            }
            return traits;
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.ON_APPROVAL;
        }

        @Override
        public OperationScope scope(P params) {
            return OperationScope.cluster(params.clusterId(), clusters.environmentOf(params.clusterId()));
        }

        @Override
        public String summary(P params) {
            return RabbitMqChanges.summary(params).replaceFirst("\\.$", "") + " on "
                    + clusters.labelOf(params.clusterId());
        }

        @Override
        public List<DisplayRow> display(P params) {
            ResourceId id = ResourceId.parse(params.id());
            List<DisplayRow> rows = new ArrayList<>();
            rows.add(DisplayRow.of("Cluster", clusters.labelOf(params.clusterId())));
            rows.add(DisplayRow.of(
                    capitalised(RabbitMqChanges.noun(id)),
                    String.join(" / ", id.path().segments())));
            params.attributes().entrySet().stream()
                    .limit(MAX_ROWS)
                    .forEach(e -> rows.add(
                            DisplayRow.of(e.getKey().replaceFirst("^rabbitmq\\.", ""), String.valueOf(e.getValue()))));
            return rows;
        }

        @Override
        public Set<String> redactedPaths() {
            return Set.of();
        }

        @Override
        public Effect estimate(P params) {
            return changes.estimate(params);
        }

        @Override
        public void replay(P params) {
            changes.replay(params);
        }

        private static String capitalised(String text) {
            return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
        }
    }
}
