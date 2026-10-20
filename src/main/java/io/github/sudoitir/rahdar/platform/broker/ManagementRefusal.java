package io.github.sudoitir.rahdar.platform.broker;

/**
 * A management operation the broker refused for a reason that is not a connection
 * failure. Telling these apart is what {@code managementWrite} honesty depends on
 * (ADR-0049 D5): a refusal is evidence about the <em>request</em>, which must never
 * disable the capability. Only a rejected credential is evidence the connection
 * cannot write, and that is a connection failure (see below), not a refusal.
 *
 * <p>The broker SDK's driver recognises the broker's error codes, and {@link BrokerFailures} maps
 * its reasons to these kinds. Authorization is <em>not</em> represented here: it arrives as HTTP
 * 401/403 and is classified as {@link BrokerConnectionException.Kind#CREDENTIALS_REJECTED}.
 */
public class ManagementRefusal extends RuntimeException {

    public enum Kind {
        /**
         * The cluster is already in the requested state — the queue exists, or the
         * thing being removed is already gone. A success, not a failure (D2).
         */
        ALREADY,
        /**
         * The broker rejected an argument: an invalid filter, or a field that cannot
         * change on a live queue. The operator's input is wrong, the connection is fine.
         */
        ARGUMENT,
        /** An address still has queues bound to it (D8). The refusal names them. */
        BOUND_QUEUES,
        /**
         * A queue has consumers attached, so the broker will not destroy it without being told
         * to disconnect them (ADR-0084 D6).
         */
        HAS_CONSUMERS
    }

    private final Kind kind;

    public ManagementRefusal(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
