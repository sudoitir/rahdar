package io.github.sudoitir.rahdar.platform.broker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException.Kind;
import io.github.sudoitir.rahdar.platform.broker.NodeDirectory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/** A broker failure names the system of the cluster it happened on, even when the driver's error does not. */
class BrokerProblemAdviceTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static BrokerProblemAdvice advice(String system) {
        NodeDirectory directory = mock(NodeDirectory.class);
        when(directory.system(CLUSTER)).thenReturn(system);
        ObjectProvider<NodeDirectory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(directory);
        return new BrokerProblemAdvice(provider);
    }

    private static MockHttpServletRequest on(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    @Test
    void aFailureWithoutASystemTakesTheClusterOneFromThePath() {
        ProblemDetail problem = advice("kafka")
                .onBrokerConnection(
                        BrokerConnectionException.of(Kind.UNSUPPORTED_VERSION),
                        on("/api/v1/clusters/" + CLUSTER + "/queues"));
        assertThat(problem.getProperties()).containsEntry("system", "Kafka");
        assertThat(problem.getTitle()).doesNotContain("Artemis");
    }

    @Test
    void theDriversOwnSystemWins() {
        ProblemDetail problem = advice("kafka")
                .onBrokerConnection(
                        BrokerConnectionException.of(Kind.BAD_RESPONSE, "odd", null, "RabbitMQ"),
                        on("/api/v1/clusters/" + CLUSTER + "/queues"));
        assertThat(problem.getProperties()).containsEntry("system", "RabbitMQ");
    }

    @Test
    void aRequestOutsideAClusterNamesNoSystem() {
        ProblemDetail problem = advice("kafka")
                .onBrokerConnection(BrokerConnectionException.of(Kind.UNREACHABLE), on("/api/v1/clusters"));
        assertThat(problem.getProperties()).doesNotContainKey("system");
    }
}
