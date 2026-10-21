package io.github.sudoitir.rahdar.platform.broker;

/** A resource id that does not parse, from a client. Mapped to 400 {@code invalid-resource-id}. */
public class InvalidResourceIdException extends RuntimeException {

    public InvalidResourceIdException(String message) {
        super(message);
    }
}
