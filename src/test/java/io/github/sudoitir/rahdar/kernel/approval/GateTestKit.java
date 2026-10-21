package io.github.sudoitir.rahdar.kernel.approval;

import io.github.sudoitir.rahdar.kernel.gate.ApprovalProvider;
import io.github.sudoitir.rahdar.kernel.gate.Approver;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GateDecision;
import io.github.sudoitir.rahdar.kernel.gate.GateRequest;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.HeldOperationView;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.PolicyRef;
import io.github.sudoitir.rahdar.kernel.gate.RunCheck;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.gate.Vote;
import io.github.sudoitir.rahdar.kernel.gate.VoteCheck;
import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only gated operations, a service that gates them the way Rahdar's services do, a REST endpoint over it, and a
 * provider whose answers each test sets. Nothing here ships.
 */
@TestConfiguration(proxyBeanMethods = false)
class GateTestKit {

    /** The permission a test service checks before it gates, the way a real service authorizes first. */
    static final String SERVICE_PERMISSION = Permissions.TEAM_ADMIN;

    /** The permission the test provider's approvers need. */
    static final String APPROVER_PERMISSION = Permissions.USER_ADMIN;

    static final PolicyRef POLICY = new PolicyRef("p-1", "3", "Two people for purges");

    record PurgeParams(String queue, String secret) {}

    record TokenParams(String name) {}

    record BulkParams(List<String> queues) {}

    /** What the estimate reports, so a test can change the state key or count between request and run. */
    static final class TestState {
        volatile String etag = "state-1";
        volatile long count = 7;
        volatile int purgeVersion = 1;
    }

    /** The gated service: authorizes, then passes the gate, then acts. */
    static final class TestService {
        final List<String> purged = new CopyOnWriteArrayList<>();
        final AtomicInteger tokens = new AtomicInteger();
        /** Runs inside the purge action, before it records: a test makes the action fail or be interrupted here. */
        volatile Runnable duringPurge = () -> {};

        private final OperationGate gate;
        private final PermissionResolver permissions;

        TestService(OperationGate gate, PermissionResolver permissions) {
            this.gate = gate;
            this.permissions = permissions;
        }

        int purge(PurgeParams params) {
            authorize();
            return gate.run(Operation.of(params), () -> {
                duringPurge.run();
                purged.add(params.queue());
                return purged.size();
            });
        }

        String createToken(TokenParams params) {
            authorize();
            return gate.run(Operation.of(params), () -> "secret-" + params.name() + "-" + tokens.incrementAndGet());
        }

        int bulk(BulkParams params) {
            authorize();
            return gate.run(Operation.of(params), () -> {
                params.queues().forEach(queue -> purge(new PurgeParams(queue, null)));
                return params.queues().size();
            });
        }

        private void authorize() {
            if (!permissions.can(SERVICE_PERMISSION)) {
                throw new AccessDeniedException("Needs " + SERVICE_PERMISSION);
            }
        }
    }

    static final class TestProvider implements ApprovalProvider {
        volatile Function<GateRequest, GateDecision> decide =
                request -> new GateDecision.Hold(POLICY, Duration.ofHours(1), "a platform lead");
        volatile VoteCheck vote = VoteCheck.allow();
        volatile RunCheck run = RunCheck.allow();
        volatile boolean enforcing = true;
        final AtomicInteger decisions = new AtomicInteger();
        final List<GateRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public boolean enforcing() {
            return enforcing;
        }

        @Override
        public GateDecision decide(GateRequest request) {
            decisions.incrementAndGet();
            requests.add(request);
            return decide.apply(request);
        }

        @Override
        public VoteCheck checkVote(HeldOperationView held, Approver approver, Vote vote) {
            return this.vote;
        }

        @Override
        public RunCheck checkRun(HeldOperationView held, Effect now) {
            return run;
        }

        void reset() {
            decide = request -> new GateDecision.Hold(POLICY, Duration.ofHours(1), "a platform lead");
            vote = VoteCheck.allow();
            run = RunCheck.allow();
            enforcing = true;
            decisions.set(0);
            requests.clear();
        }
    }

