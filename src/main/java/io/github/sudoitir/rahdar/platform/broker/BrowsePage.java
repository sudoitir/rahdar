package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;

/**
 * One page of a queue and the broker's own count of it.
 *
 * @param total the broker's count, or null when it could not be had — never a guess
 * @param totalUnavailable why {@code total} is null; null whenever it is present
 * @param truncated the page was cut short at the byte budget, so messages the broker returned are not in it
 */
public record BrowsePage(List<BrowsedMessage> messages, Long total, String totalUnavailable, boolean truncated) {

    /** A page whose count the broker reported. */
    public BrowsePage(List<BrowsedMessage> messages, long total) {
        this(messages, total, null, false);
    }

    /** A page that is whole, with or without the broker's count. */
    public BrowsePage(List<BrowsedMessage> messages, Long total, String totalUnavailable) {
        this(messages, total, totalUnavailable, false);
    }
}
