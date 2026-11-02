package io.github.sudoitir.rahdar.system.rabbitmq;

import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.ETAG;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.change;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.preview;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.AccessRuleClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.AccessRuleSet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.Change;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PermissionPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PermissionRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.TopicPermissionRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.User;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.UserPage;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Users and permissions are listed without a secret, and a change shows the user's rights before and after. */
class RabbitMqAccessRulesTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static final Resource PERMISSION = resource(
            "rabbitmq.permission/%2F/app",
            Map.of(RabbitMqIds.CONFIGURE, "^app\\..*", RabbitMqIds.WRITE, ".*", RabbitMqIds.READ, ".*"));
    private static final Resource TOPIC = resource(
            "rabbitmq.topic-permission/%2F/app/events", Map.of(RabbitMqIds.WRITE, "^app\\..*", RabbitMqIds.READ, ""));

    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final RabbitMqOps ops = mock(RabbitMqOps.class);
    private final RabbitMqChanges changes = mock(RabbitMqChanges.class);
    private final RabbitMqAccessRules permissions;

    RabbitMqAccessRulesTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = RabbitMqFixtures.connection(RabbitMqAccessRulesTest::answer);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        permissions = new RabbitMqAccessRules(gateway, access, ops, changes);
    }

    private static List<Resource> answer(ResourceQuery query) {
        return switch (query.kind().orElseThrow().value()) {
            case RabbitMqIds.USER ->
                List.of(resource("rabbitmq.user/app", Map.of(RabbitMqIds.TAGS, "management, monitoring")));
            case RabbitMqIds.PERMISSION -> List.of(PERMISSION);
            case RabbitMqIds.TOPIC_PERMISSION -> List.of(TOPIC);
            default -> List.of();
        };
    }

    @Test
    void usersHaveTagsAndNothingElse() {
        UserPage page = permissions.users(CLUSTER, null, null);

        verify(access).requireCluster(CLUSTER, ResourcePermissions.ACCESS_RULE_READ);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().name()).isEqualTo("app");
        assertThat(page.items().getFirst().tags()).containsExactly("management", "monitoring");
        assertThat(names(User.class)).containsExactlyInAnyOrder("name", "tags");
    }

    @Test
    void aPermissionCarriesItsTopicPermissions() {
        PermissionPage page = permissions.list(CLUSTER, "/", "app", null, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().configure()).isEqualTo("^app\\..*");
        assertThat(page.items().getFirst().topic()).hasSize(1);
        assertThat(page.items().getFirst().topic().getFirst().exchange()).isEqualTo("events");
        assertThat(page.items().getFirst().topic().getFirst().read()).isEmpty();
    }

    @Test
    void aSetPreviewShowsTheRightsBeforeAndAfter() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.CREATE), any()))
                .thenReturn(preview(ETAG, 0, change(RabbitMqIds.READ, ".*", "^q$")));

        RabbitMqChangePreview preview =
                permissions.previewSet(CLUSTER, "/", "app", new PermissionRequest("^app\\..*", ".*", "^q$", null));

        verify(access).requireCluster(CLUSTER, ResourcePermissions.ACCESS_RULE_WRITE);
        assertThat(preview.identityBasis()).isEqualTo("CONFIGURATION");
        assertThat(preview.effect().lines())
                .containsExactly(
                        "Before: configure '^app\\..*', write '.*', read '.*'",
                        "After: configure '^app\\..*', write '.*', read '^q$'");
    }

    @Test
    void aClearPreviewSaysTheUserKeepsNoAccess() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any())).thenReturn(preview(ETAG, 0));

        RabbitMqChangePreview preview = permissions.previewClear(CLUSTER, "/", "app");

        assertThat(preview.effect().lines().getLast()).isEqualTo("After: none");
        assertThat(permissions
                        .previewClear(CLUSTER, "/", "nobody")
                        .effect()
                        .lines()
                        .getFirst())
                .isEqualTo("Before: none");
    }

    @Test
    void aTopicPermissionPreviewShowsItsOwnRights() {
        when(ops.preview(eq(CLUSTER), any(), any(), any())).thenReturn(preview(ETAG, 0));

        RabbitMqChangePreview preview = permissions.previewSetTopic(
                CLUSTER, "/", "app", "events", new TopicPermissionRequest("^new$", "", null));
        RabbitMqChangePreview cleared = permissions.previewClearTopic(CLUSTER, "/", "app", "events");

        assertThat(preview.effect().lines())
                .containsExactly("Before: write '^app\\..*', read ''", "After: write '^new$', read ''");
        assertThat(cleared.effect().lines()).hasSize(2);
    }

    @Test
    void theRealCallsAreAccessRuleChanges() {
        permissions.set(CLUSTER, "/", "app", new PermissionRequest(".*", ".*", ".*", "e1"));
        permissions.clear(CLUSTER, "/", "app", "e2");
        permissions.setTopic(CLUSTER, "/", "app", "events", new TopicPermissionRequest(".*", ".*", "e3"));
        permissions.clearTopic(CLUSTER, "/", "app", "events", "e4");

        ArgumentCaptor<Change> change = ArgumentCaptor.forClass(Change.class);
        verify(changes, times(4)).apply(change.capture());
        assertThat(change.getAllValues())
                .extracting(Change::id)
                .containsExactly(
                        "rabbitmq.permission/%2F/app",
                        "rabbitmq.permission/%2F/app",
                        "rabbitmq.topic-permission/%2F/app/events",
                        "rabbitmq.topic-permission/%2F/app/events");
        assertThat(change.getAllValues().get(0)).isInstanceOf(AccessRuleSet.class);
        assertThat(change.getAllValues().get(1)).isInstanceOf(AccessRuleClear.class);
        assertThat(change.getAllValues()).extracting(Change::etag).containsExactly("e1", "e2", "e3", "e4");
    }

    @Test
    void aPermissionMustNameWhoAndWhereAndBeAPattern() {
        assertThatThrownBy(() -> permissions.clear(CLUSTER, "/", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> permissions.clearTopic(CLUSTER, "/", "app", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> permissions.set(CLUSTER, "/", "app", new PermissionRequest("(", ".*", ".*", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular expression");
    }

    private static List<String> names(Class<? extends Record> view) {
        return Arrays.stream(view.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
