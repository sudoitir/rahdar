package io.github.sudoitir.rahdar.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.AttributeSchema;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A node for tests that mock what is done to it: its connection knows Artemis's kinds, and a listing of its
 * resources finds nothing.
 */
public final class Targets {

    private Targets() {}

    public static BrokerTarget target() {
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        when(connection.resources()).thenReturn(resources);
        when(resources.kinds())
                .thenReturn(List.of(
                        kind("artemis.queue", ResourceClass.SUBSCRIPTION),
                        kind("artemis.address", ResourceClass.DESTINATION),
                        kind("artemis.connection", ResourceClass.CONNECTION),
                        kind("artemis.session", ResourceClass.CHANNEL),
                        kind("artemis.consumer", ResourceClass.CONSUMER),
                        kind("artemis.producer", ResourceClass.PRODUCER)));
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(new Partial<>(new ResourcePage(List.of(), Optional.empty()), Map.of(), Map.of()));
        return new BrokerTarget(connection, NodeId.of("node"));
    }

    /** An Artemis resource id. */
    public static ResourceId id(String kind, String... path) {
        return new ResourceId(KindId.of(kind), ResourcePath.of(path));
    }

    private static Kind kind(String id, ResourceClass resourceClass) {
        return new Kind(
                KindId.of(id), resourceClass, Set.of(), Optional.empty(), new AttributeSchema(List.of()), Set.of());
    }
}
