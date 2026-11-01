package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.platform.broker.IdentityBasis;
import io.github.sudoitir.rahdar.platform.broker.WholeClusterOperations;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Diff;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.EffectView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The preview and the run of one operation on a RabbitMQ resource, over the whole cluster (ADR-0223). The etag is the
 * driver's, and its basis is always the configuration: RabbitMQ does not tell a recreated resource apart.
 */
@Component
@RequiredArgsConstructor
class RabbitMqOps {

    /** What a change does to its resource. */
    enum Verb {
        CREATE,
        UPDATE,
        DELETE
    }

    private final WholeClusterOperations operations;

    static Operation operation(Verb verb, Map<String, Object> attributes) {
        Attributes given = Attributes.of(attributes == null ? Map.of() : attributes);
        return switch (verb) {
            case CREATE -> new Operation.Create(given);
            case UPDATE -> new Operation.Update(given);
            case DELETE -> new Operation.Delete(false, false);
        };
    }

    Preview preview(UUID clusterId, ResourceId id, Verb verb, Map<String, Object> attributes) {
        return RabbitMqFailures.call(() -> operations.preview(clusterId, id, operation(verb, attributes)));
    }

    OperationResult execute(UUID clusterId, ResourceId id, Verb verb, Map<String, Object> attributes, String etag) {
        return RabbitMqFailures.call(() -> operations.execute(clusterId, id, operation(verb, attributes), etag));
    }

    /** The preview as the API answers it: what it would do, the etag to send back and what the etag holds. */
    static ChangePreview view(Preview preview, Verb verb, String summary, List<String> lines) {
        Optional<Long> messages = verb == Verb.DELETE ? WholeClusterOperations.messages(preview) : Optional.empty();
        IdentityBasis basis = IdentityBasis.of(preview.basis());
        long count = messages.orElse((long) preview.diff().size());
        EffectView effect = new EffectView(
                count, messages.isPresent() ? "messages" : "changes", detail(summary, basis), diff(preview), lines);
        return new ChangePreview(effect, preview.etag(), basis.name());
    }

    /** The sentence for the approver: what is done, and what the etag does not tell apart. */
    static String detail(String summary, IdentityBasis basis) {
        return basis.note() == null ? summary : summary + " " + basis.note();
    }

    static List<Diff> diff(Preview preview) {
        return preview.diff().stream()
                .map(c -> new Diff(
                        c.key(),
                        c.before().map(String::valueOf).orElse(null),
                        c.after().map(String::valueOf).orElse(null)))
                .toList();
    }

    /** Fails with the stale problem unless the caller's etag is the preview's. */
    static void require(String given, String current) {
        if (given == null || given.isBlank()) {
            throw new IllegalArgumentException("Preview first, then send the preview's etag.");
        }
        if (!given.equals(current)) {
            throw new StalePreviewException("It changed since the preview. Preview again.");
        }
    }
}
