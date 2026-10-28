package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.rahdar.kernel.core.Problems;
import io.github.sudoitir.rahdar.system.kafka.KafkaStaleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A preview that no longer matches the cluster is {@code 412}, and the operator is offered a fresh one. */
@RestControllerAdvice
class KafkaProblemAdvice {

    @ExceptionHandler(KafkaStaleException.class)
    ProblemDetail onStale(KafkaStaleException e) {
        return Problems.of(HttpStatus.PRECONDITION_FAILED, "stale-preview", "The preview is stale", e.getMessage());
    }
}
