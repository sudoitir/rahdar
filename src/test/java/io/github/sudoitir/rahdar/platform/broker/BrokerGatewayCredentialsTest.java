package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The broker client reads a cluster's accounts on its own threads and retries only on a broker error. A
 * failed read of Rahdar's database is reported as the broker being unavailable, so the thread backs off
 * instead of ending.
 */
class BrokerGatewayCredentialsTest {

    private final UUID cluster = UUID.randomUUID();
    private final ConnectionSettingsSource settings = mock(ConnectionSettingsSource.class);

    @Test
    void aFailedReadIsReportedAsTheBrokerBeingUnavailable() {
        when(settings.coreSettings(cluster)).thenThrow(new DataAccessResourceFailureException("I/O error"));
        CredentialSource credentials = BrokerGateway.credentials(settings, cluster);

        assertThatThrownBy(() -> credentials.forPlane(Plane.DATA)).isInstanceOfSatisfying(BrokerException.class, e -> {
            assertThat(e.status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(e.dispatch()).isEqualTo(Dispatch.NOT_SENT);
            assertThat(e.getCause()).isInstanceOf(DataAccessResourceFailureException.class);
        });
    }

    @Test
    void theAccountIsReadForEachPlane() {
        CoreConnectionSettings core = mock(CoreConnectionSettings.class);
        when(core.hasCredentials()).thenReturn(true);
        when(core.username()).thenReturn("app");
        when(core.password()).thenReturn("pw");
        BrokerConnectionSettings management = mock(BrokerConnectionSettings.class);
        when(management.hasCredentials()).thenReturn(false);
        when(settings.coreSettings(cluster)).thenReturn(core);
        when(settings.jolokiaSettings(cluster)).thenReturn(management);
        CredentialSource credentials = BrokerGateway.credentials(settings, cluster);

        assertThat(credentials.forPlane(Plane.DATA)).isEqualTo(Credentials.usernamePassword("app", "pw"));
        assertThat(credentials.forPlane(Plane.MANAGEMENT)).isInstanceOf(Credentials.None.class);
    }
}
