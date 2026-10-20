package io.github.sudoitir.rahdar.feature.resources;

import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.count;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.name;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.text;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.AttributeKey;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Closing connections, sessions and an address's consumers (ADR-0057), on one node each. The
 * pre-close read that identifies the target is its own call, never part of the close.
 *
 * <p><b>A missing target is not an error.</b> The broker reports nothing closed, and that
 * already-gone verdict is carried as a boolean all the way up (ADR-0057 D2).
 *
 * <p>Rows are read through the broker's own list filter rather than by fetching every row: a close is
 * aimed at exactly one connection, and asking for one row is the difference between a bounded call
 * and a full connection dump on a busy node.
 */
@Component
public class ConnectionOperations {

    /**
     * Everything the confirmation and the audit record need about a connection, read immediately
     * before it is closed (ADR-0057 D3). After the close none of it is resolvable.
     *
     * @param messagesInTransit in-flight messages that will return to their queues with an
     *     increased delivery count; {@code null} when it is not known, which is stated rather than
     *     shown as zero
     */
    public record ConnectionSnapshot(
            String connectionId,
            String clientId,
            String remoteAddress,
            String user,
            String protocol,
            long sessionCount,
            long consumerCount,
            Long messagesInTransit) {

        /**
         * What a human can recognise this connection by, and therefore what the confirmation is
         * against (D3). Never the connection id: an operator cannot tell from {@code a3f1-…} that
         * they have the right one.
         */
        public String label() {
            if (!blank(clientId)) {
                return clientId;
            }
            return blank(remoteAddress) ? connectionId : remoteAddress;
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    // ---- reads -----------------------------------------------------------

    /**
     * The pre-close snapshot of one connection, or {@code null} when the node no longer has it (the
     * already-gone verdict, D2). The connection and its sessions are read together, then every
     * session's consumers together; the second read is skipped when there are no sessions.
     */
    public ConnectionSnapshot readConnection(BrokerTarget node, String connectionId) {
        return translated(() -> {
            ClusterConnection c = node.connection();
            ResourceId id = ResourceIds.of(c, ResourceClass.CONNECTION, connectionId);
            List<ResourcePage> head = c.resources()
                    .read(List.of(
                            ResourceQuery.of(ResourceClass.CONNECTION)
                                    .named(connectionId)
                                    .in(node.scope()),
                            children(ResourceClass.CHANNEL, id).in(node.scope())))
                    .orThrow();
            Resource connection = first(head.get(0));
            if (connection == null) {
                return null;
            }
            List<Resource> sessions = head.get(1).items();
            long consumers = sessions.stream()
                    .mapToLong(s -> count(s, MetricKeys.CONSUMER_COUNT))
                    .sum();
            return new ConnectionSnapshot(
                    connectionId,
                    text(connection, StandardAttributes.CLIENT_ID),
                    text(connection, StandardAttributes.REMOTE_ADDRESS),
                    text(connection, StandardAttributes.PRINCIPAL),
                    text(connection, StandardAttributes.PROTOCOL),
                    // The connection's own count can disagree with the sessions listed; the listed ones
                    // are what the close takes with it, so they are what the operator is shown.
                    sessions.isEmpty() ? count(connection, MetricKeys.CHANNEL_COUNT) : sessions.size(),
                    consumers,
                    inTransit(node, sessions));
        });
    }

    /**
     * The pre-close snapshot of one session, shaped as a connection snapshot with a session count of
     * one so every kind of close reports the same thing. Its remote address comes from its
     * connection: a session row carries none, and without it a CORE client with no client id would
     * be confirmed against an opaque connection id, which ADR-0057 D3 forbids.
     */
    public ConnectionSnapshot readSession(BrokerTarget node, String sessionId) {
        return translated(() -> {
            ClusterConnection c = node.connection();
            Resource session = first(c.resources()
                    .read(ResourceQuery.of(ResourceClass.CHANNEL)
                            .named(sessionId)
                            .in(node.scope()))
                    .orThrow());
            if (session == null) {
                return null;
            }
            String connectionId = name(session, StandardAttributes.PARENT);
            if (connectionId == null) {
                return null;
            }
            List<ResourcePage> pages = c.resources()
                    .read(List.of(
                            ResourceQuery.of(ResourceClass.CONNECTION)
                                    .named(connectionId)
                                    .in(node.scope()),
                            children(ResourceClass.CONSUMER, session.id()).in(node.scope())))
                    .orThrow();
            Resource connection = first(pages.get(0));
            return new ConnectionSnapshot(
                    connectionId,
                    firstNonBlank(
                            text(session, StandardAttributes.CLIENT_ID),
                            textOf(connection, StandardAttributes.CLIENT_ID)),
                    textOf(connection, StandardAttributes.REMOTE_ADDRESS),
                    firstNonBlank(
                            text(session, StandardAttributes.PRINCIPAL),
                            textOf(connection, StandardAttributes.PRINCIPAL)),
                    textOf(connection, StandardAttributes.PROTOCOL),
                    1,
                    count(session, MetricKeys.CONSUMER_COUNT),
                    sum(pages.get(1).items()));
        });
    }

    /** The session a consumer belongs to, or {@code null} when the consumer is gone. */
    public String sessionOfConsumer(BrokerTarget node, String consumerId) {
        return parentOf(node, ResourceClass.CONSUMER, consumerId);
    }

    /** The connection a session belongs to, or {@code null} when the session is gone. */
    public String connectionOfSession(BrokerTarget node, String sessionId) {
        return parentOf(node, ResourceClass.CHANNEL, sessionId);
    }

    private static String parentOf(BrokerTarget node, ResourceClass resourceClass, String name) {
        return translated(() -> {
            Resource child = first(node.connection()
                    .resources()
                    .read(ResourceQuery.of(resourceClass).named(name).in(node.scope()))
                    .orThrow());
            return child == null ? null : name(child, StandardAttributes.PARENT);
        });
    }

    /**
     * How many consumers are attached to an address on this node: the address-scoped close's
     * per-node estimate, which the bulk cap is checked against (D6).
     */
    public long countConsumersForAddress(BrokerTarget node, String address) {
        return translated(() -> {
            ClusterConnection c = node.connection();
            Count estimate = c.operations()
                    .preview(ResourceIds.of(c, ResourceClass.DESTINATION, address), new Operation.Close(), node.scope())
                    .estimate()
                    .count();
            return switch (estimate) {
                case Count.Exact e -> e.value();
                case Count.Estimate e -> e.value();
                case Count.Unavailable u ->
                    throw new IllegalStateException("The broker did not count the consumers: " + u.reason());
            };
        });
    }

    // ---- closes ----------------------------------------------------------

    /**
     * Closes a connection, a session, or every consumer of an address on this node.
     *
     * @return true when something was closed; false when the node had nothing left to close
     */
    public boolean close(BrokerTarget node, ResourceId target) {
        return translated(() -> !node.connection()
                .operations()
                .execute(target, new Operation.Close(), node.scope())
                .orThrow()
                .alreadyInState());
    }

    // ---- helpers ---------------------------------------------------------

    /** The resources of a class whose parent is {@code parent}. */
    private static ResourceQuery children(ResourceClass resourceClass, ResourceId parent) {
        return ResourceQuery.of(resourceClass).where(Map.of(StandardAttributes.PARENT.name(), parent.toString()));
    }

    /** Messages in flight across a set of sessions, or {@code null} when there is nothing to ask about. */
    private static Long inTransit(BrokerTarget node, List<Resource> sessions) {
        if (sessions.isEmpty()) {
            return null;
        }
        List<ResourceQuery> queries = sessions.stream()
                .map(s -> children(ResourceClass.CONSUMER, s.id()).in(node.scope()))
                .toList();
        return node.connection().resources().read(queries).orThrow().stream()
                .mapToLong(page -> sum(page.items()))
                .sum();
    }

    private static long sum(List<Resource> consumers) {
        return consumers.stream().mapToLong(r -> count(r, MetricKeys.IN_FLIGHT)).sum();
    }

    private static Resource first(ResourcePage page) {
        return page.items().isEmpty() ? null : page.items().getFirst();
    }

    private static String textOf(Resource r, AttributeKey<String> key) {
        return r == null ? null : text(r, key);
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static <T> T translated(Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }
}
