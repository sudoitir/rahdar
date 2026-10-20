package io.github.sudoitir.rahdar.platform.broker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Refuses a broker call made while a database transaction is active: the call would hold a database
 * connection for the whole network round trip (and for as long as the per-node rate limiter makes it wait).
 *
 * <p>Checked where a management request is admitted ({@link RahdarCallInterceptor}) and where a Core connection is built ({@code CoreConnectionFactory}). A pooled Core connection that is reused does not pass the second.
 * Switched on by {@code rahdar.broker.forbid-calls-in-transaction}: on in the tests, so a violation
 * fails the test that exercises it, and off in production, where a call inside a transaction is slow, not wrong.
 */
@Component
public class BrokerCallGuard {

    /** A guard that never refuses, for a client or factory built outside the application. */
    public static final BrokerCallGuard OFF = new BrokerCallGuard(false);

    private final boolean forbidden;

    BrokerCallGuard(@Value("${rahdar.broker.forbid-calls-in-transaction:false}") boolean forbid) {
        this.forbidden = forbid;
    }

    void check() {
        if (forbidden && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("broker call inside a database transaction");
        }
    }
}
