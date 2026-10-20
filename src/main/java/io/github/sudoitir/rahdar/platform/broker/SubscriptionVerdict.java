package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import java.time.Instant;

/**
 * The cached outcome of a cluster's notification streams, read by {@code CapabilityProbe} without
 * opening a connection (ADR-0026, D5).
 */
public sealed interface SubscriptionVerdict
        permits SubscriptionVerdict.Connected, SubscriptionVerdict.Failed, SubscriptionVerdict.NotAttempted {

    /** Why a stream is not established, as precisely as an operator can act on. */
    enum Kind {
        /** The node has no data-plane address to subscribe at. */
        NO_ENDPOINT,
        UNREACHABLE,
        /** The broker rejected the account's credentials. */
        UNAUTHORIZED,
        /** The account is known but may not subscribe to the broker's notifications. */
        PERMISSION_DENIED,
        TLS_FAILED,
        UNKNOWN
    }

    /** Subscribed on at least one node. */
    record Connected(int nodeCount, Instant since) implements SubscriptionVerdict {}

    /** Every attempt failed; {@code kind} carries the most actionable reason. */
    record Failed(Kind kind, String reason) implements SubscriptionVerdict {}

    /** No live node has been subscribed to yet — the first scrape cycle has not completed. */
    record NotAttempted() implements SubscriptionVerdict {}

    /** One node's stream: established since when, or its latest failure. */
    sealed interface NodeState {
        record Connected(Instant since) implements NodeState {}

        record Failed(Kind kind, String reason, Instant at) implements NodeState {}
    }

    default boolean isConnected() {
        return this instanceof Connected;
    }

    /** The kind of a stream's failure, from the reason the broker SDK gives it. */
    static Kind kindOf(BrokerException failure) {
        String reason = failure.reason() == null ? "" : failure.reason();
        return switch (reason) {
            case ErrorReasons.ENDPOINT_NOT_FOUND -> Kind.NO_ENDPOINT;
            case ErrorReasons.UNREACHABLE -> Kind.UNREACHABLE;
            case ErrorReasons.CREDENTIALS_REJECTED -> Kind.UNAUTHORIZED;
            case ErrorReasons.NOT_AUTHORIZED -> Kind.PERMISSION_DENIED;
            case ErrorReasons.TLS_HANDSHAKE_FAILED -> Kind.TLS_FAILED;
            default -> Kind.UNKNOWN;
        };
    }
}
