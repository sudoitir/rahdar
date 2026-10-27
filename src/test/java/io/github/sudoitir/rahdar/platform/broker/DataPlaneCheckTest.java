package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;

/**
 * The registration-time data-plane check against a real broker: whether the broker lets the account in
 * on each node, and whether it may subscribe to the broker's notifications.
 */
class DataPlaneCheckTest extends ArtemisIntegrationTest {

    /** A notification stream takes no call permits, so the interceptor is never asked. */
    private final DataPlaneCheck check = new DataPlaneCheck(new BrokerGateway(
            BrokerDrivers.load(),
            mock(NodeDirectory.class),
            mock(ConnectionSettingsSource.class),
            new BrokerTls(mock(SslBundles.class)),
            mock(RahdarCallInterceptor.class),
            new BrokerTimeouts(new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000)),
            BrokerTelemetry.none()));

    private static NodeEndpoint node(String core, String role, boolean active) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                "n",
                "n",
                null,
                core,
                role,
                "STARTED",
                active,
                null,
                1L,
                "2.44.0",
                null,
                null,
                Instant.now(),
                null,
                null,
                false,
                true);
    }

    private static CoreConnectionSettings account(String password) {
        return new CoreConnectionSettings(UUID.randomUUID(), BROKER_USER, password, null, true);
    }

    @Test
    void theRightAccountIsAcceptedAndMaySubscribe() {
        DataPlaneCheck.Result result = check.check(List.of(node(coreUrl(), "PRIMARY", true)), account(BROKER_PASSWORD));

        assertThat(result.accounts()).containsExactly(AccountResult.ACCEPTED);
        assertThat(result.verdict()).isInstanceOf(SubscriptionVerdict.Connected.class);
    }

    @Test
    void aWrongPasswordIsRejectedAndSaysSo() {
        DataPlaneCheck.Result result = check.check(List.of(node(coreUrl(), "PRIMARY", true)), account("wrong"));

        assertThat(result.accounts()).containsExactly(AccountResult.REJECTED);
        assertThat(result.verdict())
                .isInstanceOfSatisfying(
                        SubscriptionVerdict.Failed.class,
                        f -> assertThat(f.kind()).isEqualTo(SubscriptionVerdict.Kind.UNAUTHORIZED));
    }

    @Test
    void nothingListeningSaysNothingOfTheAccount() {
        DataPlaneCheck.Result result =
                check.check(List.of(node("tcp://127.0.0.1:1", "PRIMARY", true)), account(BROKER_PASSWORD));

        assertThat(result.accounts()).containsExactly(AccountResult.UNREACHABLE);
        assertThat(result.verdict()).isInstanceOf(SubscriptionVerdict.Failed.class);
    }

    @Test
    void aNodeWithNoDataPlaneAddressOrAPassiveBackupIsNotTried() {
        DataPlaneCheck.Result result = check.check(
                List.of(node(null, "PRIMARY", true), node(coreUrl(), "BACKUP", false)), account(BROKER_PASSWORD));

        assertThat(result.accounts()).containsExactly(AccountResult.NOT_TRIED, AccountResult.NOT_TRIED);
        assertThat(result.verdict()).isInstanceOf(SubscriptionVerdict.NotAttempted.class);
    }
}
