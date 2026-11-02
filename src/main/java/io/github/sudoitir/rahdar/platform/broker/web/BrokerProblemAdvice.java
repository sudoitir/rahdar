package io.github.sudoitir.rahdar.platform.broker.web;

import io.github.sudoitir.rahdar.kernel.core.Problems;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException.Kind;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.InvalidResourceIdException;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.broker.NodeDirectory;
import io.github.sudoitir.rahdar.platform.broker.UnsupportedSystemException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Broker transport failures and the safety cap, as problem details. */
@RestControllerAdvice
@RequiredArgsConstructor
class BrokerProblemAdvice {

    private static final Pattern CLUSTER_PATH = Pattern.compile("/clusters/([0-9a-fA-F-]{36})(?:/|$)");

    private static final Map<String, String> SYSTEM_NAMES =
            Map.of("kafka", "Kafka", "rabbitmq", "RabbitMQ", "artemis", "Artemis");

    private final ObjectProvider<NodeDirectory> directory;

    @ExceptionHandler(BrokerConnectionException.class)
    ProblemDetail onBrokerConnection(BrokerConnectionException e, HttpServletRequest request) {
        String system = e.system() != null ? e.system() : systemOf(request);
        ProblemDetail problem = Problems.of(
                statusFor(e.kind()), "broker-" + kebab(e.kind()), titleFor(e.kind(), system), e.getMessage());
        problem.setProperty("brokerErrorKind", e.kind().name());
        if (system != null) {
            problem.setProperty("system", system);
        }
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
            case CREDENTIALS_REJECTED, NOT_AUTHORIZED, NOT_ARTEMIS, WRONG_PATH, UNSUPPORTED_VERSION ->
                HttpStatus.UNPROCESSABLE_ENTITY;
            case UNREACHABLE, TLS_FAILED, BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case THROTTLED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    /**
     * The system of the cluster the request is about, for a failure whose own words do not name it: a driver error
     * without native metadata would otherwise read as the generic, Artemis-worded failure on a Kafka or RabbitMQ
     * cluster. Null outside a cluster's path, or for a cluster that is not registered.
     */
    private String systemOf(HttpServletRequest request) {
        Matcher m = CLUSTER_PATH.matcher(request.getRequestURI());
        NodeDirectory nodes = directory.getIfAvailable();
        if (nodes == null || !m.find()) {
            return null;
        }
        try {
            String id = nodes.system(UUID.fromString(m.group(1)));
            return id == null ? null : SYSTEM_NAMES.getOrDefault(id, id);
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static String titleFor(Kind kind, String system) {
        String broker = system == null ? "Broker" : system;
        return switch (kind) {
            case UNREACHABLE -> broker + " unreachable";
            case THROTTLED -> "Rahdar is throttling calls to this broker";
            case CREDENTIALS_REJECTED -> broker + " rejected the credentials";
            case NOT_AUTHORIZED -> broker + " refused this account";
            case NOT_ARTEMIS -> "No Artemis broker at this agent";
            case WRONG_PATH ->
                system == null || "Artemis".equals(system)
                        ? "No Jolokia agent at this address"
                        : "No " + system + " management endpoint at this address";
            case TLS_FAILED -> "TLS handshake failed";
            case BAD_RESPONSE -> broker + " sent a response Rahdar cannot use";
            case UNSUPPORTED_VERSION -> broker + " version not supported";
        };
    }

    private static String kebab(Kind kind) {
        return kind.name().toLowerCase().replace('_', '-');
    }
}
