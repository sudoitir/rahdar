package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupMember;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The running members of a group, as the driver names them in a preview ("client@host (id)"). The driver does not
 * model a member's partitions, so {@code assignments} stays empty until it does.
 */
@Component
class KafkaGroupMembers {

    List<GroupMember> of(ClusterConnection connection, Resource group, KindId kind) {
        if (kind == null) {
            return List.of();
        }
        List<Resource> subscriptions = KafkaGroups.read(
                        connection,
                        ResourceQuery.of(kind)
                                .where(Map.of("group", group.id().name()))
                                .limit(1))
                .items();
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        return KafkaFailures.call(() -> connection
                .operations()
                .preview(subscriptions.getFirst().id(), new Operation.Seek(Position.latest()), Scope.whole())
                .diff()
                .stream()
                .filter(c -> c.key().equals(KafkaKinds.MEMBERS))
                .map(AttributeChange::before)
                .flatMap(Optional::stream)
                .filter(List.class::isInstance)
                .flatMap(l -> ((List<?>) l).stream())
                .map(String::valueOf)
                .map(KafkaGroupMembers::parse)
                .toList());
    }

    /** "client@host (id)" back into its parts. */
    static GroupMember parse(String described) {
        int open = described.lastIndexOf(" (");
        String id = open < 0 ? described : described.substring(open + 2, described.length() - 1);
        String who = open < 0 ? described : described.substring(0, open);
        int at = who.lastIndexOf('@');
        return new GroupMember(id, at < 0 ? who : who.substring(0, at), at < 0 ? "" : who.substring(at + 1), List.of());
    }
}
