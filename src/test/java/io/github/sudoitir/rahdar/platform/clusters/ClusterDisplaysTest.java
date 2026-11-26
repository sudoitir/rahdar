package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService.ClusterUpdateParams;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A schema registry credential is shown as the fact that one was given, never as the secret. */
class ClusterDisplaysTest {

    private static final String SECRET = "user:registry-secret-value";
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();

    @Test
    void aRegistrationNamesTheRegistryAndRedactsItsCredential() {
        EnvironmentService environments = mock(EnvironmentService.class);
        when(environments.labelOf(ENV)).thenReturn("prod");

        List<DisplayRow> rows = ClusterDisplays.register(
                environments,
                new RegisterClusterRequest(
                        "kafka",
                        "prod-eu",
                        null,
                        ENV,
                        false,
                        new KafkaConnection(
                                "kafka",
                                List.of("k:9092"),
                                "PLAINTEXT",
                                null,
                                null,
                                null,
                                null,
                                "confluent",
                                "https://registry.example",
                                SECRET)));
        List<DisplayRow> none = ClusterDisplays.register(
                environments,
                new RegisterClusterRequest(
                        "kafka",
                        "plain",
                        null,
                        ENV,
                        false,
                        new KafkaConnection(
                                "kafka", List.of("k:9092"), "PLAINTEXT", null, null, null, null, "none", null, null)));
        List<DisplayRow> noUrl = ClusterDisplays.register(
                environments,
                new RegisterClusterRequest(
                        "kafka",
                        "kind-only",
                        null,
                        ENV,
                        false,
                        new KafkaConnection(
                                "kafka",
                                List.of("k:9092"),
                                "PLAINTEXT",
                                null,
                                null,
                                null,
                                null,
                                "confluent",
                                null,
                                null)));

        assertThat(rows)
                .extracting(DisplayRow::label, DisplayRow::to)
                .contains(
                        tuple("Schema registry", "confluent"),
                        tuple("Schema registry URL", "https://registry.example"),
                        tuple("Schema registry credential", "(a new password)"));
        assertThat(rows).extracting(DisplayRow::to).doesNotContain(SECRET);
        assertThat(none).extracting(DisplayRow::label).doesNotContain("Schema registry");
        assertThat(noUrl)
                .extracting(DisplayRow::label, DisplayRow::to)
                .contains(tuple("Schema registry", "confluent"))
                .doesNotContain(tuple("Schema registry URL", "https://registry.example"))
                .doesNotContain(tuple("Schema registry credential", "(a new password)"));
    }

    @Test
    void anEditRedactsANewRegistryCredentialAndABlankOneIsNotANewOne() {
        ClusterEnvironmentIndex clusters = mock(ClusterEnvironmentIndex.class);
        EnvironmentService environments = mock(EnvironmentService.class);
        when(clusters.labelOf(CLUSTER)).thenReturn("prod-eu");

        List<DisplayRow> given = ClusterDisplays.update(clusters, environments, edit("confluent", "https://r", SECRET));
        List<DisplayRow> blank = ClusterDisplays.update(clusters, environments, edit("confluent", "https://r", "  "));

        assertThat(given)
                .extracting(DisplayRow::label, DisplayRow::to)
                .contains(
                        tuple("Schema registry", "confluent"),
                        tuple("Schema registry URL", "https://r"),
                        tuple("Schema registry credential", "(a new password)"));
        assertThat(given).extracting(DisplayRow::to).doesNotContain(SECRET);
        assertThat(blank).extracting(DisplayRow::label).doesNotContain("Schema registry credential");
    }

    private static ClusterUpdateParams edit(String kind, String url, String credentials) {
        return new ClusterUpdateParams(
                CLUSTER,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                new KafkaConnectionUpdate("kafka", null, null, null, null, null, null, kind, url, credentials),
                null);
    }
}
