package io.github.sudoitir.rahdar.platform.broker;

import java.util.Set;
import java.util.TreeSet;

/** A request names a broker system Rahdar has no driver for, or a connection shaped for another system. Mapped to HTTP 400. */
public class UnsupportedSystemException extends RuntimeException {

    private final Set<String> supported;

    public UnsupportedSystemException(String message, Set<String> supported) {
        super(message + " Supported systems: " + String.join(", ", new TreeSet<>(supported)) + ".");
        this.supported = Set.copyOf(supported);
    }

    public Set<String> supported() {
        return supported;
    }
}
