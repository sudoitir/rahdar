package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.KindLabel;
import io.github.sudoitir.openbroker.model.Resources;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A page of rows must not ask the broker for its kind catalogue once per row. */
class ResourceIdsKindCacheTest {

    @Test
    void labellingManyRowsAsksForTheKindsOnce() {
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        Kind kind = mock(Kind.class);
        KindId id = new KindId("kafka.group-topic");
        when(connection.resources()).thenReturn(resources);
        when(resources.kinds()).thenReturn(List.of(kind));
        when(kind.id()).thenReturn(id);
        when(kind.label()).thenReturn(new KindLabel("Group on topic", "Groups on topics"));

        assertThat(ResourceIds.label(connection, id).singular()).isEqualTo("Group on topic");
        assertThat(ResourceIds.label(connection, id).singular()).isEqualTo("Group on topic");
        assertThat(ResourceIds.label(connection, id).singular()).isEqualTo("Group on topic");

        verify(resources, times(1)).kinds();
    }
}
