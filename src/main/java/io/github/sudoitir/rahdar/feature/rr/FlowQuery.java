package io.github.sudoitir.rahdar.feature.rr;

import java.time.Instant;

/**
 * The filters and page of a request-reply flow list; a blank or null filter matches everything.
 *
 * @param state the flow state
 * @param destination the request destination
 * @param correlationId the correlation id
 * @param from earliest timestamp, inclusive
 * @param to latest timestamp, inclusive
 * @param page 1-based page number
 * @param size rows per page, at most 500
 */
public record FlowQuery(
        String state, String destination, String correlationId, Instant from, Instant to, int page, int size) {}
