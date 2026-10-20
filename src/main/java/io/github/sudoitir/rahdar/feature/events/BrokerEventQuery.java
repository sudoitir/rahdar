package io.github.sudoitir.rahdar.feature.events;

import io.github.sudoitir.rahdar.kernel.core.Cursor;
import java.time.Instant;
import java.util.UUID;

/**
 * The filters and page of a broker-event history read; a blank or null filter matches everything.
 *
 * @param type the notification type
 * @param nodeId the node it came from
 * @param destination the destination it concerns
 * @param from earliest timestamp, inclusive
 * @param to latest timestamp, inclusive
 * @param page 1-based page number
 * @param size rows per page, at most 500
 * @param after where the previous page ended, or null to page by number
 */
public record BrokerEventQuery(
        String type, UUID nodeId, String destination, Instant from, Instant to, int page, int size, Cursor after) {}
