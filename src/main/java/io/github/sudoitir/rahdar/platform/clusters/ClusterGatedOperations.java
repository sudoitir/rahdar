package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService.ClusterDeleteParams;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService.ClusterUpdateParams;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService.NodeOverrideParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.ClusterAssignParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.EnvironmentCreateParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.EnvironmentDeleteParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.EnvironmentUpdateParams;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.AccountUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.ArtemisConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.UnknownConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.EnvironmentViews.EnvironmentRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated cluster and environment operations (ADR-0179): {@code environment.create/update/delete} and {@code
 * cluster.register/update/node-override/delete/assign-environment}. They change what Rahdar manages and who sees it, so each is
 * access control, and a delete is destructive too. The secrets in a registration or an edit are redacted for those who
 * read it. A cluster's state key is its id and creation time, so a cluster deleted and registered again is refused.
 */
@Configuration(proxyBeanMethods = false)
class ClusterGatedOperations {

    private static final String REDACTED_PASSWORD = "(a new password)";
    private static final String LABEL_CLUSTER = "Cluster";
    private static final String LABEL_ENVIRONMENT = "Environment";
    private static final String LABEL_CORE_ACCOUNT = "Core account";
    private static final String UNIT_CLUSTERS = "clusters";

    @Bean
    GatedOperation<EnvironmentCreateParams> environmentCreateOperation(EnvironmentService environments) {
        return new Operations.Base<>(
                "environment.create", EnvironmentCreateParams.class, Set.of(Trait.ACCESS_CONTROL)) {
            @Override
            public OperationScope scope(EnvironmentCreateParams p) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(EnvironmentCreateParams p) {
                return "Create environment " + p.name();
            }

            @Override
            public List<DisplayRow> display(EnvironmentCreateParams p) {
                return List.of(DisplayRow.of(LABEL_ENVIRONMENT, p.name()));
            }

            @Override
            public Effect estimate(EnvironmentCreateParams p) {
                return new Effect(1, "environments", "name:" + p.name(), null);
            }

            @Override
            public void replay(EnvironmentCreateParams p) {
                environments.create(new EnvironmentRequest(p.name(), p.colour(), p.sortOrder()));
            }
        };
    }

    @Bean
    GatedOperation<EnvironmentUpdateParams> environmentUpdateOperation(EnvironmentService environments) {
        return new Operations.Base<>(
                "environment.update", EnvironmentUpdateParams.class, Set.of(Trait.ACCESS_CONTROL)) {
            @Override
            public OperationScope scope(EnvironmentUpdateParams p) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(EnvironmentUpdateParams p) {
                return "Edit environment " + environments.labelOf(p.environmentId()) + " (now " + p.name() + ")";
            }

            @Override
            public List<DisplayRow> display(EnvironmentUpdateParams p) {
                return List.of(
                        new DisplayRow("Name", environments.labelOf(p.environmentId()), p.name()),
                        DisplayRow.of("Colour", p.colour()),
                        DisplayRow.of("Order", String.valueOf(p.sortOrder())));
            }

            @Override
            public Effect estimate(EnvironmentUpdateParams p) {
                return new Effect(1, "environments", p.environmentId().toString(), null);
            }

            @Override
            public void replay(EnvironmentUpdateParams p) {
                environments.update(p.environmentId(), new EnvironmentRequest(p.name(), p.colour(), p.sortOrder()));
            }
        };
    }

    @Bean
    GatedOperation<EnvironmentDeleteParams> environmentDeleteOperation(EnvironmentService environments) {
        return new Operations.Base<>(
                "environment.delete", EnvironmentDeleteParams.class, Set.of(Trait.ACCESS_CONTROL, Trait.DESTRUCTIVE)) {
            @Override
            public OperationScope scope(EnvironmentDeleteParams p) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(EnvironmentDeleteParams p) {
                return "Delete environment " + environments.labelOf(p.environmentId());
            }

            @Override
            public List<DisplayRow> display(EnvironmentDeleteParams p) {
                return List.of(DisplayRow.of(LABEL_ENVIRONMENT, environments.labelOf(p.environmentId())));
            }

            @Override
            public Effect estimate(EnvironmentDeleteParams p) {
                long members = environments.memberCount(p.environmentId());
                return new Effect(
                        members,
                        UNIT_CLUSTERS,
                        p.environmentId().toString(),
                        "Its clusters stay, without an environment, and every grant on it is removed.");
            }

            @Override
            public void replay(EnvironmentDeleteParams p) {
                environments.delete(p.environmentId());
            }
        };
    }

