package io.github.sudoitir.rahdar.platform.broker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A management operation the broker refused for a reason that is not a connection
 * failure. Telling these apart is what {@code managementWrite} honesty depends on
 * (ADR-0049 D5): a refusal is evidence about the <em>request</em>, which must never
 * disable the capability. Only a rejected credential is evidence the connection
 * cannot write, and that is a connection failure (see below), not a refusal.
 *
 * <p>The codes are the ones Artemis 2.44 actually returns, measured over Jolokia
 * during this change's groundwork and recorded in ADR-0049 / the change's
 * {@code design.md}. Authorization is <em>not</em> represented here: on the
 * console's Jolokia endpoint it arrives as HTTP 401/403 and is already classified
 * as {@link BrokerConnectionException.Kind#CREDENTIALS_REJECTED} by the client.
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

    // ---- Artemis error codes, measured on 2.44.0 -----------------------

    /** Queue already exists on the address. */
    static final String QUEUE_EXISTS = "AMQ229019";
    /** Queue does not exist. */
    static final String QUEUE_ABSENT = "AMQ229017";
    /** Address does not exist. */
    static final String ADDRESS_ABSENT = "AMQ229203";
    /** Address already exists. */
    static final String ADDRESS_EXISTS = "AMQ229204";
    /** Address still has bindings — the force-free delete refuses. */
    static final String ADDRESS_HAS_BINDINGS = "AMQ229205";
    /** Routing type cannot be changed on a live queue. */
    static final String ROUTING_TYPE_IMMUTABLE = "AMQ229211";
    /** A queue with consumers attached, destroyed without {@code removeConsumers} (§17 Q1). */
    static final String QUEUE_HAS_CONSUMERS = "AMQ229025";
    /** Invalid message filter. */
    static final String INVALID_FILTER = "AMQ229020";
    /** No binding for a divert — destroying one that is already gone (§15 M4). */
    static final String DIVERT_ABSENT = "AMQ229012";
    /** An address-setting JSON document the broker could not parse (§15 M1). */
    static final String SETTING_PARSE = "Error while parsing MetaData";

    /** The setting key the broker names in a parse error; the last one wins. */
    private static final Pattern SETTING_NAME = Pattern.compile("name='([^']*)'");

    /** An address-setting pair the broker refuses after parsing (§15 M1). */
    static final String PAGE_SIZE_VS_MAX = "pageSize has to be lower than maxSizeBytes";
    /**
     * The MBean is not registered: the queue or address named by the object name is no
     * longer on this node. Raised by the JMX layer rather than as an AMQ code, so it
     * arrives as a Java class name and has to be matched as one.
     */
    static final String MBEAN_ABSENT = "InstanceNotFoundException";

    /**
     * Turn a failed response into the right exception: a {@link ManagementRefusal} when
     * the broker explained itself with a code we know, and a connection-level
     * {@link BrokerConnectionException} otherwise. Authorization refusals never reach
     * here — the client raises {@code CREDENTIALS_REJECTED} from the HTTP status first, which is
     * what {@code managementWrite} keys off (ADR-0049 D5).
     */
    public static void require(JolokiaResponse res, String operation) {
        if (res.ok()) {
            return;
        }
        ManagementRefusal refusal = classify(res.error());
        if (refusal != null) {
            throw refusal;
        }
        throw new BrokerConnectionException(
                BrokerConnectionException.Kind.BAD_RESPONSE,
                operation + " failed: " + (res.error() != null ? res.error() : "status " + res.status()));
    }

    /**
     * Classify a failed Jolokia response, or return {@code null} when the error is
     * not one of the known management refusals and should be treated as a
     * connection-level failure instead.
     */
    static ManagementRefusal classify(String error) {
        if (error == null) {
            return null;
        }
        if (error.contains(QUEUE_ABSENT) || error.contains(ADDRESS_ABSENT)) {
            return new ManagementRefusal(Kind.ALREADY, "Already absent: " + error);
        }
        if (error.contains(MBEAN_ABSENT)) {
            return new ManagementRefusal(
                    Kind.ALREADY,
                    "The queue or address is no longer on this node — it was destroyed, or it has not been"
                            + " created here. Refresh the listing: " + error);
        }
        if (error.contains(QUEUE_EXISTS) || error.contains(ADDRESS_EXISTS)) {
            return new ManagementRefusal(Kind.ALREADY, "Already present: " + error);
        }
        if (error.contains(ADDRESS_HAS_BINDINGS)) {
            return new ManagementRefusal(Kind.BOUND_QUEUES, error);
        }
        if (error.contains(QUEUE_HAS_CONSUMERS)) {
            return new ManagementRefusal(
                    Kind.HAS_CONSUMERS,
                    "The queue has consumers attached, and the broker will not delete it while they are."
                            + " Stop the consumers, or tick \"Disconnect this queue's consumers\""
                            + " (disconnectConsumers=true over the API or MCP) to close them.");
        }
        if (error.contains(ROUTING_TYPE_IMMUTABLE)) {
            return new ManagementRefusal(
                    Kind.ARGUMENT,
                    "A queue's routing type cannot be changed on a live queue. " + "Recreate the queue instead.");
        }
        if (error.contains(INVALID_FILTER)) {
            return new ManagementRefusal(Kind.ARGUMENT, "Invalid message filter.");
        }
        if (error.contains(DIVERT_ABSENT)) {
            return new ManagementRefusal(Kind.ALREADY, "Already absent: " + error);
        }
        if (error.contains(SETTING_PARSE)) {
            return settingParseRefusal(error);
        }
        if (error.contains(PAGE_SIZE_VS_MAX)) {
            return new ManagementRefusal(
                    Kind.ARGUMENT, "page-size-bytes must be lower than max-size-bytes; the broker refused the pair.");
        }
        return null;
    }

    /** Names the last address-setting key the broker quoted, or says it could not tell which. */
    private static ManagementRefusal settingParseRefusal(String error) {
        Matcher name = SETTING_NAME.matcher(error);
        String field = null;
        while (name.find()) {
            field = name.group(1);
        }
        return new ManagementRefusal(
                Kind.ARGUMENT,
                "The broker could not parse the value of " + (field == null ? "an address-setting key" : field) + ".");
    }
}
