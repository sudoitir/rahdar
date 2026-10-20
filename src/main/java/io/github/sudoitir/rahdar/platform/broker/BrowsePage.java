package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;

/**
 * One page of a queue and the broker's own count of it.
 *
 * @param total the broker's count, or null when it could not be had — never a guess
 * @param totalUnavailable why {@code total} is null; null whenever it is present
 */
public record BrowsePage(List<BrowsedMessage> messages, Long total, String totalUnavailable) {

    /** A page whose count the broker reported. */
    public BrowsePage(List<BrowsedMessage> messages, long total) {
        this(messages, total, null);
    }
}