    @Bean
    GatedOperation<ClusterAssignParams> clusterAssignEnvironmentOperation(
            EnvironmentService environments, ClusterEnvironmentIndex clusters) {
        return new Operations.Base<>(
                "cluster.assign-environment", ClusterAssignParams.class, Set.of(Trait.ACCESS_CONTROL)) {
            @Override
            public OperationScope scope(ClusterAssignParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(ClusterAssignParams p) {
                return "Move cluster " + clusters.labelOf(p.clusterId()) + " to "
                        + environments.labelOf(p.environmentId());
            }

            @Override
            public List<DisplayRow> display(ClusterAssignParams p) {
                return List.of(
                        DisplayRow.of(LABEL_CLUSTER, clusters.labelOf(p.clusterId())),
                        new DisplayRow(
                                LABEL_ENVIRONMENT,
                                environments.labelOf(clusters.environmentOf(p.clusterId())),
                                environments.labelOf(p.environmentId())));
            }

            @Override
            public Effect estimate(ClusterAssignParams p) {
                return new Effect(1, UNIT_CLUSTERS, p.clusterId() + "|" + p.environmentId(), null);
            }

            @Override
            public void replay(ClusterAssignParams p) {
                environments.assignCluster(p.clusterId(), p.environmentId());
            }
        };
    }

    /** What the request reaches the cluster at: its seed URLs or its bootstrap servers. */
    private static List<String> targets(RegisterClusterRequest p) {
        return switch (p.connection()) {
            case ArtemisConnection a -> a.seedUrls();
            case KafkaConnection k -> k.bootstrapServers();
            case UnknownConnection _ -> List.of(p.system());
        };
    }

    @Bean
    GatedOperation<RegisterClusterRequest> clusterRegisterOperation(
            ClusterService service, EnvironmentService environments) {
        return new Operations.Base<>("cluster.register", RegisterClusterRequest.class, Set.of(Trait.ACCESS_CONTROL)) {
            @Override
            public OperationScope scope(RegisterClusterRequest p) {
                return new OperationScope(null, p.environmentId());
            }

            @Override
            public String summary(RegisterClusterRequest p) {
                return "Register cluster "
                        + (p.name() != null ? p.name() : targets(p).getFirst());
            }

            @Override
            public List<DisplayRow> display(RegisterClusterRequest p) {
                return registerDisplay(environments, p);
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of(
                        "/connection/credentials/password",
                        "/connection/credentials/token",
                        "/connection/coreCredentials/password");
            }

            @Override
            public Effect estimate(RegisterClusterRequest p) {
                return new Effect(
                        1,
                        UNIT_CLUSTERS,
                        "targets:"
                                + String.join(",", targets(p).stream().sorted().toList()),
                        null);
            }

            @Override
            public void replay(RegisterClusterRequest p) {
                if (service.register(p) instanceof Attempt.Failed<?>(var kind, var detail)) {
                    throw new BrokerConnectionException(kind, detail);
                }
            }
        };
    }

    @Bean
    GatedOperation<ClusterUpdateParams> clusterUpdateOperation(
            ClusterService service,
            ClusterDirectory directory,
            ClusterEnvironmentIndex clusters,
            EnvironmentService environments) {
        return new Operations.Base<>("cluster.update", ClusterUpdateParams.class, Set.of(Trait.ACCESS_CONTROL)) {
            @Override
            public OperationScope scope(ClusterUpdateParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(ClusterUpdateParams p) {
                return "Change the connection of cluster " + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(ClusterUpdateParams p) {
                return updateDisplay(clusters, environments, p);
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of(
                        "/management/password",
                        "/core/password",
                        "/connection/credentials/password",
                        "/connection/credentials/token");
            }

            @Override
            public Effect estimate(ClusterUpdateParams p) {
                return new Effect(1, UNIT_CLUSTERS, identity(directory, p.clusterId()), null);
            }

            @Override
            public void replay(ClusterUpdateParams p) {
                service.updateConnection(p.clusterId(), p.toRequest());
            }
        };
    }

    @Bean
    GatedOperation<NodeOverrideParams> clusterNodeOverrideOperation(
            ClusterService service, ClusterDirectory directory, ClusterEnvironmentIndex clusters) {
        return new Operations.Base<>("cluster.node-override", NodeOverrideParams.class, Set.of(Trait.ACCESS_CONTROL)) {
            @Override
            public OperationScope scope(NodeOverrideParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(NodeOverrideParams p) {
                return "Point Rahdar at node " + node(p).getName() + " of cluster " + clusters.labelOf(p.clusterId())
                        + " by hand";
            }

            @Override
            public List<DisplayRow> display(NodeOverrideParams p) {
                ClusterNode node = node(p);
                List<DisplayRow> rows = new ArrayList<>();
                rows.add(DisplayRow.of(LABEL_CLUSTER, clusters.labelOf(p.clusterId())));
                rows.add(DisplayRow.of("Node", node.getName()));
                if (p.managementUrl() != null) {
                    rows.add(new DisplayRow("Management URL", node.getManagementUrl(), p.managementUrl()));
                }
                if (p.dataUrl() != null) {
                    rows.add(new DisplayRow("Data URL", node.getDataUrl(), p.dataUrl()));
                }
                return rows;
            }

            /** The node and the URLs it has now, so a node pointed elsewhere meanwhile is refused. */
            @Override
            public Effect estimate(NodeOverrideParams p) {
                ClusterNode node = node(p);
                return new Effect(
                        1, "nodes", p.nodeId() + "|" + node.getManagementUrl() + "|" + node.getDataUrl(), null);
            }

            @Override
            public void replay(NodeOverrideParams p) {
                if (service.overrideNodeUrl(p.clusterId(), p.nodeId(), p.toRequest())
                        instanceof Attempt.Failed<?>(var kind, var detail)) {
                    throw new BrokerConnectionException(kind, detail);
                }
            }

            private ClusterNode node(NodeOverrideParams p) {
                return directory
                        .node(p.nodeId())
                        .filter(n -> n.getClusterId().equals(p.clusterId()))
                        .orElseThrow(() -> new NotFoundException("Node", p.nodeId()));
            }
        };
    }

    @Bean
    GatedOperation<ClusterDeleteParams> clusterDeleteOperation(
            ClusterDirectory directory, ClusterService service, ClusterEnvironmentIndex clusters) {
        return new Operations.Base<>(
                "cluster.delete", ClusterDeleteParams.class, Set.of(Trait.ACCESS_CONTROL, Trait.DESTRUCTIVE)) {
            @Override
            public OperationScope scope(ClusterDeleteParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(ClusterDeleteParams p) {
                return "Delete cluster " + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(ClusterDeleteParams p) {
                return List.of(DisplayRow.of(LABEL_CLUSTER, clusters.labelOf(p.clusterId())));
            }

            @Override
            public Effect estimate(ClusterDeleteParams p) {
                return new Effect(
                        directory.nodes(p.clusterId()).size(),
                        "nodes",
                        identity(directory, p.clusterId()),
                        "Rahdar stops managing the cluster and forgets its history; the brokers are not touched.");
            }

            @Override
            public void replay(ClusterDeleteParams p) {
                service.delete(p.clusterId());
            }
        };
    }

    private static List<DisplayRow> registerDisplay(EnvironmentService environments, RegisterClusterRequest p) {
        List<DisplayRow> rows = new ArrayList<>();
        rows.add(DisplayRow.of("Name", p.name()));
        rows.add(DisplayRow.of("System", p.system()));
        rows.add(DisplayRow.of(
                p.connection() instanceof KafkaConnection ? "Bootstrap servers" : "Seed URLs",
                String.join(", ", targets(p))));
        rows.add(DisplayRow.of(LABEL_ENVIRONMENT, environments.labelOf(p.environmentId())));
        switch (p.connection()) {
            case ArtemisConnection a -> {
                if (a.credentials() != null) {
                    rows.add(DisplayRow.of("Management account", a.credentials().username()));
                }
                if (a.coreCredentials() != null) {
                    rows.add(DisplayRow.of(LABEL_CORE_ACCOUNT, a.coreCredentials().username()));
                }
                rows.add(DisplayRow.of("Adopt the running configuration", p.adopts() ? "yes" : "no"));
            }
            case KafkaConnection k -> {
                rows.add(DisplayRow.of("Security protocol", k.securityProtocol()));
                if (k.saslMechanism() != null) {
                    rows.add(DisplayRow.of("SASL mechanism", k.saslMechanism()));
                }
                if (k.credentials() != null && k.credentials().username() != null) {
                    rows.add(DisplayRow.of("Account", k.credentials().username()));
                }
            }
            case UnknownConnection _ -> {
                // An unknown system has no account or protocol rows to show.
            }
        }
        return rows;
    }

    private static List<DisplayRow> updateDisplay(
            ClusterEnvironmentIndex clusters, EnvironmentService environments, ClusterUpdateParams p) {
        List<DisplayRow> rows = new ArrayList<>();
        rows.add(DisplayRow.of(LABEL_CLUSTER, clusters.labelOf(p.clusterId())));
        addIfSet(rows, "Name", p.name());
        addIfSet(rows, "Description", p.description());
        if (p.seedUrls() != null) {
            rows.add(DisplayRow.of("Seed URLs", String.join(", ", p.seedUrls())));
        }
        addIfSet(rows, "Management URL pattern", p.managementUrlPattern());
        addIfSet(rows, "TLS bundle", p.tlsBundle());
        if (p.connection() instanceof KafkaConnectionUpdate k) {
            addIfSet(
                    rows,
                    "Bootstrap servers",
                    k.bootstrapServers() == null ? null : String.join(", ", k.bootstrapServers()));
            addIfSet(rows, "Security protocol", k.securityProtocol());
            addIfSet(rows, "SASL mechanism", k.saslMechanism());
            if (k.credentials() != null) {
                addIfSet(rows, "Account", k.credentials().username());
                rows.add(DisplayRow.of("Account secret", REDACTED_PASSWORD));
            }
        }
        if (p.environmentId() != null) {
            rows.add(DisplayRow.of(LABEL_ENVIRONMENT, environments.labelOf(p.environmentId())));
        }
        account(rows, "Management account", p.management());
        if (p.clearCore()) {
            rows.add(new DisplayRow(LABEL_CORE_ACCOUNT, null, "cleared"));
        } else {
            account(rows, LABEL_CORE_ACCOUNT, p.core());
        }
        return rows;
    }

    private static void account(List<DisplayRow> rows, String label, AccountUpdate account) {
        if (account == null) {
            return;
        }
        rows.add(DisplayRow.of(label, account.username()));
        if (account.password() != null && !account.password().isEmpty()) {
            rows.add(DisplayRow.of(label + " password", REDACTED_PASSWORD));
        }
    }

    /** The cluster as the gate pins it: its id and when it was registered, so one registered again is a different one. */
    private static String identity(ClusterDirectory directory, UUID clusterId) {
        RegisteredCluster cluster =
                directory.cluster(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        return clusterId + "|" + cluster.getCreatedAt();
    }

    private static void addIfSet(List<DisplayRow> rows, String label, String value) {
        if (value != null) {
            rows.add(DisplayRow.of(label, value));
        }
    }

    /** What every operation here shares: one version, run when approved, and no secrets unless it says so. */
    private static final class Operations {

        private Operations() {}

        abstract static class Base<P extends Record> implements GatedOperation<P> {
            private final String type;
            private final Class<P> paramsType;
            private final Set<Trait> traits;

            Base(String type, Class<P> paramsType, Set<Trait> traits) {
                this.type = type;
                this.paramsType = paramsType;
                this.traits = traits;
            }

            @Override
            public String type() {
                return type;
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<P> paramsType() {
                return paramsType;
            }

            @Override
            public Set<Trait> traits(P params) {
                return traits;
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }
        }
    }
}
