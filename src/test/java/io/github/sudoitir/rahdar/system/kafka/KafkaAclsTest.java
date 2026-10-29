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
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclChangeRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** An ACL preview reads the ACLs that decide the access it shows, however many pages that takes, and no more. */
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
    void everyPageOfTheScopeIsReadBeforeAPreviewIsComputed() {
        Resources resources = mock(Resources.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(acl(1), acl(2)), Optional.of("p2")), List.of()))
                .thenReturn(Partial.complete(new ResourcePage(List.of(acl(3)), Optional.empty()), List.of()));
        KafkaAcls acls = new KafkaAcls(gateway, mock(ClusterAccessGuard.class));

        List<Acl> all = acls.held(CLUSTER, List.of(new Acl("User:u1", "*", "read", "allow", "topic", "literal", "t1")))
                .acls();

        verify(resources, times(3)).read(any(ResourceQuery.class));
        assertThat(all).extracting(Acl::name).containsExactly("t1", "t2", "t3");
    }

    private static Resource rule(String principal, String operation, String permission, String pattern, String name) {
        return new Resource(
                ResourceId.of("kafka.acl", principal, "*", operation, permission, "topic", pattern, name),
                Attributes.empty(),
                List.of(),
                List.of(),
                Instant.now());
    }

    @Test
    void aPrefixedDenyListsTheGrantLostOnEveryResourceItCoversNotOnlyOnThePrefix() {
        Resources resources = mock(Resources.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(
                        new ResourcePage(
                                List.of(
                                        rule("User:app", "read", "allow", "literal", "orders-eu"),
                                        rule("User:app", "read", "allow", "literal", "orders-us"),
                                        rule("User:app", "read", "allow", "literal", "billing")),
                                Optional.empty()),
                        List.of()));
        KafkaAcls acls = new KafkaAcls(gateway, mock(ClusterAccessGuard.class));

        var preview = acls.preview(
                CLUSTER,
                new AclChangeRequest(
                        List.of(new Acl("User:app", "*", "read", "deny", "topic", "prefixed", "orders-")), null, null));

        assertThat(preview.losses())
                .containsExactlyInAnyOrder(
                        "User:app loses READ on topic orders-eu", "User:app loses READ on topic orders-us");
        assertThat(preview.access()).extracting(a -> a.resource()).contains("orders-", "orders-eu", "orders-us");
        assertThat(preview.access()).extracting(a -> a.resource()).doesNotContain("billing");
    }

    @Test
    void aHundredThousandAclsAreNotReadForARuleOnOnePrincipalAndOneTopic() {
        List<Resource> every =
                IntStream.range(0, 100_000).mapToObj(KafkaAclsTest::acl).toList();
        AtomicInteger returned = new AtomicInteger();
        Resources resources = mock(Resources.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(resources.read(any(ResourceQuery.class))).thenAnswer(call -> {
            ResourceQuery query = call.getArgument(0);
            String principal = query.fieldSelector().get("principal");
            List<Resource> matching = every.stream()
                    .filter(r ->
                            principal == null || r.id().path().segments().get(0).equals(principal))
                    .limit(query.limit())
                    .toList();
            returned.addAndGet(matching.size());
            return Partial.complete(new ResourcePage(matching, Optional.empty()), List.of());
        });
        KafkaAcls acls = new KafkaAcls(gateway, mock(ClusterAccessGuard.class));

        var preview = acls.preview(
                CLUSTER,
                new AclChangeRequest(
                        List.of(new Acl("User:u7", "*", "write", "allow", "topic", "literal", "t7")), null, null));

        assertThat(returned.get()).isLessThanOrEqualTo(2);
        assertThat(preview.added()).isEqualTo(1);
    }

    @Test
    void aPrefixedRuleReadsTheTypeUpToTheCapAndSaysItWasCut() {
        Resources resources = mock(Resources.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        AtomicInteger next = new AtomicInteger();
        when(resources.read(any(ResourceQuery.class))).thenAnswer(call -> {
            int from = next.getAndAdd(500);
            List<Resource> page = IntStream.range(from, from + 500)
                    .mapToObj(KafkaAclsTest::acl)
                    .toList();
            return Partial.complete(new ResourcePage(page, Optional.of("more")), List.of());
        });
        KafkaAcls acls = new KafkaAcls(gateway, mock(ClusterAccessGuard.class));

        KafkaAcls.Held held =
                acls.held(CLUSTER, List.of(new Acl("User:app", "*", "read", "deny", "topic", "prefixed", "orders-")));

        assertThat(held.truncated()).isTrue();
        assertThat(held.acls()).hasSize(KafkaAcls.MAX_READ);
    }
}
