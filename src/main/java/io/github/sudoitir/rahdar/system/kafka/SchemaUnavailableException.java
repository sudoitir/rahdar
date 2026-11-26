package io.github.sudoitir.rahdar.system.kafka;

/**
 * The cluster's schema registry cannot do what was asked. Answered 409, with the registry system and its own reason.
 * The reason is the registry's sentence, never a credential.
 */
public class SchemaUnavailableException extends RuntimeException {

    private final String system;

    public SchemaUnavailableException(String system, String reason) {
        super(reason);
        this.system = system;
    }

    /** {@code confluent}, or the broker system when it has no registry at all. */
    public String system() {
        return system;
    }
}
