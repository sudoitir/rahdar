package io.github.sudoitir.rahdar.system.rabbitmq.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** What the RabbitMQ namespace answers and takes (ADR-0223). A figure that is not known is absent, never zero. */
public final class RabbitMqViews {

    private RabbitMqViews() {}

    // ---- changes ------------------------------------------------------------------------------------------------

    public record RabbitMqDiff(
            @Schema(requiredMode = REQUIRED) String key,

            @Schema(nullable = true, description = "The value now; absent when the resource has none.")
            String before,

            @Schema(nullable = true, description = "The value after the change; absent when the change removes it.")
            String after) {}

    public record RabbitMqEffect(
            @Schema(requiredMode = REQUIRED, description = "How much the change affects, in the unit.")
            long count,

            @Schema(requiredMode = REQUIRED, description = "messages, or changes.")
            String unit,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What the change does, and what the etag does not tell apart.")
            String detail,

            @Schema(requiredMode = REQUIRED, description = "Each attribute the change sets, with its value now.")
            List<RabbitMqDiff> changes,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What the change reaches or leaves: the queues of a virtual host,"
                            + " the resources a policy matches, a user's rights before and after.")
            List<String> lines) {}

    public record RabbitMqChangePreview(
            @Schema(requiredMode = REQUIRED) RabbitMqEffect effect,

            @Schema(requiredMode = REQUIRED, description = "Send it back with the change.")
            String etag,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"CONFIGURATION", "INCARNATION"},
                    description = "What the etag holds. RabbitMQ is always CONFIGURATION: a resource deleted"
                            + " and created again with the same configuration is not told apart.")
            String identityBasis) {}

    public record RabbitMqChangeResult(
            @Schema(requiredMode = REQUIRED, description = "The resource id.")
            String id,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"CONFIGURATION", "INCARNATION"})
            String identityBasis,

            @Schema(requiredMode = REQUIRED) List<RabbitMqDiff> changes) {}

    // ---- nodes --------------------------------------------------------------------------------------------------

    public record Listener(
            @Schema(requiredMode = REQUIRED) String protocol,
            @Schema(requiredMode = REQUIRED) int port) {}

    public record NodeView(
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(nullable = true, description = "The RabbitMQ release the node runs.")
            String version,

            @Schema(requiredMode = REQUIRED) boolean running,

            @Schema(requiredMode = REQUIRED, description = "memory and disk.")
            List<String> alarms,

            @Schema(requiredMode = REQUIRED, description = "The nodes this one is partitioned from.")
            List<String> partitions,

            @Schema(requiredMode = REQUIRED) List<Listener> listeners) {}

    public record NodesView(
            @Schema(nullable = true) String clusterName,
            @Schema(requiredMode = REQUIRED) List<NodeView> nodes) {}

    // ---- virtual hosts ------------------------------------------------------------------------------------------

    public record Vhost(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String description,
            @Schema(requiredMode = REQUIRED) List<String> tags,
            @Schema(nullable = true) String defaultQueueType,
            @Schema(requiredMode = REQUIRED) long queues,

            @Schema(requiredMode = REQUIRED, description = "Messages ready in its queues, an estimate.")
            long messages) {}

    public record VhostPage(
            @Schema(requiredMode = REQUIRED) List<Vhost> items,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether the counts cover every queue; false when the read was cut short.")
            boolean countsComplete,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    public record VhostRequest(
            @Schema(description = "The name; on a change it is the path's.")
            String name,

            String description,

            @Schema(description = "Tags such as management or monitoring.")
            List<String> tags,

            @Schema(allowableValues = {"classic", "quorum", "stream"})
            String defaultQueueType,

            @Schema(description = "The preview's etag; required on a real call.")
            String etag) {}

    // ---- queues and exchanges -----------------------------------------------------------------------------------

    public record QueueRequest(
            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"classic", "quorum", "stream"})
            String type,

            Boolean durable,
            Boolean autoDelete,

            @Schema(description = "Arguments such as x-max-length, by name.")
            Map<String, Object> arguments,

            @Schema(description = "The preview's etag; optional on a real call.")
            String etag) {}

    public record ExchangeRequest(
            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"direct", "fanout", "topic", "headers"})
            String type,

            Boolean durable,
            Boolean autoDelete,
            Boolean internal,
            Map<String, Object> arguments,

            @Schema(description = "The preview's etag; optional on a real call.")
            String etag) {}

    public record CreatedView(
            @Schema(requiredMode = REQUIRED, description = "The resource id.")
            String id) {}

    // ---- bindings -----------------------------------------------------------------------------------------------

    public record Binding(
            @Schema(requiredMode = REQUIRED, description = "The resource id; delete a binding by it.")
            String id,

            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String source,
            @Schema(requiredMode = REQUIRED) String destination,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"queue", "exchange"})
            String destinationType,

            @Schema(requiredMode = REQUIRED) String routingKey,
            @Schema(requiredMode = REQUIRED) Map<String, String> arguments) {}

    public record BindingPage(
            @Schema(requiredMode = REQUIRED) List<Binding> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    public record BindingRequest(
            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String source,
            @Schema(requiredMode = REQUIRED) String destination,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"queue", "exchange"})
            String destinationType,

            String routingKey,
            Map<String, Object> arguments,

            @Schema(description = "The preview's etag; required on a real call.")
            String etag) {}

    public record EtagRequest(
            @Schema(description = "The preview's etag; required on a real call.")
            String etag) {}

    // ---- policies -----------------------------------------------------------------------------------------------

    public record Policy(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String pattern,
            @Schema(requiredMode = REQUIRED) String applyTo,
            @Schema(requiredMode = REQUIRED) long priority,

            @Schema(requiredMode = REQUIRED, description = "Each setting, with its value as JSON.")
            Map<String, String> definition,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "An operator policy, which Rahdar lists and does not change.")
            boolean operator,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The queues and exchanges the pattern matches now, as Rahdar matches it.")
            long matches) {}

    public record PolicyPage(
            @Schema(requiredMode = REQUIRED) List<Policy> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    public record PolicyRequest(
            @Schema(requiredMode = REQUIRED, description = "A regular expression over the names.")
            String pattern,

            @Schema(allowableValues = {"all", "queues", "classic_queues", "quorum_queues", "streams", "exchanges"})
            String applyTo,

            Long priority,

            @Schema(requiredMode = REQUIRED, description = "Each setting by name, its value as JSON.")
            Map<String, String> definition,

            @Schema(description = "The preview's etag; required on a real call.")
            String etag) {}

    public record PolicyMatch(
            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"classic_queue", "quorum_queue", "stream", "exchange"})
            String kind,

            @Schema(requiredMode = REQUIRED) String name,

            @Schema(nullable = true, description = "A policy of higher priority that wins; absent when this one does.")
            String shadowedBy) {}

    public record PolicyPreview(
            @Schema(requiredMode = REQUIRED) RabbitMqEffect effect,
            @Schema(requiredMode = REQUIRED) String etag,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"CONFIGURATION", "INCARNATION"})
            String identityBasis,

            @Schema(requiredMode = REQUIRED, description = "As Rahdar matches it: a Java regular expression.")
            List<PolicyMatch> matches,

            @Schema(requiredMode = REQUIRED, description = "The matches were cut short or the names not all read.")
            boolean partial) {}

    // ---- users and permissions ----------------------------------------------------------------------------------

    public record User(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) List<String> tags) {}

    public record UserPage(
            @Schema(requiredMode = REQUIRED) List<User> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    public record TopicPermission(
            @Schema(requiredMode = REQUIRED) String exchange,
            @Schema(requiredMode = REQUIRED) String write,
            @Schema(requiredMode = REQUIRED) String read) {}

    public record Permission(
            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String user,
            @Schema(requiredMode = REQUIRED) String configure,
            @Schema(requiredMode = REQUIRED) String write,
            @Schema(requiredMode = REQUIRED) String read,
            @Schema(requiredMode = REQUIRED) List<TopicPermission> topic) {}

    public record PermissionPage(
            @Schema(requiredMode = REQUIRED) List<Permission> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    public record PermissionRequest(
            @Schema(
                    requiredMode = REQUIRED,
                    description = "A regular expression over the names the user may configure.")
            String configure,

            @Schema(requiredMode = REQUIRED) String write,
            @Schema(requiredMode = REQUIRED) String read,

            @Schema(description = "The preview's etag; required on a real call.")
            String etag) {}

    public record TopicPermissionRequest(
            @Schema(requiredMode = REQUIRED) String write,
            @Schema(requiredMode = REQUIRED) String read,

            @Schema(description = "The preview's etag; required on a real call.")
            String etag) {}

    // ---- shovels and federation ---------------------------------------------------------------------------------

    public record Shovel(
            @Schema(requiredMode = REQUIRED, description = "The resource id; delete a shovel by it.")
            String id,

            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String state,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Where it reads from; a remote broker's address has no credentials.")
            String source,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Where it writes to; a remote broker's address has no credentials.")
            String destination,

            @Schema(nullable = true, description = "configuration for a shovel the node's configuration defines.")
            String managedBy) {}

    public record ShovelPage(
            @Schema(requiredMode = REQUIRED) boolean available,

            @Schema(nullable = true, description = "What to enable when the shovels cannot be listed.")
            String remedy,

            @Schema(requiredMode = REQUIRED) List<Shovel> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    public record FederationLink(
            @Schema(requiredMode = REQUIRED) String vhost,
            @Schema(requiredMode = REQUIRED) String upstream,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"exchange", "queue"})
            String type,

            @Schema(requiredMode = REQUIRED, description = "The federated exchange or queue.")
            String name,

            @Schema(nullable = true) String status,

            @Schema(requiredMode = REQUIRED, description = "The upstream, without credentials.")
            String source) {}

    public record FederationPage(
            @Schema(requiredMode = REQUIRED) boolean available,

            @Schema(nullable = true, description = "What to enable when the links cannot be listed.")
            String remedy,

            @Schema(requiredMode = REQUIRED) List<FederationLink> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}
}
