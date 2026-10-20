package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.artemis.MessageBrowser;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "is this indexed message still on its queue?" against the live broker
 * (ADR-0059). The index can only ever say what it saw; this is the one operation
 * that asks the authority.
 *
 * <p>Artemis has no filter attribute for the internal message id, so the read is a
 * page filtered on the message's own enqueue timestamp — cheap on a queue of any
 * size, and usually a handful of messages. If the id is not in that page and the
 * page came back full, the answer is <em>unknown</em>, not "gone": more messages
 * share that millisecond than one read returns, and reporting a message as consumed
 * because a bounded read did not contain it is exactly the false certainty this
 * feature exists to remove.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageVerifier {

    private final ClusterDirectory nodes;
    private final QueueSnapshots snapshots;
    private final ClusterAccessGuard clusterAccess;
    private final SqlConsoleService console;

    public enum Presence {
        /** The broker still holds it. */
        PRESENT,
        /** The broker was read and it is not there — consumed, moved, or expired. */
        GONE,
        /** The read could not settle the question, and says why rather than guessing. */
        UNKNOWN
    }

    public record Verdict(Presence presence, String detail) {}

    @Transactional(readOnly = true)
    public Verdict verify(UUID clusterId, UUID nodeId, String queueName, long messageId, long timestamp) {
        clusterAccess.requireResource(clusterId, ResourceRef.queue(queueName), MessagePermissions.MESSAGE_READ);

        Optional<ClusterNode> node = nodes.nodes(clusterId).stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst();
        if (node.isEmpty()) {
            return new Verdict(Presence.UNKNOWN, "That node is no longer part of this cluster.");
        }
        Optional<QueueSnapshot> queue = snapshots.forNode(nodeId).stream()
                .filter(s -> s.queueName().equals(queueName))
                .findFirst();
        if (queue.isEmpty()) {
            return new Verdict(
                    Presence.UNKNOWN,
                    "The queue " + queueName + " is not on " + node.get().getName()
                            + " any more, so the message cannot be looked for.");
        }

        TransportTarget target = new TransportTarget(
                clusterId,
                nodeId,
                queueName,
                queue.get().address(),
                queue.get().routingType(),
                node.get().getJolokiaUrl(),
                node.get().getCoreUrl());
        // Enqueue time is the one selector-visible attribute that narrows a queue to
        // a handful of messages without knowing anything else about this one.
        String selector = timestamp > 0 ? "AMQTimestamp = " + timestamp : null;

        MessageTransport transport = console.transportFor(clusterId);
        try {
            var result = transport.browse(target, 1, MessageBrowser.BROKER_PAGE_CAP, selector);
            boolean found = result.page().messages().stream().anyMatch(m -> m.messageId() == messageId);
            if (found) {
                return new Verdict(Presence.PRESENT, "Read from " + node.get().getName() + " just now.");
            }
            if (result.page().messages().size() >= MessageBrowser.BROKER_PAGE_CAP) {
                return new Verdict(
                        Presence.UNKNOWN,
                        "More messages share this message's enqueue time than one read returns, so its"
                                + " absence from that read proves nothing.");
            }
            return new Verdict(
                    Presence.GONE,
                    "Not on " + queueName + " on " + node.get().getName()
                            + " any more — consumed, moved, or expired since it was indexed.");
        } catch (RuntimeException e) {
            log.debug("Verify of message {} on {} failed", messageId, queueName, e);
            return new Verdict(
                    Presence.UNKNOWN, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
