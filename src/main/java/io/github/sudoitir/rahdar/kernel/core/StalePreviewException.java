package io.github.sudoitir.rahdar.kernel.core;

/** What was previewed has changed since: answered {@code 412}, and the operator is offered a fresh preview. */
public class StalePreviewException extends RuntimeException {

    public StalePreviewException(String message) {
        super(message);
    }
}