    /** The provider plugin as the host would hand it to the registry, without a jar. */
    record FakeHandle(String id, ApprovalProvider provider) implements PluginHandle {

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(
                    1,
                    id,
                    id,
                    "1.0.0",
                    new PluginDescriptor.Vendor("Acme", null, null),
                    "desc",
                    "Apache-2.0",
                    null,
                    "com.acme.approvals",
                    "com.acme.Config",
                    12,
                    new PluginDescriptor.Rahdar("2026.01.0", null),
                    List.of(),
                    false,
                    false,
                    PluginDescriptor.Activation.AUTO,
                    null,
                    id,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new PluginDescriptor.ApprovalProvider(APPROVER_PERMISSION),
                    null);
        }

        @Override
        public ApplicationContext applicationContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassLoader classLoader() {
            return getClass().getClassLoader();
        }

        @Override
        public boolean verified() {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Map<String, T> beansOfType(Class<T> type) {
            return type == ApprovalProvider.class ? Map.of("provider", (T) provider) : Map.of();
        }

        @Override
        public <T> T runInPlugin(Callable<T> call) throws Exception {
            return call.call();
        }
    }

    @Bean
    TestState testGateState() {
        return new TestState();
    }

    @Bean
    TestProvider testApprovalProvider() {
        return new TestProvider();
    }

    @Bean
    TestService testGatedService(OperationGate gate, PermissionResolver permissions) {
        return new TestService(gate, permissions);
    }

    @Bean
    GatedOperation<PurgeParams> testPurgeOperation(TestState state, TestService service) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "test.purge";
            }

            @Override
            public int version() {
                return state.purgeVersion;
            }

            @Override
            public Class<PurgeParams> paramsType() {
                return PurgeParams.class;
            }

            @Override
            public Set<Trait> traits(PurgeParams params) {
                return Set.of(Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(PurgeParams params) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(PurgeParams params) {
                return "Purge queue " + params.queue();
            }

            @Override
            public List<DisplayRow> display(PurgeParams params) {
                return List.of(DisplayRow.of("Queue", params.queue()));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of("/secret");
            }

            @Override
            public Effect estimate(PurgeParams params) {
                return new Effect(state.count, "messages", state.etag + ":" + params.queue(), null);
            }

            @Override
            public void replay(PurgeParams params) {
                service.purge(params);
            }
        };
    }

    @Bean
    GatedOperation<TokenParams> testTokenOperation() {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "test.token";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<TokenParams> paramsType() {
                return TokenParams.class;
            }

            @Override
            public Set<Trait> traits(TokenParams params) {
                return Set.of(Trait.ACCESS_CONTROL);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.BY_REQUESTER;
            }

            @Override
            public OperationScope scope(TokenParams params) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(TokenParams params) {
                return "Create token " + params.name();
            }

            @Override
            public List<DisplayRow> display(TokenParams params) {
                return List.of(DisplayRow.of("Name", params.name()));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(TokenParams params) {
                return new Effect(1, "tokens", "token:" + params.name(), null);
            }

            @Override
            public void replay(TokenParams params) {
                throw new UnsupportedOperationException("completed by its requester");
            }
        };
    }

    @Bean
    GatedOperation<BulkParams> testBulkOperation(TestService service) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "test.bulk";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<BulkParams> paramsType() {
                return BulkParams.class;
            }

            @Override
            public Set<Trait> traits(BulkParams params) {
                return Set.of(Trait.BULK, Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(BulkParams params) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(BulkParams params) {
                return "Purge " + params.queues().size() + " queues";
            }

            @Override
            public List<DisplayRow> display(BulkParams params) {
                return List.of(DisplayRow.of("Queues", String.join(", ", params.queues())));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(BulkParams params) {
                return new Effect(params.queues().size(), "queues", String.join(",", params.queues()), null);
            }

            @Override
            public void replay(BulkParams params) {
                service.bulk(params);
            }
        };
    }

    /**
     * A REST entry point over the test service, for the HTTP mapping of the gate's outcomes. Component scanning finds
     * it in every test context, so it asks for the service only when called.
     */
    @RestController
    @RequestMapping("/test-gate")
    static class TestGateController {
        private final ObjectProvider<TestService> service;

        TestGateController(ObjectProvider<TestService> service) {
            this.service = service;
        }

        @PostMapping("/purge")
        Map<String, Object> purge(@RequestBody PurgeParams params) {
            return Map.of("purged", service.getObject().purge(params));
        }
    }
}
