package io.github.sudoitir.rahdar.system.rabbitmq;

import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.change;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.preview;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangePreview;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The reads and the previews every part of the namespace builds on. */
class RabbitMqReadsTest {

    @Test
    void aLimitIsKeptInRange() {
        assertThat(RabbitMqReads.limit(null)).isEqualTo(100);
        assertThat(RabbitMqReads.limit(0)).isEqualTo(1);
        assertThat(RabbitMqReads.limit(9_999)).isEqualTo(500);
        assertThat(RabbitMqReads.limit(25)).isEqualTo(25);
    }

    @Test
    void anOffsetCursorIsANumberOrTheStart() {
        assertThat(RabbitMqReads.offset(null)).isZero();
        assertThat(RabbitMqReads.offset(" ")).isZero();
        assertThat(RabbitMqReads.offset("40")).isEqualTo(40);
        assertThat(RabbitMqReads.offset("-3")).isZero();
        assertThatThrownBy(() -> RabbitMqReads.offset("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReadFollowsTheContinueTokenUntilTheCap() {
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        when(connection.resources()).thenReturn(resources);
        List<Resource> page = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            page.add(resource("rabbitmq.user/u" + i, Map.of()));
        }
        when(resources.read(any(ResourceQuery.class)))
                .thenAnswer(call -> Partial.complete(new ResourcePage(page, Optional.of("more")), List.of()));

        RabbitMqReads.Found found = RabbitMqReads.all(connection, ResourceQuery.of(RabbitMqIds.kind(RabbitMqIds.USER)));

        assertThat(found.truncated()).isTrue();
        assertThat(found.items()).hasSize(RabbitMqReads.CAP);
    }

    @Test
    void anAttributeIsReadAsTextFlagNumberOrMembers() {
        Resource queue = resource(
                "rabbitmq.classic-queue/v/q",
                Map.of(
                        RabbitMqIds.DESCRIPTION,
                        "d",
                        RabbitMqIds.AUTO_DELETE,
                        "true",
                        RabbitMqIds.PRIORITY,
                        7,
                        RabbitMqIds.PATTERN,
                        "not a number",
                        RabbitMqIds.ARGUMENT_PREFIX + "x-b",
                        2,
                        RabbitMqIds.ARGUMENT_PREFIX + "x-a",
                        "s"));

        assertThat(RabbitMqReads.text(queue, RabbitMqIds.DESCRIPTION)).isEqualTo("d");
        assertThat(RabbitMqReads.text(queue, RabbitMqIds.TAGS)).isEmpty();
        assertThat(RabbitMqReads.flag(queue, RabbitMqIds.AUTO_DELETE)).isTrue();
        assertThat(RabbitMqReads.flag(queue, RabbitMqIds.INTERNAL)).isFalse();
        assertThat(RabbitMqReads.number(queue, RabbitMqIds.PRIORITY)).isEqualTo(7);
        assertThat(RabbitMqReads.number(queue, RabbitMqIds.PATTERN)).isZero();
        assertThat(RabbitMqReads.members(queue, RabbitMqIds.ARGUMENT_PREFIX))
                .containsExactly(Map.entry("x-a", "s"), Map.entry("x-b", "2"));
    }

    @Test
    void theVerbDecidesTheSdkOperation() {
        assertThat(RabbitMqOps.operation(Verb.CREATE, Map.of("a", "1"))).isInstanceOf(Operation.Create.class);
        assertThat(RabbitMqOps.operation(Verb.UPDATE, null)).isInstanceOf(Operation.Update.class);
        assertThat(RabbitMqOps.operation(Verb.DELETE, Map.of())).isEqualTo(new Operation.Delete(false, false));
    }

    @Test
    void aDeletionCountsMessagesAndOtherChangesCountAttributes() {
        ChangePreview deletion =
                RabbitMqOps.view(preview("e", 40, change("k", "a", null)), Verb.DELETE, "Delete it.", List.of("x"));
        ChangePreview creation = RabbitMqOps.view(
                preview("e", 0, change("k", null, "a"), change("j", null, "b")), Verb.CREATE, "Make it.", List.of());

        assertThat(deletion.effect().count()).isEqualTo(40);
        assertThat(deletion.effect().unit()).isEqualTo("messages");
        assertThat(deletion.effect().lines()).containsExactly("x");
        assertThat(deletion.effect().changes()).hasSize(1);
        assertThat(creation.effect().count()).isEqualTo(2);
        assertThat(creation.effect().unit()).isEqualTo("changes");
        assertThat(creation.etag()).isEqualTo("e");
    }

    @Test
    void anEtagMustBeThePreviewsOwn() {
        RabbitMqOps.require("e", "e");

        assertThatThrownBy(() -> RabbitMqOps.require(null, "e")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqOps.require(" ", "e")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqOps.require("x", "e")).hasMessageContaining("Preview again");
    }
}
