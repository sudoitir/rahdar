package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;

/**
 * One page of a partition of a log: its records in offset order, and the offset to continue from, or {@code null}
 * when the page reached the end. A page cut short at the byte budget is {@code truncated} and its {@code next} is the
 * first record left out.
 */
public record LogPage(List<LogRecord> records, Long next, boolean truncated) {

    /** A page that was not cut short. */
    public LogPage(List<LogRecord> records, Long next) {
        this(records, next, false);
    }

    /**
     * A record as shown: the decoded message, whether it is a tombstone (no value), and its key, as text when it reads
     * as text and as base64 when it does not, which {@code keyEncoding} says; both are null for a record with no key.
     */
    public record LogRecord(BrowsedMessage message, boolean tombstone, String key, BodyEncoding keyEncoding) {

        /** A record whose key is text. */
        public LogRecord(BrowsedMessage message, boolean tombstone, String key) {
            this(message, tombstone, key, key == null ? null : BodyEncoding.TEXT);
        }
    }
}
