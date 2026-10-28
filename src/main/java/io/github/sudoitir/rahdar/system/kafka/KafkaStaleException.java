package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;

/** A Kafka preview that no longer matches the cluster. */
public class KafkaStaleException extends StalePreviewException {

    public KafkaStaleException(String message) {
        super(message);
    }
}
