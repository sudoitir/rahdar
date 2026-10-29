package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The broker client reads a cluster's accounts on its own threads and retries only on a broker error. A
 * failed read of Rahdar's database is reported as the broker being unavailable, so the thread backs off
 * instead of ending.
 */
class BrokerGatewayCredentialsTest {

    @Test
    void aFailedReadIsReportedAsTheBrokerBeingUnavailable() {
        CredentialSource credentials = BrokerGateway.guarded(plane -> {
            throw new DataAccessResourceFailureException("I/O error");
        });

        assertThatThrownBy(() -> credentials.forPlane(Plane.DATA)).isInstanceOfSatisfying(BrokerException.class, e -> {
            assertThat(e.status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(e.dispatch()).isEqualTo(Dispatch.NOT_SENT);
            assertThat(e.getCause()).isInstanceOf(DataAccessResourceFailureException.class);
        });
    }

    @Test
    void anAccountIsPassedThroughForEachPlane() {
        CredentialSource credentials = BrokerGateway.guarded(
                plane -> plane == Plane.DATA ? Credentials.usernamePassword("app", "pw") : new Credentials.None());

        assertThat(credentials.forPlane(Plane.DATA)).isEqualTo(Credentials.usernamePassword("app", "pw"));
        assertThat(credentials.forPlane(Plane.MANAGEMENT)).isInstanceOf(Credentials.None.class);
    }

    @Test
    void aKafkaTokenReadFailureIsReportedTheSameWay() {
        CredentialSource credentials = BrokerGateway.guarded(plane -> {
            throw new DataAccessResourceFailureException("I/O error");
        });

        assertThatThrownBy(() -> credentials.forPlane(Plane.DATA))
                .isInstanceOfSatisfying(
                        BrokerException.class, e -> assertThat(e.status()).isEqualTo(Status.UNAVAILABLE));
    }
}
