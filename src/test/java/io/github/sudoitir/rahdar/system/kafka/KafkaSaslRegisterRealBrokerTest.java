package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.KafkaCredentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ScramCredentialInfo;
import org.apache.kafka.clients.admin.ScramMechanism;
import org.apache.kafka.clients.admin.UserScramCredentialUpsertion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Kafka 4.4 cluster that asks for SASL SCRAM-SHA-512 is registered with an account whose secret is sealed and
 * never returned, and a wrong password fails the check as an authentication failure (ADR-0213). The account is
 * created through an administrator that signs in with SASL PLAIN, as an operator would with the cluster's tools.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaSaslRegisterRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");
    private static final String PLAIN_LOGIN = "org.apache.kafka.common.security.plain.PlainLoginModule";
    private static final String USER = "alice";
    private static final String SECRET = "alice-scram-secret";

    private static KafkaContainer kafka;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    JdbcClient jdbc;

    private final String run = "kafka-sasl-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void startKafka() throws Exception {
        kafka = new KafkaContainer(IMAGE)
                .withEnv(
                        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "BROKER:PLAINTEXT,PLAINTEXT:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN,SCRAM-SHA-512")
                .withEnv(
                        "KAFKA_LISTENER_NAME_PLAINTEXT_PLAIN_SASL_JAAS_CONFIG",
                        PLAIN_LOGIN + " required user_admin=\"admin-secret\";")
                .withEnv(
                        "KAFKA_LISTENER_NAME_PLAINTEXT_SCRAM___SHA___512_SASL_JAAS_CONFIG",
                        "org.apache.kafka.common.security.scram.ScramLoginModule required;")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx384m");
        kafka.start();
        try (Admin admin = Admin.create(Map.of(
                "bootstrap.servers",
                kafka.getBootstrapServers(),
                "security.protocol",
                "SASL_PLAINTEXT",
                "sasl.mechanism",
                "PLAIN",
                "sasl.jaas.config",
                PLAIN_LOGIN + " required username=\"admin\" password=\"admin-secret\";"))) {
            admin.alterUserScramCredentials(List.of(new UserScramCredentialUpsertion(
                            USER, new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512, 4096), SECRET)))
                    .all()
                    .get();
        }
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @AfterEach
    void removeTheClusters() {
        clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .forEach(c -> clusters.deleteById(c.getId()));
    }

    private RegisterClusterRequest request(String name, String password) {
        return new RegisterClusterRequest(
                KafkaModule.SYSTEM,
                run + "-" + name,
                null,
                null,
                false,
                new KafkaConnection(
                        KafkaModule.SYSTEM,
                        List.of(kafka.getBootstrapServers()),
                        "SASL_PLAINTEXT",
                        "SCRAM-SHA-512",
                        new KafkaCredentials(USER, password, null),
                        null,
                        null));
    }

    @Test
    void theAccountIsSealedAndNeverReturned() throws Exception {
        ConnectionCheck check = service.checkConnection(request("check", SECRET));
        assertThat(check.failure()).isNull();
        assertThat(check.toString()).doesNotContain(SECRET);

        ClusterDetail detail = ((Attempt.Ok<ClusterDetail>) service.register(request("sealed", SECRET))).value();

        UUID id = detail.id();
        byte[] sealed = jdbc.sql("SELECT sealed FROM broker_credential WHERE cluster_id = ? AND kind = 'DATA'")
                .param(id)
                .query(byte[].class)
                .single();
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain(SECRET);
        assertThat(detail.connection().kafka().username()).isEqualTo(USER);
        assertThat(detail.connection().kafka().secretStored()).isTrue();
        assertThat(JsonMapper.builder().build().writeValueAsString(service.get(id)))
                .doesNotContain(SECRET);
        assertThat(jdbc.sql("SELECT params::text FROM audit_event WHERE cluster_id = ? AND action = 'REGISTER_CLUSTER'")
                        .param(id)
                        .query(String.class)
                        .list())
                .isNotEmpty()
                .noneMatch(params -> params.contains(SECRET));
    }

    @Test
    void aWrongPasswordFailsTheCheckAsAuthentication() {
        ConnectionCheck check = service.checkConnection(request("wrong", "not-the-password"));

        assertThat(check.failure()).isNotNull();
        assertThat(check.failure().cause()).isEqualTo("AUTHENTICATION");
        assertThat(check.failure().next()).isNotBlank();
    }

    @Test
    void noPasswordReachesAResponseAFailureOrAnAuditRowOnRegisterCheckOrEdit() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        String wrong = "not-the-password-4711";
        KafkaConnectionUpdate sameAccount = new KafkaConnectionUpdate(
                KafkaModule.SYSTEM, null, null, null, new KafkaCredentials(USER, SECRET, null), null, null);
        KafkaConnectionUpdate wrongAccount = new KafkaConnectionUpdate(
                KafkaModule.SYSTEM, null, null, null, new KafkaCredentials(USER, wrong, null), null, null);

        // A failed check, and a failed registration, say what went wrong without the password that was sent.
        ConnectionCheck failed = service.checkConnection(request("leak-check", wrong));
        assertThat(json.writeValueAsString(failed)).doesNotContain(wrong).doesNotContain(SECRET);
        assertThat(request("leak-request", wrong).toString()).doesNotContain(wrong);
        Throwable refused = catchThrowable(() -> service.register(request("leak-register", wrong)));
        assertThat(String.valueOf(refused))
                .contains("rejected these credentials")
                .doesNotContain(wrong);

        ClusterDetail detail = ((Attempt.Ok<ClusterDetail>) service.register(request("leak", SECRET))).value();
        UUID id = detail.id();
        UpdateClusterRequest edit = new UpdateClusterRequest(null, null, null, wrongAccount);

        // The edit's dry run, a refused edit and an accepted one: none returns or stores the password in the clear.
        Throwable refusedDryRun = catchThrowable(() -> service.checkUpdate(id, edit));
        assertThat(String.valueOf(refusedDryRun)).doesNotContain(wrong);
        Throwable refusedEdit = catchThrowable(() -> service.updateConnection(id, edit));
        assertThat(String.valueOf(refusedEdit)).doesNotContain(wrong);
        assertThat(edit.toString()).doesNotContain(wrong);
        var accepted = service.updateConnection(id, new UpdateClusterRequest(null, null, null, sameAccount));
        assertThat(json.writeValueAsString(accepted)).doesNotContain(SECRET);
        assertThat(json.writeValueAsString(service.get(id)))
                .doesNotContain(SECRET)
                .doesNotContain(wrong);

        assertThat(jdbc.sql("SELECT row_to_json(a)::text FROM audit_event a WHERE a.cluster_id = ?")
                        .param(id)
                        .query(String.class)
                        .list())
                .isNotEmpty()
                .noneMatch(row -> row.contains(SECRET) || row.contains(wrong));
        assertThat(jdbc.sql("SELECT row_to_json(a)::text FROM audit_event a WHERE a.params::text LIKE ?")
                        .param("%" + run + "%")
                        .query(String.class)
                        .list())
                .noneMatch(row -> row.contains(SECRET) || row.contains(wrong));
    }
}
