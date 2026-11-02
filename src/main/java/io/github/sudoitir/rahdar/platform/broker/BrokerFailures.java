package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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

    private static final String KAFKA_ERROR = "kafka.error";
    private static final String KAFKA_CODE = "kafka.code";
    private static final String RABBITMQ_ERROR = "rabbitmq.error";
    private static final String KAFKA = "Kafka";
    private static final String RABBITMQ = "RabbitMQ";
    private static final String ARTEMIS = "Artemis";
    private static final String UNSUPPORTED_REQUEST = "UnsupportedVersionException";

    /** The problem type of a request the broker's release is too old to answer, such as Kafka's LIST_TRANSACTIONS. */
    public static final String UNSUPPORTED_BY_RELEASE = "unsupported-by-release";

    private static final String NATIVE_CODE = "artemis.code";
    private static final String DIFFERING = "differing";
    private static final String MBEAN_ABSENT = "InstanceNotFoundException";

    /** The problem type of an operation the resource's kind does not have, such as purging a stream. */
    public static final String UNSUPPORTED_ON_KIND = "unsupported-on-kind";

    /** The problem type of a send no queue received. */
    public static final String NOT_ROUTED = "not-routed";

    private BrokerFailures() {}

    /** The refusal of a send the exchange could not route: it names the exchange and the request's routing key. */
    public static ConflictException notRouted(String exchange, String routingKey) {
        return new ConflictException(
                NOT_ROUTED,
                "No queue received the message: the exchange '" + exchange
                        + "' has no binding that matches routing key '" + routingKey + "'. Nothing was stored.");
    }

    /** The call's result, or the Rahdar exception for the failure it raised. */
    public static <T> T translated(Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerException e) {
            throw translate(e);
        }
    }

    /** The Rahdar exception for a failure. */
    public static RuntimeException translate(BrokerException e) {
        Map<String, String> metadata = e.info().metadata();
        String nativeError = metadata.getOrDefault(NATIVE_ERROR, "");
        String system = systemOf(e);
        return switch (e.reason()) {
            case ErrorReasons.UNREACHABLE -> connection(BrokerConnectionException.Kind.UNREACHABLE, e);
            case ErrorReasons.RATE_LIMITED -> connection(BrokerConnectionException.Kind.THROTTLED, e);
            case ErrorReasons.CREDENTIALS_REJECTED ->
                BrokerConnectionException.credentialsRejected(account(metadata), e.getMessage(), e, system);
            case ErrorReasons.NOT_AUTHORIZED -> connection(BrokerConnectionException.Kind.NOT_AUTHORIZED, e);
            // A RabbitMQ address that answers as something else is a wrong address; Kafka answered with an error
            // Rahdar does not map. Only an Artemis agent without a broker is "not Artemis".
            case ErrorReasons.UNEXPECTED_SYSTEM ->
                connection(
                        switch (system == null ? ARTEMIS : system) {
                            case RABBITMQ -> BrokerConnectionException.Kind.WRONG_PATH;
                            case KAFKA -> BrokerConnectionException.Kind.BAD_RESPONSE;
                            default -> BrokerConnectionException.Kind.NOT_ARTEMIS;
                        },
                        e);
            case ErrorReasons.ENDPOINT_NOT_FOUND -> connection(BrokerConnectionException.Kind.WRONG_PATH, e);
            case ErrorReasons.TLS_HANDSHAKE_FAILED -> connection(BrokerConnectionException.Kind.TLS_FAILED, e);
            // A Kafka request one broker's release does not know is a gap in that feature, not an unsupported cluster.
            case ErrorReasons.UNSUPPORTED_VERSION ->
                UNSUPPORTED_REQUEST.equals(metadata.get(KAFKA_CODE))
                        ? new ConflictException(
                                UNSUPPORTED_BY_RELEASE,
                                "This Kafka cluster's brokers are too old for this request ("
                                        + metadata.getOrDefault(KAFKA_ERROR, e.getMessage())
                                        + "). Upgrade the brokers to use it.")
                        : connection(BrokerConnectionException.Kind.UNSUPPORTED_VERSION, e);
            case ErrorReasons.RESOURCE_GONE ->
                MBEAN_ABSENT.equals(metadata.get(NATIVE_CODE))
                        ? new ManagementRefusal(
                                ManagementRefusal.Kind.ALREADY,
                                "The queue or address is no longer on this node — it was destroyed, or it has not"
                                        + " been created here. Refresh the listing: " + nativeError)
                        : new ManagementRefusal(ManagementRefusal.Kind.ALREADY, e.getMessage());
            case ErrorReasons.RESOURCE_EXISTS ->
                // The driver names the differing fields when what exists is not what was asked for.
                new ManagementRefusal(
                        metadata.containsKey(DIFFERING)
                                ? ManagementRefusal.Kind.ARGUMENT
                                : ManagementRefusal.Kind.ALREADY,
                        e.getMessage());
            case ErrorReasons.HAS_CHILDREN ->
                new ManagementRefusal(ManagementRefusal.Kind.BOUND_SUBSCRIPTIONS, e.getMessage());
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
            case ErrorReasons.NOT_ROUTED -> new ConflictException(NOT_ROUTED, e.getMessage());
            case ErrorReasons.DESTINATION_FULL -> new ConflictException("destination-full", e.getMessage());
            case ErrorReasons.NOT_SUPPORTED -> new ConflictException(UNSUPPORTED_ON_KIND, e.getMessage());
            case ErrorReasons.CALL_REFUSED ->
                metadata.containsKey(GUARD)
                        ? new IllegalStateException(e.getMessage(), e)
                        : connection(BrokerConnectionException.Kind.UNREACHABLE, e);
            default -> connection(BrokerConnectionException.Kind.BAD_RESPONSE, e);
        };
    }

    /** The broker's own error text, when a translated failure is an answer that carried an error. */
    public static Optional<String> answeredError(Throwable translated) {
        if (!(translated.getCause() instanceof BrokerException b)) {
            return Optional.empty();
        }
        Map<String, String> metadata = b.info().metadata();
        return Optional.ofNullable(metadata.get(NATIVE_ERROR))
                .or(() -> Optional.ofNullable(metadata.get(KAFKA_ERROR)))
                .or(() -> Optional.ofNullable(metadata.get(RABBITMQ_ERROR)));
    }

    private static BrokerConnectionException connection(BrokerConnectionException.Kind kind, BrokerException e) {
        return BrokerConnectionException.of(kind, e.getMessage(), e, systemOf(e));
    }

    /**
     * The system a failure came from, read from the driver's namespaced metadata ({@code kafka.*}), else
     * from the system it names in its own words; {@code null} when it says neither.
     */
    static String systemOf(BrokerException e) {
        for (String key : e.info().metadata().keySet()) {
            if (key.startsWith("kafka.")) {
                return KAFKA;
            }
            if (key.startsWith("rabbitmq.")) {
                return RABBITMQ;
            }
            if (key.startsWith("artemis.")) {
                return ARTEMIS;
            }
        }
        String message = String.valueOf(e.getMessage());
        if (message.contains(KAFKA)) {
            return KAFKA;
        }
        return message.contains(RABBITMQ) ? RABBITMQ : null;
    }

    private static BrokerAccount account(Map<String, String> metadata) {
        return Plane.DATA.name().equals(metadata.get(ErrorReasons.PLANE))
                ? BrokerAccount.CORE
                : BrokerAccount.MANAGEMENT;
    }
}
