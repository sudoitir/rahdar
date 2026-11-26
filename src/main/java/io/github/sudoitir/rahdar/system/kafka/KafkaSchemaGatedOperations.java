package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaWriteParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Schema registration, compatibility and a topic binding (ADR-0179). Each is destructive, runs as the requester once
 * approved, and pins the state the operator previewed.
 */
@Configuration(proxyBeanMethods = false)
class KafkaSchemaGatedOperations {

    private static final String CLUSTER = "Cluster";

    private static final String TOPIC = "Topic";

    @Bean
    GatedOperation<SchemaWriteParams> schemaWrite(KafkaSchemaReviews reviews, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "schema.write",
                SchemaWriteParams.class,
                SchemaWriteParams::clusterId,
                p -> "Change schema " + p.action() + " on " + clusters.labelOf(p.clusterId()),
                p -> schemaRows(clusters, p),
                reviews::estimate,
                reviews::replay,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<BindingParams> schemaBinding(KafkaSchemaBindings bindings, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "schema.binding",
                BindingParams.class,
                BindingParams::clusterId,
                p -> "Bind " + p.topic() + " " + p.side() + " to " + p.serde() + " on "
                        + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(TOPIC, p.topic()),
                        DisplayRow.of("Side", p.side()),
                        DisplayRow.of("Serde", p.serde())),
                bindings::estimate,
                bindings::replay,
                clusters,
                Set.of());
    }

    static List<DisplayRow> schemaRows(ClusterEnvironmentIndex clusters, SchemaWriteParams p) {
        List<DisplayRow> rows = new ArrayList<>();
        rows.add(DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())));
        rows.add(DisplayRow.of("Action", p.action()));
        rows.add(DisplayRow.of("Subject", p.subject() == null ? "default" : p.subject()));
        if (p.level() != null) {
            rows.add(DisplayRow.of("Compatibility", p.level()));
        }
        if (p.permanent() != null) {
            rows.add(DisplayRow.of(
                    "Permanent",
                    Boolean.TRUE.equals(p.permanent())
                            ? "yes — the schemas cannot be restored"
                            : "no — hidden from the default list, still readable, not undoable from this screen"));
        }
        if (p.schema() != null) {
            String text = p.schema();
            rows.add(DisplayRow.of("Schema", text.length() <= 120 ? text : text.substring(0, 117) + "..."));
        }
        return rows;
    }
}
