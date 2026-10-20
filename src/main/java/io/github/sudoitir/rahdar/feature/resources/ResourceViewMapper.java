package io.github.sudoitir.rahdar.feature.resources;

import static io.github.sudoitir.openbroker.artemis.ArtemisLists.num;
import static io.github.sudoitir.openbroker.artemis.ArtemisLists.str;

import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.AddressView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConnectionView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConsumerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ProducerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SessionView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * One Jolokia {@code listX} row (all-strings JSON, Phase 0) → its typed view,
 * tagged with the logical node it was fetched from. Hand-written rather than
 * MapStruct because the source is a loosely-typed {@link JsonNode}, not a bean.
 */
@Component
public class ResourceViewMapper {

    private static final String PROTOCOL = "protocol";

    /** The logical node a fanned-out row belongs to. */
    public record NodeRef(UUID id, String name) {}

    public AddressView address(JsonNode row, NodeRef node) {
        return new AddressView(
                node.id(),
                node.name(),
                str(row, "name"),
                str(row, "routingTypes"),
                num(row, "queueCount"),
                num(row, "messageCount"),
                List.of(),
                null);
    }

    public ConsumerView consumer(JsonNode row, NodeRef node) {
        return new ConsumerView(
                node.id(),
                node.name(),
                str(row, "id"),
                str(row, "session"),
                str(row, "queue"),
                str(row, "address"),
                str(row, PROTOCOL),
                num(row, "messagesDelivered"),
                num(row, "messagesAcknowledged"),
                str(row, "status"),
                List.of());
    }

    public SessionView session(JsonNode row, NodeRef node) {
        return new SessionView(
                node.id(),
                node.name(),
                str(row, "id"),
                str(row, "user"),
                str(row, "connectionID"),
                num(row, "consumerCount"),
                num(row, "producerCount"),
                str(row, "creationTime"),
                List.of());
    }

    public ConnectionView connection(JsonNode row, NodeRef node) {
        return new ConnectionView(
                node.id(),
                node.name(),
                str(row, "connectionID"),
                str(row, "remoteAddress"),
                str(row, PROTOCOL),
                str(row, "clientID"),
                num(row, "sessionCount"),
                str(row, "creationTime"),
                List.of());
    }

    public ProducerView producer(JsonNode row, NodeRef node) {
        return new ProducerView(
                node.id(),
                node.name(),
                str(row, "id"),
                str(row, "name"),
                str(row, "session"),
                str(row, "address"),
                str(row, PROTOCOL),
                num(row, "msgSent"),
                List.of());
    }
}
