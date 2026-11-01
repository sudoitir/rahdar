package io.github.sudoitir.rahdar.platform.clusters.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RabbitMqCredentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Connection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.RabbitMqConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.UnknownConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.RabbitMqConnectionUpdate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The connection unions read and write the RabbitMQ variant, and print no secret. */
class ClusterRequestsJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aRabbitMqConnectionRoundTripsThroughTheUnion() {
        RabbitMqConnection connection = new RabbitMqConnection(
                "rabbitmq",
                List.of("https://rabbit-1:15671", "http://rabbit-2:15672/rmq"),
                List.of("amqps://rabbit-1:5671"),
                new RabbitMqCredentials("ops", "s3cret", null),
                "rabbit-tls",
                "/events",
                30);

        String json = mapper.writeValueAsString(connection);
        Connection read = mapper.readValue(json, Connection.class);

        assertThat(read).isEqualTo(connection);
    }

    @Test
    void aRegisterRequestCarriesTheRabbitMqVariant() {
        String json = """
                {"system":"rabbitmq","name":"prod","connection":{"system":"rabbitmq",
                 "managementUrls":["http://rabbit:15672"],"credentials":{"token":"tkn"}}}
                """;

        RegisterClusterRequest request = mapper.readValue(json, RegisterClusterRequest.class);

        RabbitMqConnection connection = (RabbitMqConnection) request.connection();
        assertThat(connection.managementUrls()).containsExactly("http://rabbit:15672");
        assertThat(connection.amqpEndpoints()).isNull();
        assertThat(connection.credentials().token()).isEqualTo("tkn");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void anEditCarriesTheRabbitMqVariant() {
        String json = """
                {"connection":{"system":"rabbitmq","eventVhost":"/audit","watchIntervalSeconds":5}}
                """;

        UpdateClusterRequest request = mapper.readValue(json, UpdateClusterRequest.class);

        RabbitMqConnectionUpdate update = (RabbitMqConnectionUpdate) request.connection();
        assertThat(update.eventVhost()).isEqualTo("/audit");
        assertThat(update.watchIntervalSeconds()).isEqualTo(5);
        assertThat(update.managementUrls()).isNull();
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void aSystemNoDriverServesReadsAsUnknown() {
        Connection read = mapper.readValue("{\"system\":\"nats\"}", Connection.class);

        assertThat(read).isInstanceOf(UnknownConnection.class);
    }

    @Test
    void toStringMasksThePasswordAndTheToken() {
        RabbitMqCredentials both = new RabbitMqCredentials("ops", "s3cret", "tkn-123");
        RabbitMqConnection connection =
                new RabbitMqConnection("rabbitmq", List.of("http://r:15672"), null, both, null, null, null);

        assertThat(both.toString()).doesNotContain("s3cret").doesNotContain("tkn-123");
        assertThat(connection.toString()).doesNotContain("s3cret").doesNotContain("tkn-123");
        assertThat(new RabbitMqCredentials("ops", null, null).toString()).contains("password=none");
    }

    @Test
    void aUrlWithAnAccountOrTheWrongSchemeIsRefused() {
        RabbitMqConnection account =
                new RabbitMqConnection("rabbitmq", List.of("http://ops:pw@r:15672"), null, null, null, null, null);
        RabbitMqConnection scheme = new RabbitMqConnection(
                "rabbitmq", List.of("http://r:15672"), List.of("http://r:5672"), null, null, null, null);
        RabbitMqConnection fine = new RabbitMqConnection(
                "rabbitmq", List.of("https://r:15671"), List.of("amqp://r:5672"), null, null, null, 1);
        RabbitMqConnection tooFast =
                new RabbitMqConnection("rabbitmq", List.of("http://r:15672"), null, null, null, null, 0);

        assertThat(validator.validate(account)).hasSize(1);
        assertThat(validator.validate(scheme)).hasSize(1);
        assertThat(validator.validate(fine)).isEmpty();
        assertThat(validator.validate(tooFast)).hasSize(1);
        assertThat(validator.validate(new RabbitMqConnectionUpdate(
                        "rabbitmq", List.of("ftp://r"), List.of("amqp://u@r:5672"), null, null, null, null)))
                .hasSize(2);
    }
}
