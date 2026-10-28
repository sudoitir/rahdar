package io.github.sudoitir.rahdar.system.kafka;

/** What was previewed has changed since: answered {@code 412}, and the operator is offered a fresh preview. */
public class KafkaStaleException extends RuntimeException {

    public KafkaStaleException(String message) {
        super(message);
    }
}
