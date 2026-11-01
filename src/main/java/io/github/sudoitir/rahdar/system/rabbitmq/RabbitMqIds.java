package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceId;
import java.util.Arrays;

/**
 * The kinds and attribute keys of the RabbitMQ driver, as the text the SDK's resources carry. Rahdar names them
 * here and nowhere else, and never imports a driver class.
 */
final class RabbitMqIds {

    static final String VHOST = "rabbitmq.vhost";
    static final String EXCHANGE = "rabbitmq.exchange";
    static final String CLASSIC_QUEUE = "rabbitmq.classic-queue";
    static final String QUORUM_QUEUE = "rabbitmq.quorum-queue";
    static final String STREAM = "rabbitmq.stream";
    static final String BINDING = "rabbitmq.binding";
    static final String EXCHANGE_BINDING = "rabbitmq.exchange-binding";
    static final String SHOVEL = "rabbitmq.shovel";
    static final String FEDERATION_LINK = "rabbitmq.federation-link";
    static final String POLICY = "rabbitmq.policy";
    static final String OPERATOR_POLICY = "rabbitmq.operator-policy";
    static final String PERMISSION = "rabbitmq.permission";
    static final String TOPIC_PERMISSION = "rabbitmq.topic-permission";
    static final String USER = "rabbitmq.user";

    static final String ARGUMENT_PREFIX = "rabbitmq.argument.";
    static final String DEFINITION_PREFIX = "rabbitmq.definition.";

    static final String DESCRIPTION = "rabbitmq.description";
    static final String TAGS = "rabbitmq.tags";
    static final String DEFAULT_QUEUE_TYPE = "rabbitmq.default_queue_type";
    static final String EXCHANGE_TYPE = "rabbitmq.exchange_type";
    static final String AUTO_DELETE = "rabbitmq.auto_delete";
    static final String INTERNAL = "rabbitmq.internal";
    static final String PATTERN = "rabbitmq.pattern";
    static final String APPLY_TO = "rabbitmq.apply_to";
    static final String PRIORITY = "rabbitmq.priority";
    static final String CONFIGURE = "rabbitmq.configure";
    static final String WRITE = "rabbitmq.write";
    static final String READ = "rabbitmq.read";

    /** The SDK's standard keys the namespace sets. */
    static final String DURABLE = "openbroker.durable";

    static final String FILTER = "openbroker.filter";

    /** The vhost and user selectors the driver lists by. */
    static final String VHOST_SELECTOR = "vhost";

    static final String USER_SELECTOR = "user";

    private RabbitMqIds() {}

    static KindId kind(String kind) {
        return KindId.of(kind);
    }

    static ResourceId id(String kind, String... segments) {
        return ResourceId.of(kind, segments);
    }

    /** The id the text names, refused unless it is of one of the kinds. */
    static ResourceId parse(String text, String... kinds) {
        ResourceId id;
        try {
            id = ResourceId.parse(text == null ? "" : text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a resource id: " + text, e);
        }
        if (!Arrays.asList(kinds).contains(id.kind().value())) {
            throw new IllegalArgumentException(id.kind().value() + " is not one of " + String.join(", ", kinds) + ".");
        }
        return id;
    }

    /** The kind of queue the type names: {@code classic}, {@code quorum} or {@code stream}. */
    static String queueKind(String type) {
        return switch (type == null ? "" : type) {
            case "classic" -> CLASSIC_QUEUE;
            case "quorum" -> QUORUM_QUEUE;
            case "stream" -> STREAM;
            default -> throw new IllegalArgumentException("A queue is classic, quorum or stream.");
        };
    }
}
