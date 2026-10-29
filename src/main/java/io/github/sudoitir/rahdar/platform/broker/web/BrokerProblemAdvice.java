package io.github.sudoitir.rahdar.platform.broker.web;

import io.github.sudoitir.rahdar.kernel.core.Problems;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException.Kind;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.InvalidResourceIdException;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.broker.UnsupportedSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Broker transport failures and the safety cap, as problem details. */
@RestControllerAdvice
class BrokerProblemAdvice {

    @ExceptionHandler(BrokerConnectionException.class)
    ProblemDetail onBrokerConnection(BrokerConnectionException e) {
        ProblemDetail problem =
                Problems.of(statusFor(e.kind()), "broker-" + kebab(e.kind()), titleFor(e.kind()), e.getMessage());
        problem.setProperty("brokerErrorKind", e.kind().name());
        if (e.account() != null) {
            problem.setProperty("account", e.account().name());
        }
        return problem;
    }

    /**
     * A management refusal that reached the controller rather than being folded
     * into a per-node outcome — an address delete blocked by bound queues, or an
     * argument the broker will not accept. 409 rather than 400: the request is
     * well-formed, the resource's current state is what refuses it.
     */
    @ExceptionHandler(ManagementRefusal.class)
    ProblemDetail onManagementRefusal(ManagementRefusal e) {
        HttpStatus status = e.kind() == ManagementRefusal.Kind.ARGUMENT ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        ProblemDetail problem =
                Problems.of(status, "management-refused", "The broker refused this operation", e.getMessage());
        problem.setProperty("refusalKind", e.kind().name());
        return problem;
    }

    @ExceptionHandler(UnsupportedSystemException.class)
    ProblemDetail onUnsupportedSystem(UnsupportedSystemException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "unsupported-system", "Unsupported system", e.getMessage());
    }

    @ExceptionHandler(InvalidResourceIdException.class)
    ProblemDetail onInvalidResourceId(InvalidResourceIdException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "invalid-resource-id", "Invalid resource id", e.getMessage());
    }

    @ExceptionHandler(BulkCapExceededException.class)
    ProblemDetail onBulkCap(BulkCapExceededException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.UNPROCESSABLE_ENTITY, "bulk-cap-exceeded", "Safety cap exceeded", e.getMessage());
        problem.setProperty("affectedCount", e.affectedCount());
        problem.setProperty("cap", e.cap());
        return problem;
    }

    private static HttpStatus statusFor(Kind kind) {
        return switch (kind) {
            case CREDENTIALS_REJECTED, NOT_ARTEMIS, WRONG_PATH, UNSUPPORTED_VERSION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNREACHABLE, TLS_FAILED, BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case THROTTLED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String titleFor(Kind kind) {
        return switch (kind) {
            case UNREACHABLE -> "Broker unreachable";
            case THROTTLED -> "Rahdar is throttling calls to this broker";
            case CREDENTIALS_REJECTED -> "Broker rejected the credentials";
            case NOT_ARTEMIS -> "No Artemis broker at this agent";
            case WRONG_PATH -> "No Jolokia agent at this address";
            case TLS_FAILED -> "TLS handshake failed";
            case BAD_RESPONSE -> "Unexpected broker response";
            case UNSUPPORTED_VERSION -> "Artemis version not supported";
        };
    }

    private static String kebab(Kind kind) {
        return kind.name().toLowerCase().replace('_', '-');
    }
}
