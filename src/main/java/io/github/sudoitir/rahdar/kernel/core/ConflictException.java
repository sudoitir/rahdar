package io.github.sudoitir.rahdar.kernel.core;

/** A mutation that is individually well-formed but conflicts with existing state. Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    private final String slug;

    public ConflictException(String slug, String message) {
        super(message);
        this.slug = slug;
    }

    /** One the broker answered, keeping its failure so a caller can still read what the broker said. */
    public ConflictException(String slug, String message, Throwable cause) {
        super(message, cause);
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }
}
