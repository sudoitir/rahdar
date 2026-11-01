package io.github.sudoitir.rahdar.system.rabbitmq.web;

import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqBindings;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqExchanges;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFederation;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqNodes;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqPermissions;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqPolicies;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqQueues;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqShovels;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqVhosts;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.BindingPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.BindingRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangeResult;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.CreatedView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ExchangeRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.NodesView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PermissionPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PermissionRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyPreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.QueueRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ShovelPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.TopicPermissionRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.UserPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.VhostPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.VhostRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What only RabbitMQ has, under {@code /clusters/{id}/rabbitmq/} (ADR-0223). Lists page with an opaque
 * {@code cursor} and a {@code limit} (default 100, at most 500). Every mutation of a virtual host, a binding, a
 * policy or a permission is previewed first ({@code :preview}, answering the effect, the {@code etag} and its
 * {@code identityBasis}); the real call carries the etag, may be held for approval, and answers {@code 412} when the
 * resource changed since the preview. A resource id in a path is its canonical text, percent-encoded.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/rabbitmq")
@RequiredArgsConstructor
public class RabbitMqController {

    private final RabbitMqNodes nodes;
    private final RabbitMqVhosts vhosts;
    private final RabbitMqQueues queues;
    private final RabbitMqExchanges exchanges;
    private final RabbitMqBindings bindings;
    private final RabbitMqPolicies policies;
    private final RabbitMqPermissions permissions;
    private final RabbitMqShovels shovels;
    private final RabbitMqFederation federation;

    @GetMapping("/nodes")
    public NodesView nodes(@PathVariable UUID clusterId) {
        return nodes.list(clusterId);
    }

    // ---- virtual hosts ------------------------------------------------------------------------------------------

    @GetMapping("/vhosts")
    public VhostPage vhosts(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return vhosts.list(clusterId, cursor, limit);
    }

    @PostMapping("/vhosts:preview")
    public ChangePreview previewCreateVhost(@PathVariable UUID clusterId, @RequestBody VhostRequest request) {
        return vhosts.previewCreate(clusterId, request);
    }

    @HeldResponse
    @PostMapping("/vhosts")
    public ChangeResult createVhost(@PathVariable UUID clusterId, @RequestBody VhostRequest request) {
        return vhosts.create(clusterId, request);
    }

    @PatchMapping("/vhosts/{vhost}:preview")
    public ChangePreview previewUpdateVhost(
            @PathVariable UUID clusterId, @PathVariable String vhost, @RequestBody VhostRequest request) {
        return vhosts.previewUpdate(clusterId, vhost, request);
    }

    @HeldResponse
    @PatchMapping("/vhosts/{vhost}")
    public ChangeResult updateVhost(
            @PathVariable UUID clusterId, @PathVariable String vhost, @RequestBody VhostRequest request) {
        return vhosts.update(clusterId, vhost, request);
    }

    @DeleteMapping("/vhosts/{vhost}:preview")
    public ChangePreview previewDeleteVhost(@PathVariable UUID clusterId, @PathVariable String vhost) {
        return vhosts.previewDelete(clusterId, vhost);
    }

    @HeldResponse
    @DeleteMapping("/vhosts/{vhost}")
    public ChangeResult deleteVhost(
            @PathVariable UUID clusterId, @PathVariable String vhost, @RequestParam(required = false) String etag) {
        return vhosts.delete(clusterId, vhost, etag);
    }

    // ---- queues and exchanges -----------------------------------------------------------------------------------

    @PostMapping("/queues:preview")
    public ChangePreview previewCreateQueue(@PathVariable UUID clusterId, @RequestBody QueueRequest request) {
        return queues.preview(clusterId, request);
    }

    @PostMapping("/queues")
    public CreatedView createQueue(@PathVariable UUID clusterId, @RequestBody QueueRequest request) {
        return queues.create(clusterId, request);
    }

    @PostMapping("/exchanges:preview")
    public ChangePreview previewCreateExchange(@PathVariable UUID clusterId, @RequestBody ExchangeRequest request) {
        return exchanges.preview(clusterId, request);
    }

    @PostMapping("/exchanges")
    public CreatedView createExchange(@PathVariable UUID clusterId, @RequestBody ExchangeRequest request) {
        return exchanges.create(clusterId, request);
    }

    // ---- bindings -----------------------------------------------------------------------------------------------

    @GetMapping("/bindings")
    public BindingPage bindings(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String vhost,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return bindings.list(clusterId, vhost, source, destination, cursor, limit);
    }

    @PostMapping("/bindings:preview")
    public ChangePreview previewCreateBinding(@PathVariable UUID clusterId, @RequestBody BindingRequest request) {
        return bindings.previewCreate(clusterId, request);
    }

    @HeldResponse
    @PostMapping("/bindings")
    public ChangeResult createBinding(@PathVariable UUID clusterId, @RequestBody BindingRequest request) {
        return bindings.create(clusterId, request);
    }

    @DeleteMapping("/bindings/{id}:preview")
    public ChangePreview previewDeleteBinding(@PathVariable UUID clusterId, @PathVariable String id) {
        return bindings.previewDelete(clusterId, id);
    }

