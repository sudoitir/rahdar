package io.github.sudoitir.rahdar.kernel.stream;

import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.replica.BusFrame;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The stream's access rule. A subscriber holding the topic's permission on the cluster, through a grant,
 * sees every frame of the topic. Anyone else sees a frame only through the queues and addresses it names:
 * those they may read are kept, and a frame left naming none, or naming none to begin with, is not for them.
 * A topic with a {@link StreamGate} is decided by the gate. A subscriber with no principal, or a frame of a
 * topic no module declares, sees nothing.
 *
 * <p>Access is read at each decision from the principal's current grants and teams, which the resolver
 * keeps fresh, so a change applies to the next frame without the client reconnecting.
 */
@Component
class PermissionStreamAccess implements StreamAccess {

    private final PermissionResolver permissions;
    private final StreamTopicRegistry topics;
    private final ObjectMapper mapper;
    private final Map<String, StreamGate> gates;

    PermissionStreamAccess(
            PermissionResolver permissions,
            StreamTopicRegistry topics,
            ObjectMapper mapper,
            ObjectProvider<StreamGate> gates) {
        this.permissions = permissions;
        this.topics = topics;
        this.mapper = mapper;
        this.gates =
                gates.orderedStream().collect(Collectors.toUnmodifiableMap(StreamGate::topic, Function.identity()));
    }

    @Override
    public Subscriber.Held narrow(Subscriber subscriber, UUID clusterId, Subscriber.Held frame) {
        if (SseHub.isControl(frame.topic())) {
            return frame;
        }
        RahdarPrincipal principal = subscriber.principal();
        TopicDef topic = topics.definition(frame.topic());
        if (principal == null || topic == null) {
            return null;
        }
        StreamGate gate = gates.get(topic.name());
        if (gate != null) {
            return gate.mayRead(principal, clusterId, json(frame.data())) ? frame : null;
        }
        String permission = topic.permission() == null ? Permissions.CLUSTER_READ : topic.permission();
        if (permissions.can(principal, clusterId, permission)) {
            return frame;
        }
        BusFrame.About about = frame.about();
        if (about == null) {
            return null;
        }
        List<String> queues = about.queues().stream()
                .filter(name -> permissions.can(
                        principal, clusterId, ResourceRef.subscription(name), Permissions.SUBSCRIPTION_READ))
                .toList();
        List<String> addresses = about.addresses().stream()
                .filter(name -> permissions.can(
                        principal, clusterId, ResourceRef.destination(name), Permissions.DESTINATION_READ))
                .toList();
        if (queues.isEmpty() && addresses.isEmpty()) {
            return null;
        }
        boolean whole = queues.size() == about.queues().size()
                && addresses.size() == about.addresses().size();
        return whole
                ? frame
                : new Subscriber.Held(frame.topic(), frame.data(), frame.id(), new BusFrame.About(queues, addresses));
    }

    private JsonNode json(Object data) {
        return data instanceof JsonNode node ? node : mapper.valueToTree(data);
    }
}
