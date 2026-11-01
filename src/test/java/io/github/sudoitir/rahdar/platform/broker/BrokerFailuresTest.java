package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every SDK reason lands on the one Rahdar kind an operator saw for it before. */
class BrokerFailuresTest {

    private static BrokerException failure(String reason, Map<String, String> metadata) {
        return new BrokerException(
                Status.UNKNOWN, ErrorInfo.of(reason, metadata), Dispatch.REJECTED, null, "message for " + reason);
    }

    static Stream<Arguments> connectionKinds() {
        return Stream.of(
                Arguments.of(ErrorReasons.UNREACHABLE, BrokerConnectionException.Kind.UNREACHABLE),
                Arguments.of(ErrorReasons.RATE_LIMITED, BrokerConnectionException.Kind.THROTTLED),
                Arguments.of(ErrorReasons.CREDENTIALS_REJECTED, BrokerConnectionException.Kind.CREDENTIALS_REJECTED),
                Arguments.of(ErrorReasons.UNEXPECTED_SYSTEM, BrokerConnectionException.Kind.NOT_ARTEMIS),
                Arguments.of(ErrorReasons.ENDPOINT_NOT_FOUND, BrokerConnectionException.Kind.WRONG_PATH),
                Arguments.of(ErrorReasons.TLS_HANDSHAKE_FAILED, BrokerConnectionException.Kind.TLS_FAILED),
                Arguments.of(ErrorReasons.UNSUPPORTED_VERSION, BrokerConnectionException.Kind.UNSUPPORTED_VERSION),
                Arguments.of(ErrorReasons.BAD_RESPONSE, BrokerConnectionException.Kind.BAD_RESPONSE),
                Arguments.of(ErrorReasons.RESPONSE_TOO_LARGE, BrokerConnectionException.Kind.BAD_RESPONSE),
                Arguments.of(ErrorReasons.CALL_REFUSED, BrokerConnectionException.Kind.UNREACHABLE));
    }

    @ParameterizedTest
    @MethodSource("connectionKinds")
    void aConnectionFailureKeepsItsKindAndTheSdkMessage(String reason, BrokerConnectionException.Kind kind) {
        RuntimeException translated = BrokerFailures.translate(failure(reason, Map.of()));

        assertThat(translated).isInstanceOf(BrokerConnectionException.class);
        assertThat(((BrokerConnectionException) translated).kind()).isEqualTo(kind);
        assertThat(translated).hasMessage("message for " + reason);
    }

    static Stream<Arguments> refusalKinds() {
        return Stream.of(
                Arguments.of(ErrorReasons.RESOURCE_GONE, Map.of(), ManagementRefusal.Kind.ALREADY),
                Arguments.of(ErrorReasons.RESOURCE_EXISTS, Map.of(), ManagementRefusal.Kind.ALREADY),
                Arguments.of(
                        ErrorReasons.RESOURCE_EXISTS,
                        Map.of("differing", "address,routing-type"),
                        ManagementRefusal.Kind.ARGUMENT),
                Arguments.of(ErrorReasons.HAS_CHILDREN, Map.of(), ManagementRefusal.Kind.BOUND_SUBSCRIPTIONS),
                Arguments.of(ErrorReasons.HAS_CONSUMERS, Map.of(), ManagementRefusal.Kind.HAS_CONSUMERS),
                Arguments.of(ErrorReasons.IMMUTABLE_FIELD, Map.of(), ManagementRefusal.Kind.ARGUMENT),
                Arguments.of(ErrorReasons.INVALID_FILTER, Map.of(), ManagementRefusal.Kind.ARGUMENT),
                Arguments.of(
                        ErrorReasons.INVALID_ARGUMENT,
                        Map.of("artemis.code", "AMQ229020"),
                        ManagementRefusal.Kind.ARGUMENT));
    }

    @ParameterizedTest
    @MethodSource("refusalKinds")
    void aRefusalKeepsItsKind(String reason, Map<String, String> metadata, ManagementRefusal.Kind kind) {
        RuntimeException translated = BrokerFailures.translate(failure(reason, metadata));

        assertThat(translated).isInstanceOf(ManagementRefusal.class);
        assertThat(((ManagementRefusal) translated).kind()).isEqualTo(kind);
    }

    @Test
    void aQueueNoLongerOnTheNodeSaysSoAndAsksForARefresh() {
        RuntimeException translated = BrokerFailures.translate(failure(
                ErrorReasons.RESOURCE_GONE,
                Map.of("artemis.code", "InstanceNotFoundException", "artemis.error", "javax.management.X")));

        assertThat(translated).hasMessageContaining("no longer on this node").hasMessageContaining("Refresh");
    }

    @Test
    void aMessageNoQueueReceivedIsAConflictThatSaysNothingWasStored() {
        assertThat(BrokerFailures.translate(failure(ErrorReasons.NOT_ROUTED, Map.of())))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("not-routed"));
        assertThat(BrokerFailures.notRouted("orders", "eu"))
                .hasMessage("No queue received the message: the exchange 'orders' has no binding that matches"
                        + " routing key 'eu'. Nothing was stored.");
    }

    @Test
    void aFullDestinationIsAConflict() {
        assertThat(BrokerFailures.translate(failure(ErrorReasons.DESTINATION_FULL, Map.of())))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("destination-full"));
    }

    @Test
    void aQueueWithConsumersNamesTheWayThrough() {
        assertThat(BrokerFailures.translate(failure(ErrorReasons.HAS_CONSUMERS, Map.of())))
                .hasMessageContaining("disconnectConsumers=true");
    }

    @Test
    void anArgumentTheBrokerDidNotRefuseIsTheCallersMistake() {
        assertThat(BrokerFailures.translate(failure(ErrorReasons.INVALID_ARGUMENT, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCallGuardsRefusalStaysAProgrammingError() {
        assertThat(BrokerFailures.translate(failure(ErrorReasons.CALL_REFUSED, Map.of(BrokerFailures.GUARD, "x"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectedCredentialsNameTheAccountByPlane() {
        BrokerConnectionException data = (BrokerConnectionException) BrokerFailures.translate(
                failure(ErrorReasons.CREDENTIALS_REJECTED, Map.of(ErrorReasons.PLANE, Plane.DATA.name())));
        BrokerConnectionException management = (BrokerConnectionException) BrokerFailures.translate(
                failure(ErrorReasons.CREDENTIALS_REJECTED, Map.of(ErrorReasons.PLANE, Plane.MANAGEMENT.name())));

        assertThat(data.account()).isEqualTo(BrokerAccount.CORE);
        assertThat(management.account()).isEqualTo(BrokerAccount.MANAGEMENT);
    }

    @Test
    void theBrokersOwnTextIsKeptForTheCaller() {
        RuntimeException translated =
                BrokerFailures.translate(failure(ErrorReasons.BAD_RESPONSE, Map.of("artemis.error", "AMQ1: no")));

        assertThat(BrokerFailures.answeredError(translated)).contains("AMQ1: no");
    }
}
