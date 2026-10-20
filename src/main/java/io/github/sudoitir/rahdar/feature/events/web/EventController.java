package io.github.sudoitir.rahdar.feature.events.web;

import io.github.sudoitir.rahdar.feature.events.BrokerEventQuery;
import io.github.sudoitir.rahdar.feature.events.BrokerEventService;
import io.github.sudoitir.rahdar.feature.events.web.EventViews.BrokerEventPageView;
import io.github.sudoitir.rahdar.feature.events.web.EventViews.BrokerEventView;
import io.github.sudoitir.rahdar.kernel.core.Cursor;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The events screen's data source (ADR-0026, ADR-0028): the cluster's
 * {@code activemq.notifications} history, filtered by type / node / address /
 * time, newest first. The envelope carries the dropped-event count and the
 * oldest retained event so buffer overflow is visible.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/events")
@RequiredArgsConstructor
public class EventController {

    private final BrokerEventService events;

    @GetMapping
    public BrokerEventPageView list(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID nodeId,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String after) {
        ResourceQuery paging = ResourceQuery.ofPage(page, size);
        return events.page(
                clusterId,
                new BrokerEventQuery(
                        type, nodeId, destination, from, to, paging.page(), paging.size(), Cursor.decode(after)));
    }

    /** One event by its seq, for a shared link; 404 once retention has reaped it. */
    @GetMapping("/{seq}")
    public BrokerEventView get(@PathVariable UUID clusterId, @PathVariable long seq) {
        return events.get(clusterId, seq);
    }
}
