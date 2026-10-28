package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;

/**
 * One page of a partition of a log: its records in offset order, and the offset to continue from, or {@code null}
 * when the page reached the end.
 */
public record LogPage(List<LogRecord> records, Long next) {

    /** A record as shown: the decoded message, whether it is a tombstone (no value), and its key as text. */
    public record LogRecord(BrowsedMessage message, boolean tombstone, String key) {}
}
