package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.kafka.KafkaOptions;
import io.github.sudoitir.rahdar.kernel.core.Problems;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaProblemAdvice;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** A registry credential is absent from the connection detail, the problem body and the audit map. */
class KafkaSchemaCredentialTest {

    private static final String SECRET = "password=registry-secret-value";

    @Test
    void theProblemBodyDoesNotCarryTheCredential() {
        ProblemDetail problem = new KafkaSchemaProblemAdvice()
                .onUnavailable(new SchemaUnavailableException("confluent", "The registry refused " + SECRET));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getType().toString()).endsWith("schema-unavailable");
        assertThat(problem.getDetail()).doesNotContain("registry-secret-value");
        assertThat(problem.getProperties()).containsEntry("system", "confluent");
    }

    @Test
    void problemsOfRedactsACredential() {
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, "schema-unavailable", "Unavailable", SECRET);

        assertThat(problem.getDetail()).doesNotContain("registry-secret-value");
    }

    @Test
    void aRegistryUrlMustNotCarryAnAccount() {
        assertThatThrownBy(() -> KafkaRegistryConfig.require("confluent", "https://user:secret@registry.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain an account");
    }

    @Test
    void aConnectionDoesNotPrintItsRegistryCredential() {
        KafkaConnection connection = new KafkaConnection(
                "kafka", List.of("k:9092"), "PLAINTEXT", null, null, null, null, "confluent", "https://r", SECRET);

        assertThat(connection.toString()).doesNotContain("registry-secret-value");
        assertThat(connection.registryCredentials()).isEqualTo(SECRET);
        KafkaRegistrar registrar = new KafkaRegistrar(
                mock(BrokerGateway.class),
                BrokerDrivers.load(),
                mock(KafkaConnectionRepository.class),
                mock(ConnectionSettingsSource.class),
                host -> true,
                mock(KafkaRegistrySecrets.class));
        Map<String, Object> audited = registrar.auditParams(connection);

        assertThat(audited.toString()).doesNotContain("registry-secret-value");
        assertThat(audited).doesNotContainKey("registryCredentials");
    }

    @Test
    void anUnknownKindAMissingUrlAndANonHttpUrlAreRefused() {
        assertThat(KafkaRegistryConfig.kind(null)).isEqualTo(KafkaOptions.REGISTRY_NONE);
        assertThat(KafkaRegistryConfig.kind("  ")).isEqualTo(KafkaOptions.REGISTRY_NONE);
        assertThatThrownBy(() -> KafkaRegistryConfig.require("glue", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none, confluent");
        assertThatThrownBy(() -> KafkaRegistryConfig.require("confluent", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registryUrl is required");
        assertThatThrownBy(() -> KafkaRegistryConfig.require("confluent", "registry.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
        assertThatThrownBy(() -> KafkaRegistryConfig.require("confluent", "http://user:registry-secret-value@["))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain an account")
                .hasMessageNotContaining("registry-secret-value");
    }

    @Test
    void aNoneRegistryCopiesNoCredentialAndAConfluentOneCopiesBoth() {
        Map<String, String> none = new HashMap<>();
        Map<String, String> confluent = new HashMap<>();

        KafkaRegistryConfig.put(none, null, "https://r", SECRET);
        KafkaRegistryConfig.put(confluent, "confluent", "https://r", SECRET);

        assertThat(none).containsOnly(Map.entry(KafkaOptions.REGISTRY_KIND, KafkaOptions.REGISTRY_NONE));
        assertThat(none.toString()).doesNotContain("registry-secret-value");
        assertThat(confluent)
                .containsEntry(KafkaOptions.REGISTRY_URL, "https://r")
                .containsEntry(KafkaOptions.REGISTRY_CREDENTIALS, SECRET);
    }
}
