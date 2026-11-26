package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.rahdar.kernel.core.Problems;
import io.github.sudoitir.rahdar.system.kafka.SchemaUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A schema registry that cannot do what was asked, as a 409 that names the system and the registry's reason. */
@RestControllerAdvice
public class KafkaSchemaProblemAdvice {

    @ExceptionHandler(SchemaUnavailableException.class)
    public ProblemDetail onUnavailable(SchemaUnavailableException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.CONFLICT, "schema-unavailable", "The schema registry is unavailable", e.getMessage());
        problem.setProperty("system", e.system());
        return problem;
    }
}
