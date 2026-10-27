package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.LinkEvent;
import io.github.sudoitir.openbroker.messaging.LinkListener;
import io.github.sudoitir.openbroker.messaging.LinkState;
import io.github.sudoitir.rahdar.kernel.core.RahdarHealth;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The broker SDK's links as Rahdar sees them: the state of every sender, receiver and processor the
 * SDK recovers, with the node serving it and why it last left {@code OPEN} (ADR-0211). The SDK does the
 * recovering; this only records it, as the {@code brokerLinks} health contributor (and so the
 * diagnostics bundle), as metrics and in the log.
 *
 * <p>A cause is the SDK's exception message, which never carries a secret.
 */
@Slf4j
@Component("brokerLinks")
public class BrokerLinks extends AbstractHealthIndicator {

    /** How many recent state changes diagnostics shows. */
    static final int MAX_RECENT = 100;

    /** One link, as last heard of. */
    public record Link(
            String id,
            String owner,
            UUID clusterId,
            String node,
            String servedBy,
            LinkState state,
            String cause,
            Instant at) {}

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>();
    private final Deque<Link> recent = new ArrayDeque<>();
    private final Map<LinkState, Counter> transitions = new EnumMap<>(LinkState.class);

    public BrokerLinks(MeterRegistry meters) {
        for (LinkState state : LinkState.values()) {
            String tag = state.name().toLowerCase(Locale.ROOT);
            transitions.put(
                    state,
                    Counter.builder("rahdar.broker.link.transitions")
                            .description("Broker link state changes, by the state entered")
                            .tag("state", tag)
                            .register(meters));
            if (state != LinkState.CLOSED) {
                Gauge.builder("rahdar.broker.links", links, l -> count(l, state))
                        .description("Broker links by state")
                        .tag("state", tag)
                        .register(meters);
            }
        }
    }

    private static double count(Map<String, Link> links, LinkState state) {
        return links.values().stream().filter(l -> l.state() == state).count();
    }

    /** The listener for one cluster's transport, which {@link BrokerGateway} watches it with. */
    LinkListener listener(UUID clusterId) {
        return event -> changed(clusterId, event);
    }

    /**
     * Names what a link is for, as diagnostics shows it: {@code plugin <id>/<key>}, {@code capture <name>}. A
     * processor's lanes are links of their own, which the driver names {@code <processor id>-<lane>}, so they
     * take their processor's name.
     */
    public void label(String linkId, String owner) {
        if (closedAlready(linkId)) {
            // Closed before its owner could name it: nothing will close it again, so nothing is kept.
            return;
        }
        owners.put(linkId, owner);
        links.replaceAll((id, l) -> ownerKey(id).equals(linkId)
                ? new Link(id, owner, l.clusterId(), l.node(), l.servedBy(), l.state(), l.cause(), l.at())
                : l);
    }

    /** Whether every link heard of under this id (the link itself, or a processor's lanes) has closed. */
    private boolean closedAlready(String linkId) {
        if (links.keySet().stream().anyMatch(id -> belongsTo(id, linkId))) {
            return false;
        }
        return recent().stream().anyMatch(l -> l.state() == LinkState.CLOSED && belongsTo(l.id(), linkId));
    }

    private static boolean belongsTo(String id, String linkId) {
        return id.equals(linkId)
                || (id.startsWith(linkId + "-")
                        && id.substring(linkId.length() + 1).chars().allMatch(Character::isDigit));
    }

    /** The labelled id a link belongs to: its own, or its processor's for a lane. */
    private String ownerKey(String linkId) {
        if (owners.containsKey(linkId)) {
            return linkId;
        }
        int dash = linkId.lastIndexOf('-');
        if (dash > 0
                && dash < linkId.length() - 1
                && linkId.substring(dash + 1).chars().allMatch(Character::isDigit)
                && owners.containsKey(linkId.substring(0, dash))) {
            return linkId.substring(0, dash);
        }
        return linkId;
    }

    /** How many links or processors are named now. */
    int named() {
        return owners.size();
    }

    /** The current links of a labelled owner: a receiver or sender itself, or a processor's lanes. */
    List<Link> of(String linkId) {
        return links.values().stream()
                .filter(l -> ownerKey(l.id()).equals(linkId))
                .toList();
    }

    void changed(UUID clusterId, LinkEvent event) {
        String key = ownerKey(event.link());
        String owner = owners.getOrDefault(key, "link");
        Link link = new Link(
                event.link(),
                owner,
                clusterId,
                event.node().value(),
                event.servedBy().map(NodeId::value).orElse(null),
                event.state(),
                event.cause().map(Throwable::getMessage).orElse(null),
                event.at());
        if (event.state() == LinkState.CLOSED) {
            links.remove(event.link());
            if (links.keySet().stream().noneMatch(id -> ownerKey(id).equals(key))) {
                owners.remove(key);
            }
        } else {
            links.put(event.link(), link);
        }
        synchronized (recent) {
            if (recent.size() == MAX_RECENT) {
                recent.removeLast();
            }
            recent.addFirst(link);
        }
        transitions.get(event.state()).increment();
        switch (event.state()) {
            case RECOVERING ->
                log.warn(
                        "Broker link {} ({}) on node {} is recovering: {}",
                        link.id(),
                        owner,
                        link.node(),
                        link.cause());
            case FAILED ->
                log.warn(
                        "Broker link {} ({}) on node {} failed and is given up: {}",
                        link.id(),
                        owner,
                        link.node(),
                        link.cause());
            case OPEN ->
                log.info(
                        "Broker link {} ({}) is open on node {}",
                        link.id(),
                        owner,
                        link.servedBy() == null ? link.node() : link.servedBy());
            case CLOSED -> log.debug("Broker link {} ({}) closed", link.id(), owner);
        }
    }

    /** The most recent state changes, newest first. */
    List<Link> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    List<Link> current() {
        return List.copyOf(links.values());
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<Link> current = current();
        List<Link> troubled =
                current.stream().filter(l -> l.state() != LinkState.OPEN).toList();
        Map<String, Long> byState = new LinkedHashMap<>();
        for (LinkState state : LinkState.values()) {
            if (state != LinkState.CLOSED) {
                byState.put(
                        state.name(),
                        current.stream().filter(l -> l.state() == state).count());
            }
        }
        builder.status(troubled.isEmpty() ? Status.UP : RahdarHealth.DEGRADED)
                .withDetail("links", byState)
                .withDetail("notOpen", troubled)
                .withDetail("recent", recent());
    }
}
