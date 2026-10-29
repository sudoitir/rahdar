package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import java.util.Locale;
import java.util.function.Supplier;

/** The driver's refusals as the problems Rahdar answers, carrying the driver's own words (ADR-0216). */
final class KafkaFailures {

    private KafkaFailures() {}

    static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerException e) {
            throw translate(e);
        }
    }

    static RuntimeException translate(BrokerException e) {
        return switch (e.status()) {
            case FAILED_PRECONDITION ->
                ErrorReasons.PRECONDITION_FAILED.equals(e.reason())
                        ? new KafkaStaleException(e.getMessage())
                        : new ConflictException(
                                e.reason().toLowerCase(Locale.ROOT).replace('_', '-'), e.getMessage());
            case INVALID_ARGUMENT, OUT_OF_RANGE -> new IllegalArgumentException(e.getMessage(), e);
            case NOT_FOUND -> new NotFoundException(e.getMessage());
            default -> BrokerFailures.translate(e);
        };
    }
}
