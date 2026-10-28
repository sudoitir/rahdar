package io.github.sudoitir.rahdar.platform.broker;

import java.util.Optional;

/**
 * Names a system keeps for itself, such as a Kafka cluster's internal topics. Plugin messaging refuses to register
 * on or send to one (ADR-0111), on top of the names Rahdar reserves for its own objects.
 */
public interface ReservedNames extends SystemExtension {

    /** Why the name is the system's own, in words, or empty when a plugin may use it. */
    Optional<String> reason(String name);
}
