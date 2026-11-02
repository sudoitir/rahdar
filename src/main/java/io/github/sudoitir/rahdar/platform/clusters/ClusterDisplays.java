package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.AccountUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.ArtemisConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Connection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.RabbitMqConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.UnknownConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.RabbitMqConnectionUpdate;
import java.util.ArrayList;
import java.util.List;

/**
 * The rows the gate shows for a cluster registration or edit (ADR-0179): what changes, with a secret shown only as
 * the fact that a new one was given.
 */
final class ClusterDisplays {

    private static final String REDACTED_PASSWORD = "(a new password)";
    private static final String LABEL_CLUSTER = "Cluster";
    private static final String LABEL_ENVIRONMENT = "Environment";
    private static final String LABEL_CORE_ACCOUNT = "Core account";
    private static final String LABEL_ACCOUNT = "Account";
    private static final String LABEL_ACCOUNT_SECRET = "Account secret";
    private static final String LABEL_MANAGEMENT_ACCOUNT = "Management account";
    private static final String SEPARATOR = ", ";

    private ClusterDisplays() {}

    /** What the request reaches the cluster at: its seed URLs or its bootstrap servers. */
    static List<String> targets(RegisterClusterRequest p) {
        return switch (p.connection()) {
            case ArtemisConnection a -> a.seedUrls();
            case KafkaConnection k -> k.bootstrapServers();
            case RabbitMqConnection r -> r.managementUrls();
            case UnknownConnection _ -> List.of(p.system());
        };
    }

    static List<DisplayRow> register(EnvironmentService environments, RegisterClusterRequest p) {
        List<DisplayRow> rows = new ArrayList<>();
        rows.add(DisplayRow.of("Name", p.name()));
        rows.add(DisplayRow.of("System", p.system()));
        rows.add(DisplayRow.of(registerTargetLabel(p.connection()), String.join(SEPARATOR, targets(p))));
        rows.add(DisplayRow.of(LABEL_ENVIRONMENT, environments.labelOf(p.environmentId())));
        switch (p.connection()) {
            case ArtemisConnection a -> artemisRows(rows, a, p.adopts());
            case KafkaConnection k -> kafkaRows(rows, k);
            case RabbitMqConnection r -> rabbitMqRows(rows, r);
            case UnknownConnection _ -> {
                // An unknown system has no account or protocol rows to show.
            }
        }
        return rows;
    }

    private static void artemisRows(List<DisplayRow> rows, ArtemisConnection a, boolean adopts) {
        if (a.credentials() != null) {
            rows.add(DisplayRow.of(LABEL_MANAGEMENT_ACCOUNT, a.credentials().username()));
        }
        if (a.coreCredentials() != null) {
            rows.add(DisplayRow.of(LABEL_CORE_ACCOUNT, a.coreCredentials().username()));
        }
        rows.add(DisplayRow.of("Adopt the running configuration", adopts ? "yes" : "no"));
    }

    private static void kafkaRows(List<DisplayRow> rows, KafkaConnection k) {
        rows.add(DisplayRow.of("Security protocol", k.securityProtocol()));
        if (k.saslMechanism() != null) {
            rows.add(DisplayRow.of("SASL mechanism", k.saslMechanism()));
        }
        if (k.credentials() != null && k.credentials().username() != null) {
            rows.add(DisplayRow.of(LABEL_ACCOUNT, k.credentials().username()));
        }
    }

    private static void rabbitMqRows(List<DisplayRow> rows, RabbitMqConnection r) {
        if (r.amqpEndpoints() != null && !r.amqpEndpoints().isEmpty()) {
            rows.add(DisplayRow.of("AMQP endpoints", String.join(SEPARATOR, r.amqpEndpoints())));
        }
        if (r.credentials() != null && r.credentials().username() != null) {
            rows.add(DisplayRow.of(LABEL_ACCOUNT, r.credentials().username()));
        }
    }

    private static String registerTargetLabel(Connection connection) {
        return switch (connection) {
            case KafkaConnection _ -> "Bootstrap servers";
            case RabbitMqConnection _ -> "Management URLs";
            default -> "Seed URLs";
        };
    }

    static List<DisplayRow> update(
            ClusterEnvironmentIndex clusters, EnvironmentService environments, ClusterService.ClusterUpdateParams p) {
        List<DisplayRow> rows = new ArrayList<>();
        rows.add(DisplayRow.of(LABEL_CLUSTER, clusters.labelOf(p.clusterId())));
        addIfSet(rows, "Name", p.name());
        addIfSet(rows, "Description", p.description());
        if (p.seedUrls() != null) {
            rows.add(DisplayRow.of("Seed URLs", String.join(SEPARATOR, p.seedUrls())));
        }
        addIfSet(rows, "Management URL pattern", p.managementUrlPattern());
        addIfSet(rows, "TLS bundle", p.tlsBundle());
        if (p.connection() instanceof KafkaConnectionUpdate k) {
            kafkaUpdateRows(rows, k);
        }
        if (p.connection() instanceof RabbitMqConnectionUpdate r) {
            rabbitMqUpdateRows(rows, r);
        }
        if (p.environmentId() != null) {
            rows.add(DisplayRow.of(LABEL_ENVIRONMENT, environments.labelOf(p.environmentId())));
        }
        account(rows, LABEL_MANAGEMENT_ACCOUNT, p.management());
        if (p.clearCore()) {
            rows.add(new DisplayRow(LABEL_CORE_ACCOUNT, null, "cleared"));
        } else {
            account(rows, LABEL_CORE_ACCOUNT, p.core());
        }
        return rows;
    }

    private static void kafkaUpdateRows(List<DisplayRow> rows, KafkaConnectionUpdate k) {
        addIfSet(rows, "Bootstrap servers", joined(k.bootstrapServers()));
        addIfSet(rows, "Security protocol", k.securityProtocol());
        addIfSet(rows, "SASL mechanism", k.saslMechanism());
        if (k.credentials() != null) {
            addIfSet(rows, LABEL_ACCOUNT, k.credentials().username());
            rows.add(DisplayRow.of(LABEL_ACCOUNT_SECRET, REDACTED_PASSWORD));
        }
    }

    private static void rabbitMqUpdateRows(List<DisplayRow> rows, RabbitMqConnectionUpdate r) {
        addIfSet(rows, "Management URLs", joined(r.managementUrls()));
        addIfSet(rows, "AMQP endpoints", joined(r.amqpEndpoints()));
        if (r.credentials() != null) {
            addIfSet(rows, LABEL_ACCOUNT, r.credentials().username());
            rows.add(DisplayRow.of(LABEL_ACCOUNT_SECRET, REDACTED_PASSWORD));
        }
    }

    private static String joined(List<String> values) {
        return values == null ? null : String.join(SEPARATOR, values);
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

    private static void addIfSet(List<DisplayRow> rows, String label, String value) {
        if (value != null) {
            rows.add(DisplayRow.of(label, value));
        }
    }
}
