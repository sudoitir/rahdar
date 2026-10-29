package io.github.sudoitir.rahdar.kernel.core.web;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.HeavyLimitException;
import io.github.sudoitir.rahdar.kernel.core.HeavyTimeoutException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.Problems;
import io.github.sudoitir.rahdar.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The failure modes every module shares, as problem details ({@link Problems}).
 * A failure that belongs to one module — a broker refusal, a SQL dialect error, a
 * login throttle — is mapped by that module's own advice. Framework exceptions (a bad body, a wrong
 * method, an unknown route) go through {@link ResponseEntityExceptionHandler}; what nothing maps is
 * {@link UnexpectedFailureResolver}'s 500.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail onNotFound(NotFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "invalid-value", "Invalid value", e.getMessage());
    }

    @ExceptionHandler(HeavyLimitException.class)
    ResponseEntity<ProblemDetail> onHeavyLimit(HeavyLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(Problems.HEAVY_RETRY_AFTER_SECONDS))
                .body(Problems.heavyLimit(e));
    }

    @ExceptionHandler(HeavyTimeoutException.class)
    ProblemDetail onHeavyTimeout(HeavyTimeoutException e) {
        return Problems.heavyTimeout(e);
    }

    @ExceptionHandler(StalePreviewException.class)
    ProblemDetail onStale(StalePreviewException e) {
        return Problems.of(HttpStatus.PRECONDITION_FAILED, "stale-preview", "The preview is stale", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail onConflict(ConflictException e) {
        return Problems.of(HttpStatus.CONFLICT, e.slug(), "Conflict", e.getMessage());
    }

    /**
     * A last line of defence, not the intended path. Services check for a conflict
     * up front and throw {@link ConflictException}; this keeps any constraint that
     * slips through from reaching the client as a 500 with no usable body.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Unmapped constraint violation surfaced to the API", e);
        return Problems.of(
                HttpStatus.CONFLICT,
                "constraint-violation",
                "Conflict",
                "This conflicts with something that already exists.");
    }

    @ExceptionHandler(InvalidApiVersionException.class)
    ProblemDetail onInvalidApiVersion(InvalidApiVersionException e) {
        return Problems.of(
                HttpStatus.BAD_REQUEST,
                "invalid-api-version",
                "Unsupported API version",
                "API version '" + e.getVersion() + "' is not supported. Use v1.");
    }

    @ExceptionHandler(ResourceForbiddenException.class)
    ProblemDetail onResourceForbidden(ResourceForbiddenException e) {
        ProblemDetail problem =
                Problems.of(HttpStatus.FORBIDDEN, "resource-forbidden", "Access denied", e.getMessage());
        problem.setProperty("permission", e.permission());
        problem.setProperty("resource", Map.of("kind", e.kind(), "name", e.name()));
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail onAccessDenied(AccessDeniedException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "access-denied", "Access denied", e.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail onAuthentication(AuthenticationException e) {
        return Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem =
                Problems.of(HttpStatus.BAD_REQUEST, "validation", "Invalid request", "One or more fields are invalid.");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    /** Gives every framework problem a type from the same scheme, keyed by its status. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The base class passes a null body and reads the problem off the exception itself.
        ProblemDetail problem = null;
        if (body instanceof ProblemDetail p) {
            problem = p;
        } else if (e instanceof ErrorResponse er) {
            problem = er.getBody();
        }
        if (problem != null && !String.valueOf(problem.getType()).startsWith(Problems.TYPE_BASE)) {
            problem.setType(URI.create(Problems.TYPE_BASE + slugFor(status)));
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static String slugFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 415 -> "unsupported-media-type";
            default -> status.is4xxClientError() ? "bad-request" : "internal-error";
        };
    }
}
