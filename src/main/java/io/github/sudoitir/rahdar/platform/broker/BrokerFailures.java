package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Plane;
import java.util.Map;

/**
 * The SDK's failures in Rahdar's terms: each standard reason maps to exactly one {@link
 * BrokerConnectionException.Kind} or {@link ManagementRefusal.Kind}, so the problem types and the
 * texts an operator sees are what they were. {@code BrokerFailuresTest} covers every reason.
 */
public final class BrokerFailures {

    /** Metadata key the call guard sets, so its refusal stays a programming error. */
    static final String GUARD = "rahdar.guard";

    /** The Artemis driver's native error text, present when the broker answered with an error. */
    public static final String NATIVE_ERROR = "artemis.error";

    private static final String NATIVE_CODE = "artemis.code";
    private static final String MBEAN_ABSENT = "InstanceNotFoundException";

    private BrokerFailures() {}

    /** The Rahdar exception for a failure. */
    public static RuntimeException translate(BrokerException e) {
        Map<String, String> metadata = e.info().metadata();
        String nativeError = metadata.getOrDefault(NATIVE_ERROR, "");
        return switch (e.reason()) {
            case ErrorReasons.UNREACHABLE -> connection(BrokerConnectionException.Kind.UNREACHABLE, e);
            case ErrorReasons.RATE_LIMITED -> connection(BrokerConnectionException.Kind.THROTTLED, e);
            case ErrorReasons.CREDENTIALS_REJECTED ->
                BrokerConnectionException.credentialsRejected(account(metadata), e.getMessage(), e);
            case ErrorReasons.UNEXPECTED_SYSTEM -> connection(BrokerConnectionException.Kind.NOT_ARTEMIS, e);
            case ErrorReasons.ENDPOINT_NOT_FOUND -> connection(BrokerConnectionException.Kind.WRONG_PATH, e);
            case ErrorReasons.TLS_HANDSHAKE_FAILED -> connection(BrokerConnectionException.Kind.TLS_FAILED, e);
            case ErrorReasons.UNSUPPORTED_VERSION -> connection(BrokerConnectionException.Kind.UNSUPPORTED_VERSION, e);
            case ErrorReasons.RESOURCE_GONE ->
                MBEAN_ABSENT.equals(metadata.get(NATIVE_CODE))
                        ? new ManagementRefusal(
                                ManagementRefusal.Kind.ALREADY,
                                "The queue or address is no longer on this node — it was destroyed, or it has not"
                                        + " been created here. Refresh the listing: " + nativeError)
                        : new ManagementRefusal(ManagementRefusal.Kind.ALREADY, e.getMessage());
            case ErrorReasons.RESOURCE_EXISTS -> new ManagementRefusal(ManagementRefusal.Kind.ALREADY, e.getMessage());
            case ErrorReasons.HAS_CHILDREN ->
                new ManagementRefusal(ManagementRefusal.Kind.BOUND_QUEUES, e.getMessage());
            case ErrorReasons.HAS_CONSUMERS ->
                new ManagementRefusal(
                        ManagementRefusal.Kind.HAS_CONSUMERS,
                        "The queue has consumers attached, and the broker will not delete it while they are."
                                + " Stop the consumers, or tick \"Disconnect this queue's consumers\""
                                + " (disconnectConsumers=true over the API or MCP) to close them.");
            case ErrorReasons.IMMUTABLE_FIELD, ErrorReasons.INVALID_FILTER ->
                new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, e.getMessage());
            case ErrorReasons.INVALID_ARGUMENT ->
                metadata.containsKey(NATIVE_CODE)
                        ? new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, e.getMessage())
                        : new IllegalArgumentException(e.getMessage(), e);
            case ErrorReasons.CALL_REFUSED ->
                metadata.containsKey(GUARD)
                        ? new IllegalStateException(e.getMessage(), e)
                        : connection(BrokerConnectionException.Kind.UNREACHABLE, e);
            default -> connection(BrokerConnectionException.Kind.BAD_RESPONSE, e);
        };
    }

    private static BrokerConnectionException connection(BrokerConnectionException.Kind kind, BrokerException e) {
        return new BrokerConnectionException(kind, e.getMessage(), e);
    }

    private static BrokerAccount account(Map<String, String> metadata) {
        return Plane.DATA.name().equals(metadata.get(ErrorReasons.PLANE))
                ? BrokerAccount.CORE
                : BrokerAccount.MANAGEMENT;
    }
}
