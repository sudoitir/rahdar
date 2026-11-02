package io.github.sudoitir.rahdar.platform.broker;

/**
 * A classified broker connection failure. The {@link Kind} maps to a stable
 * {@code type} URI in the API's {@code ProblemDetail} so the frontend can switch
 * on the class of failure rather than parse a message.
 */
public class BrokerConnectionException extends RuntimeException {

    public enum Kind {
        /**
         * Rahdar could not reach the broker: the name does not resolve, nothing is listening, the
         * connection or the answer timed out, or the connection broke. The message names which.
         */
        UNREACHABLE("Nothing answered at this address."),
        /** Rahdar held the call back itself, because it was already calling this node as fast as its rate ceiling allows. */
        THROTTLED("Rahdar is calling this node as fast as its rate ceiling allows."),
        /** The broker rejected an account's credentials: HTTP 401 or 403 from Jolokia, or a Core security exception. */
        CREDENTIALS_REJECTED("The broker rejected these credentials."),
        /** The broker knows the account but does not allow it this: a Kafka ACL or a RabbitMQ permission refused the call. */
        NOT_AUTHORIZED("The broker does not allow this account to do that."),
        /** A Jolokia agent answered, but no Artemis broker MBean is registered on it. */
        NOT_ARTEMIS("This is a Jolokia agent, but no Artemis broker is registered on it."),
        /** No Jolokia agent at this path (HTTP 404). */
        WRONG_PATH("No Jolokia agent here. The Artemis console usually serves it at /console/jolokia."),
        /** The TLS handshake to an HTTPS broker failed. */
        TLS_FAILED("The TLS connection to the broker could not be established."),
        /** The broker answered but the response was not valid Jolokia JSON. */
        BAD_RESPONSE("The broker answered, but not with a Jolokia response."),
        /** The broker runs an Artemis release older than the supported minimum (ADR-0142). */
        UNSUPPORTED_VERSION("This broker release is older than Rahdar supports.");

        private final String defaultMessage;

        Kind(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String defaultMessage() {
            return defaultMessage;
        }
    }

    private final Kind kind;
    private final BrokerAccount account;
    private final String system;

    public BrokerConnectionException(Kind kind, String message) {
        this(kind, message, null, null, null);
    }

    public BrokerConnectionException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null, null);
    }

    private BrokerConnectionException(
            Kind kind, String message, Throwable cause, BrokerAccount account, String system) {
        super(message, cause);
        this.kind = kind;
        this.account = account;
        this.system = system;
    }

    /** A failure of a broker of a named system, such as {@code Kafka}, so the operator is told which one. */
    public static BrokerConnectionException of(Kind kind, String message, Throwable cause, String system) {
        return new BrokerConnectionException(kind, message, cause, null, system);
    }

    /** A rejection of one account's credentials, which says which account it was. */
    public static BrokerConnectionException credentialsRejected(
            BrokerAccount account, String message, Throwable cause) {
        return credentialsRejected(account, message, cause, null);
    }

    /** A rejection of one account's credentials by a broker of a named system. */
    public static BrokerConnectionException credentialsRejected(
            BrokerAccount account, String message, Throwable cause, String system) {
        return new BrokerConnectionException(Kind.CREDENTIALS_REJECTED, message, cause, account, system);
    }

    public static BrokerConnectionException of(Kind kind) {
        return new BrokerConnectionException(kind, kind.defaultMessage());
    }

    public Kind kind() {
        return kind;
    }

    /** The rejected account of a {@link Kind#CREDENTIALS_REJECTED} failure; {@code null} for every other kind. */
    public BrokerAccount account() {
        return account;
    }

    /** The system the failing broker runs ({@code Kafka}, {@code RabbitMQ}, {@code Artemis}); {@code null} when unknown. */
    public String system() {
        return system;
    }
}