    @HeldResponse
    @DeleteMapping("/bindings/{id}")
    public ChangeResult deleteBinding(
            @PathVariable UUID clusterId, @PathVariable String id, @RequestParam(required = false) String etag) {
        return bindings.delete(clusterId, id, etag);
    }

    // ---- policies -----------------------------------------------------------------------------------------------

    @GetMapping("/policies")
    public PolicyPage policies(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String vhost,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return policies.list(clusterId, vhost, cursor, limit);
    }

    @PutMapping("/policies/{vhost}/{name}:preview")
    public PolicyPreview previewSetPolicy(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String name,
            @RequestBody PolicyRequest request) {
        return policies.previewSet(clusterId, vhost, name, request);
    }

    @HeldResponse
    @PutMapping("/policies/{vhost}/{name}")
    public ChangeResult setPolicy(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String name,
            @RequestBody PolicyRequest request) {
        return policies.set(clusterId, vhost, name, request);
    }

    @DeleteMapping("/policies/{vhost}/{name}:preview")
    public PolicyPreview previewClearPolicy(
            @PathVariable UUID clusterId, @PathVariable String vhost, @PathVariable String name) {
        return policies.previewClear(clusterId, vhost, name);
    }

    @HeldResponse
    @DeleteMapping("/policies/{vhost}/{name}")
    public ChangeResult clearPolicy(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String name,
            @RequestParam(required = false) String etag) {
        return policies.clear(clusterId, vhost, name, etag);
    }

    // ---- users and permissions ----------------------------------------------------------------------------------

    @GetMapping("/users")
    public UserPage users(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return permissions.users(clusterId, cursor, limit);
    }

    @GetMapping("/permissions")
    public PermissionPage permissions(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String vhost,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return permissions.list(clusterId, vhost, user, cursor, limit);
    }

    @PutMapping("/permissions/{vhost}/{user}:preview")
    public ChangePreview previewSetPermission(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String user,
            @RequestBody PermissionRequest request) {
        return permissions.previewSet(clusterId, vhost, user, request);
    }

    @HeldResponse
    @PutMapping("/permissions/{vhost}/{user}")
    public ChangeResult setPermission(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String user,
            @RequestBody PermissionRequest request) {
        return permissions.set(clusterId, vhost, user, request);
    }

    @DeleteMapping("/permissions/{vhost}/{user}:preview")
    public ChangePreview previewClearPermission(
            @PathVariable UUID clusterId, @PathVariable String vhost, @PathVariable String user) {
        return permissions.previewClear(clusterId, vhost, user);
    }

    @HeldResponse
    @DeleteMapping("/permissions/{vhost}/{user}")
    public ChangeResult clearPermission(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String user,
            @RequestParam(required = false) String etag) {
        return permissions.clear(clusterId, vhost, user, etag);
    }

    @PutMapping("/topic-permissions/{vhost}/{user}/{exchange}:preview")
    public ChangePreview previewSetTopicPermission(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String user,
            @PathVariable String exchange,
            @RequestBody TopicPermissionRequest request) {
        return permissions.previewSetTopic(clusterId, vhost, user, exchange, request);
    }

    @HeldResponse
    @PutMapping("/topic-permissions/{vhost}/{user}/{exchange}")
    public ChangeResult setTopicPermission(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String user,
            @PathVariable String exchange,
            @RequestBody TopicPermissionRequest request) {
        return permissions.setTopic(clusterId, vhost, user, exchange, request);
    }

    @DeleteMapping("/topic-permissions/{vhost}/{user}/{exchange}:preview")
    public ChangePreview previewClearTopicPermission(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String user,
            @PathVariable String exchange) {
        return permissions.previewClearTopic(clusterId, vhost, user, exchange);
    }

    @HeldResponse
    @DeleteMapping("/topic-permissions/{vhost}/{user}/{exchange}")
    public ChangeResult clearTopicPermission(
            @PathVariable UUID clusterId,
            @PathVariable String vhost,
            @PathVariable String user,
            @PathVariable String exchange,
            @RequestParam(required = false) String etag) {
        return permissions.clearTopic(clusterId, vhost, user, exchange, etag);
    }

    // ---- shovels and federation ---------------------------------------------------------------------------------

    @GetMapping("/shovels")
    public ShovelPage shovels(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String vhost,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return shovels.list(clusterId, vhost, cursor, limit);
    }

    @DeleteMapping("/shovels/{id}:preview")
    public ChangePreview previewDeleteShovel(@PathVariable UUID clusterId, @PathVariable String id) {
        return bindings.previewDelete(clusterId, id);
    }

    @HeldResponse
    @DeleteMapping("/shovels/{id}")
    public ChangeResult deleteShovel(
            @PathVariable UUID clusterId, @PathVariable String id, @RequestParam(required = false) String etag) {
        return bindings.delete(clusterId, id, etag);
    }

    @GetMapping("/federation-links")
    public FederationPage federationLinks(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String vhost,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return federation.list(clusterId, vhost, cursor, limit);
    }
}
