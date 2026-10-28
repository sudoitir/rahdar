package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An ACL preview is computed over every ACL of the cluster, however many pages that takes to read. */
class KafkaAclsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static Resource acl(int n) {
        return new Resource(
                ResourceId.of("kafka.acl", "User:u" + n, "*", "read", "allow", "topic", "literal", "t" + n),
                Attributes.empty(),
                List.of(),
                List.of(),
                Instant.now());
    }

    @Test
    void everyPageOfTheClustersAclsIsReadBeforeAPreviewIsComputed() {
        Resources resources = mock(Resources.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(acl(1), acl(2)), Optional.of("p2")), List.of()))
                .thenReturn(Partial.complete(new ResourcePage(List.of(acl(3)), Optional.empty()), List.of()));
        KafkaAcls acls = new KafkaAcls(gateway, mock(ClusterAccessGuard.class));

        List<Acl> all = acls.all(CLUSTER);

        verify(resources, times(2)).read(any(ResourceQuery.class));
        assertThat(all).extracting(Acl::name).containsExactly("t1", "t2", "t3");
    }
}
