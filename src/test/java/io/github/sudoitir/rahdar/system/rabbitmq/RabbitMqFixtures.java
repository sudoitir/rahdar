package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Resources, connections and previews as the RabbitMQ driver answers them, for the namespace's unit tests. */
final class RabbitMqFixtures {

    static final String ETAG = "etag-1";

    private RabbitMqFixtures() {}

    static Resource resource(String id, Map<String, Object> attributes) {
        return new Resource(ResourceId.parse(id), Attributes.of(attributes), List.of(), List.of(), Instant.EPOCH);
    }

    static Resource resource(String id, Map<String, Object> attributes, List<Condition> conditions) {
        return new Resource(ResourceId.parse(id), Attributes.of(attributes), conditions, List.of(), Instant.EPOCH);
    }

    /** A connection whose listings are the answer to the query. */
    static ClusterConnection connection(Function<ResourceQuery, List<Resource>> answer) {
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        when(connection.resources()).thenReturn(resources);
        when(resources.read(any(ResourceQuery.class)))
                .thenAnswer(call -> Partial.complete(
                        new ResourcePage(answer.apply(call.getArgument(0)), Optional.empty()), List.of()));
        return connection;
    }

    static Preview preview(String etag, long messages, AttributeChange... diff) {
        return new Preview(
                List.of(diff),
                Set.of(),
                new Estimate(Count.estimate(messages), "message"),
                etag,
                EtagBasis.CONFIGURATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }

    static AttributeChange change(String key, Object before, Object after) {
        return new AttributeChange(key, Optional.ofNullable(before), Optional.ofNullable(after));
    }
}
