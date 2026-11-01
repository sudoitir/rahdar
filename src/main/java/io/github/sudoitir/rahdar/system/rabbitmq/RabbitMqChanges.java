package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.IdentityBasis;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangeResult;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.CreatedView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Diff;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The changes that go through the approval gate (ADR-0223): virtual hosts, bindings, policies, permissions, and
 * deleting a shovel. A change is previewed first; the real call carries the preview's etag, is held where a policy
 * says so, is audited before and after, and is refused as stale when the etag no longer matches when it runs.
 */
@Service
@RequiredArgsConstructor
public class RabbitMqChanges {

    /** Which gated operation a change is, and how it is audited. */
    public enum Type {
        NAMESPACE_CREATE("namespace.create", "CREATE_NAMESPACE", "NAMESPACE", Verb.CREATE, "Create"),
        NAMESPACE_UPDATE("namespace.update", "UPDATE_NAMESPACE", "NAMESPACE", Verb.UPDATE, "Change"),
        NAMESPACE_DELETE("namespace.delete", "DELETE_NAMESPACE", "NAMESPACE", Verb.DELETE, "Delete"),
        ROUTE_CREATE("route.create", "CREATE_ROUTE", "ROUTE", Verb.CREATE, "Create"),
        ROUTE_DELETE("route.delete", "DELETE_ROUTE", "ROUTE", Verb.DELETE, "Delete"),
        POLICY_SET("policy.set", "SET_POLICY", "POLICY", Verb.CREATE, "Set"),
        POLICY_CLEAR("policy.clear", "CLEAR_POLICY", "POLICY", Verb.DELETE, "Clear"),
        ACCESS_RULE_SET("access-rule.set", "SET_ACCESS_RULE", "ACCESS_RULE", Verb.CREATE, "Set"),
        ACCESS_RULE_CLEAR("access-rule.clear", "CLEAR_ACCESS_RULE", "ACCESS_RULE", Verb.DELETE, "Clear");

        private final String gateType;
        private final String audit;
        private final String target;
        private final Verb verb;
        private final String phrase;

        Type(String gateType, String audit, String target, Verb verb, String phrase) {
            this.gateType = gateType;
            this.audit = audit;
            this.target = target;
            this.verb = verb;
            this.phrase = phrase;
        }

        public String gateType() {
            return gateType;
        }

        Verb verb() {
            return verb;
        }

        /** Whether the change loses what the resource holds. */
        boolean destructive() {
            return verb == Verb.DELETE;
        }
    }

    /** One change: the parameters of its gated operation. The etag pins the state the operator previewed. */
    public interface Change {

        Type type();

        UUID clusterId();

        /** The canonical text of the {@link ResourceId} the change acts on. */
        String id();

        Map<String, Object> attributes();

        String etag();
    }

    public record NamespaceCreate(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.NAMESPACE_CREATE;
        }
    }

    public record NamespaceUpdate(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.NAMESPACE_UPDATE;
        }
    }

    public record NamespaceDelete(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.NAMESPACE_DELETE;
        }
    }

    public record RouteCreate(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.ROUTE_CREATE;
        }
    }

    public record RouteDelete(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.ROUTE_DELETE;
        }
    }

    public record PolicySet(UUID clusterId, String id, Map<String, Object> attributes, String etag) implements Change {
        @Override
        public Type type() {
            return Type.POLICY_SET;
        }
    }

    public record PolicyClear(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.POLICY_CLEAR;
        }
    }

    public record AccessRuleSet(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.ACCESS_RULE_SET;
        }
    }

    public record AccessRuleClear(UUID clusterId, String id, Map<String, Object> attributes, String etag)
            implements Change {
        @Override
        public Type type() {
            return Type.ACCESS_RULE_CLEAR;
        }
    }

    private final RabbitMqOps ops;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    @Gated("namespace.create")
    ChangeResult namespaceCreate(NamespaceCreate change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("namespace.update")
    ChangeResult namespaceUpdate(NamespaceUpdate change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("namespace.delete")
    ChangeResult namespaceDelete(NamespaceDelete change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("route.create")
    ChangeResult routeCreate(RouteCreate change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("route.delete")
    ChangeResult routeDelete(RouteDelete change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("policy.set")
    ChangeResult policySet(PolicySet change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("policy.clear")
    ChangeResult policyClear(PolicyClear change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("access-rule.set")
    ChangeResult accessRuleSet(AccessRuleSet change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    @Gated("access-rule.clear")
    ChangeResult accessRuleClear(AccessRuleClear change) {
        return gate.run(Operation.of(change), () -> run(change));
    }

    /** Previews the change, refuses an etag that is not the preview's, and hands it to the gate. */
    ChangeResult apply(Change change) {
        Preview preview = ops.preview(
                change.clusterId(), ResourceId.parse(change.id()), change.type().verb(), change.attributes());
        RabbitMqOps.require(change.etag(), preview.etag());
        return switch (change) {
            case NamespaceCreate c -> namespaceCreate(c);
            case NamespaceUpdate c -> namespaceUpdate(c);
            case NamespaceDelete c -> namespaceDelete(c);
            case RouteCreate c -> routeCreate(c);
            case RouteDelete c -> routeDelete(c);
            case PolicySet c -> policySet(c);
            case PolicyClear c -> policyClear(c);
            case AccessRuleSet c -> accessRuleSet(c);
            case AccessRuleClear c -> accessRuleClear(c);
            default -> throw new IllegalArgumentException("Not a change: " + change.type());
        };
    }

    /** What the change would do now, for the gate: the estimate an approver reads, and the etag it pins. */
    public Effect estimate(Change change) {
        Preview preview = ops.preview(
                change.clusterId(), ResourceId.parse(change.id()), change.type().verb(), change.attributes());
        IdentityBasis basis = IdentityBasis.of(preview.basis());
        long count = preview.diff().size();
        return new Effect(count, "changes", preview.etag(), RabbitMqOps.detail(summary(change), basis));
    }

    public void replay(Change change) {
        run(change);
    }

    /** One line saying what the change is, such as "Delete policy ha in /". */
    static String summary(Change change) {
        return describe(change.type(), ResourceId.parse(change.id()));
    }

    static String describe(Type type, ResourceId id) {
        return type.phrase + " " + noun(id) + " " + String.join(" / ", id.path().segments()) + ".";
    }

    /** What the resource is called in a sentence. */
    static String noun(ResourceId id) {
        return switch (id.kind().value()) {
            case RabbitMqIds.VHOST -> "virtual host";
            case RabbitMqIds.EXCHANGE_BINDING -> "exchange binding";
            case RabbitMqIds.TOPIC_PERMISSION -> "topic permission";
            default -> id.kind().value().substring(id.kind().value().indexOf('.') + 1);
        };
    }

    /**
     * Creates a queue or an exchange: audited, previewed first, and run under the preview's etag when the caller
     * sends it. It is not gated today and is kept so.
     */
    CreatedView create(
            UUID clusterId, ResourceId id, Map<String, Object> attributes, String etag, String action, String target) {
        AuditEvent event = audit.begin(
                actors.resolve(),
                action,
                target,
                id.name(),
                clusterId,
                null,
                Map.of("id", id.format(), "attributes", attributes),
                false);
        try {
            if (etag != null && !etag.isBlank()) {
                RabbitMqOps.require(
                        etag,
                        ops.preview(clusterId, id, Verb.CREATE, attributes).etag());
            }
            ops.execute(clusterId, id, Verb.CREATE, attributes, etag == null || etag.isBlank() ? null : etag);
            audit.finish(event, false, 1, null, Map.of("id", id.format()));
            return new CreatedView(id.format());
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private ChangeResult run(Change change) {
        ResourceId id = ResourceId.parse(change.id());
        Type type = change.type();
        AuditEvent event = audit.begin(
                actors.resolve(),
                type.audit,
                type.target,
                id.name(),
                change.clusterId(),
                null,
                Map.of("id", change.id(), "attributes", change.attributes()),
                false);
        try {
            Preview preview = ops.preview(change.clusterId(), id, type.verb(), change.attributes());
            RabbitMqOps.require(change.etag(), preview.etag());
            List<Diff> changes = RabbitMqOps.diff(preview);
            OperationResult result =
                    ops.execute(change.clusterId(), id, type.verb(), change.attributes(), change.etag());
            audit.finish(
                    event,
                    false,
                    result.affected().orElse(changes.size()),
                    null,
                    Map.of("changes", changes, "identityBasis", IdentityBasis.of(preview.basis())));
            return new ChangeResult(
                    change.id(), IdentityBasis.of(preview.basis()).name(), changes);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }
}
