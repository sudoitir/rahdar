package io.github.sudoitir.rahdar.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

/**
 * An operation that takes the name of a queue or an address decides, somewhere on its way, whether the caller
 * may do it to that resource (resource-permissions spec): it calls a resource check ({@code
 * ClusterAccessGuard.requireResource} and its kin, {@code PermissionResolver.can} on a {@code ResourceRef}, a
 * {@code ResourceFilter}), or a method that does. A public method of a service, controller or tool that takes
 * such a name and reaches no check fails here, unless it is listed in {@link #EXEMPT} with the reason.
 *
 * <p>A call to {@link BrokerCommands#run} is not a check: it checks what its command lists as {@code
 * resources}, so the call has to set them. An exemption for a method that no longer takes such a name, or now
 * reaches a check, fails too, so the list cannot outlive its cause.
 */
class ResourceCheckCoverageTest {

    private static final String BASE = "io.github.sudoitir.rahdar.";

    /** A parameter that names a queue or an address, or a subscription or a destination. */
    private static final Pattern NAMES_A_RESOURCE = Pattern.compile(
            "(?i)(.*queue(name|pattern)?|.*address(name|pattern)?|.*subscription(name|pattern)?|.*destination(name|pattern)?|source|target)");

    /** What a request body is called. */
    private static final Pattern REQUEST_BODY = Pattern.compile(".*(Request|Spec|Query)");

    /** A list parameter that names several of them. */
    private static final Pattern NAMES_SOME_RESOURCES = Pattern.compile(
            "(?i)(names|.*queues|.*queuenames|.*addresses|.*addressnames|.*subscriptions|.*destinations)");

    private static final Set<String> STEREOTYPES = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Controller");

    /** Methods that decide for one resource, a pattern of them, or a list of them. */
    private static final Map<String, Set<String>> CHECKS = Map.of(
            ClusterAccessGuard.class.getName(),
            Set.of("requireResource", "requireAll", "requireCreate", "requireOnAll", "mayAll", "mayOnAll"),
            PermissionResolver.class.getName(),
            Set.of("filter", "can", "canOnAll"),
            ResourceFilter.class.getName(),
            Set.of("readable", "allowedActions"),
            OperatorHandoff.class.getName(),
            Set.of("stillHolds"),
            BrokerCommands.class.getName() + "$Command$CommandBuilder",
            Set.of("resources"));

    /** Not traversed: the checks above, and the runner of commands, whose check is what the command lists. */
    private static final Set<String> OPAQUE = Set.of(BrokerCommands.class.getName());

    /**
     * Methods, or whole classes (no method named), that take such a name and need no check of their own, each with
     * why. They are the broker primitives a checked service calls, work Rahdar does for itself, and text.
     */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry(
                    "system.artemis.QueueLifecycleOperations",
                    "broker primitives; QueueLifecycleService checks before it calls them"),
            Map.entry(
                    "system.artemis.DivertOperations",
                    "broker primitives; QueueLifecycleService and RoutingService check before they call them"),
            Map.entry(
                    "feature.brokerconfig.BrokerConfigOperations",
                    "applies declared broker configuration, a cluster-level change that BrokerConfigService gates on"
                            + " config:write and config:apply"),
            Map.entry(
                    "feature.resources.ConnectionOperations",
                    "broker primitives; connection control is a cluster-level permission checked by its callers"),
            Map.entry(
                    "system.artemis.MessageOperations",
                    "broker primitives; MessageService, DlqService and the transfer and bulk runners check before"
                            + " they call them"),
            Map.entry("system.artemis.StagingQueues", "a transfer's own staging queues, made and removed by its run"),
            Map.entry(
                    "system.artemis.AcceptanceProbe", "reads a target's facts for a transfer that was already checked"),
            Map.entry(
                    "platform.scrape.QueueLocator",
                    "finds where a name is on the scraped snapshot or the live nodes; its callers have checked the"
                            + " name"),
            Map.entry(
                    "platform.broker.BrokerMessages",
                    "the SDK's message primitives; the message, queue, SQL and plugin messaging services check"
                            + " before they call them, and the request-reply sampler and capture read for Rahdar"),
            Map.entry(
                    "system.artemis.ArtemisExpiry",
                    "broker primitive behind MessageExpiry; MessageService checks before it calls it"),
            Map.entry(
                    "system.artemis.ArtemisQueueCounters",
                    "broker primitive behind QueueCounters; QueueLifecycleService checks before it calls it"),
            Map.entry(
                    "system.artemis.ArtemisDivertSettings",
                    "broker primitive behind DivertSettings; the divert and routing services check before they call it"),
            Map.entry(
                    "system.artemis.ArtemisQueueSettings",
                    "broker primitive behind QueueSettings; QueueConfigurationReader checks before it calls it"),
            Map.entry(
                    "system.artemis.ArtemisTaps",
                    "broker primitive behind MessageTaps; the capture and plugin messaging services check before they"
                            + " call it"),
            Map.entry(
                    "system.artemis.ArtemisTransferAcceptance",
                    "broker primitive behind TransferAcceptance; the transfer services check before they call it"),
            Map.entry(
                    "system.artemis.ArtemisTransferStaging",
                    "broker primitive behind TransferStaging; the transfer services check before they call it"),
            Map.entry(
                    "platform.scrape.QueueSnapshotUpsert",
                    "the scrape's own bookkeeping, driven by the broker and no caller"),
            Map.entry(
                    "feature.rr.QueueTargetResolver",
                    "the request-reply sampler's own lookup; no caller names a queue"),
            Map.entry("feature.rr.ReplyDestinationResolver", "matches a declared reply pattern for the sampler"),
            Map.entry("feature.rr.RrMetrics.recordCompletion", "records a latency the sampler observed"),
            Map.entry("feature.rr.RrSamplerHealth", "the sampler's own health record"),
            Map.entry(
                    "feature.sql.CaptureCoverage", "measures what capture covers for the index view, after its check"),
            Map.entry("feature.sql.CaptureTapCoverage", "decides what a capture tap covers; no caller names a queue"),
            Map.entry("feature.sql.MessageIndexWriter", "indexes what capture receives, for Rahdar and no caller"),
            Map.entry(
                    "feature.sql.SqlGovernance.forEvaluation",
                    "masks a message for a query that was checked against its queues"),
            Map.entry(
                    "feature.sql.SqlGovernance.forStorage",
                    "masks a message for the index; storage is Rahdar's, and masked for everyone"),
            Map.entry(
                    "platform.governance.ContentPolicy.governForStorage",
                    "masks a message for storage, which is masked for everyone"),
            Map.entry(
                    "platform.mcp.McpRunbookPrompts",
                    "returns a fixed procedure that repeats the name it was given; it reads nothing"),
            Map.entry("system.artemis.BridgeOperations", "broker primitive; RoutingService checks before it calls it"),
            Map.entry(
                    "feature.sql.CaptureConsumer",
                    "drains the captures Rahdar runs for itself; no caller names a queue"),
            Map.entry(
                    "feature.sql.CaptureTap",
                    "installs and removes a capture's taps, after MessageIndexService checked"),
            Map.entry(
                    "kernel.security.TeamIndex",
                    "lists names no team owns, for team administration, which user:admin and team:admin gate"),
            Map.entry(
                    "platform.governance.web.GovernanceController",
                    "masking rules are installation-wide; governance:write is a global permission"),
            Map.entry(
                    "platform.governance.GovernanceRuleService",
                    "masking rules are installation-wide and match addresses by pattern; governance:write is a global"
                            + " permission"),
            Map.entry(
                    "kernel.stream.SseHub.publishAboutAddress",
                    "names what a signal is about; the stream decides per subscriber who may see it"),
            Map.entry(
                    "feature.identitylocal.mfa.SecondFactorService.trustDevice",
                    "'source' is where a sign-in came from"),
            Map.entry(
                    "feature.alerting.OutboundNoticeService.enqueue",
                    "'source' names the module or plugin that sends the notice, not a queue"),
            Map.entry(
                    "kernel.inbox.InboxService.post",
                    "'source' names the module or plugin that posts the notice, not a queue"),
            Map.entry(
                    "kernel.inbox.InboxService.resolve",
                    "'source' names the module or plugin that posted the notice, not a queue"),
            Map.entry(
                    "feature.plugins.messaging.internal.AccessCheck.needsOf",
                    "only lists the needs of a registration; AccessCheck.denial checks them for the acting user"));

    /** What a scan found: the methods it looked at, each with how it fares, and how many check call sites it reaches. */
    private record Subject(String id, boolean checked, boolean composite, boolean coversEachName, int sites) {}

    @Test
    void everyMethodThatTakesAQueueOrAddressNameReachesAResourceCheck() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.sudoitir.rahdar");
        Set<String> unchecked = new TreeSet<>();
        Set<String> exempt = new TreeSet<>();
        for (Subject subject : scan(classes)) {
            String className = subject.id().substring(0, subject.id().lastIndexOf('.'));
            String exemption =
                    EXEMPT.containsKey(subject.id()) ? subject.id() : EXEMPT.containsKey(className) ? className : null;
            boolean passes = subject.checked() && (!subject.composite() || subject.coversEachName());
            if (exemption != null) {
                exempt.add(exemption);
                if (passes) {
                    unchecked.add(exemption + " is exempt but is checked; remove the exemption");
                }
            } else if (!subject.checked()) {
                unchecked.add(subject.id());
            } else if (!passes) {
                unchecked.add(subject.id() + " takes several names but reaches only " + subject.sites()
                        + " check call site(s): check each, or through requireAll");
            }
        }
        assertThat(unchecked)
                .as("check the caller against the queue or address, or add a reasoned exemption")
                .isEmpty();
        assertThat(exempt)
                .as("an exemption for a method that takes no such name any more")
                .containsExactlyInAnyOrderElementsOf(EXEMPT.keySet());
    }

    @Test
    void theScanSeesTheMethodsItIsMeantToGuardSoItCannotPassByLookingAtNothing() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.sudoitir.rahdar");
        List<Subject> subjects = scan(classes);

        assertThat(subjects.stream().map(Subject::id))
                .contains(
                        "feature.messages.MessageService.purge",
                        "feature.messages.MessageService.execute",
                        "feature.resources.QueueLifecycleService.deleteQueue",
                        "feature.transfer.TransferService.preview",
                        "feature.sql.MessageIndexService.create");
        assertThat(subjects)
                .filteredOn(Subject::composite)
                .extracting(Subject::id)
                .contains("feature.messages.MessageService.execute", "feature.transfer.TransferService.preview");
        assertThat(subjects)
                .filteredOn(s -> s.id().equals("feature.messages.MessageService.purge"))
                .singleElement()
                .satisfies(s -> assertThat(s.checked()).isTrue());
    }

    @Test
    void aMethodThatChecksNothingIsFlaggedAndOneThatChecksIsNot() {
        List<Subject> subjects = scan(new ClassFileImporter().importClasses(Unguarded.class, Guarded.class));

        assertThat(subjects)
                .extracting(Subject::id, Subject::checked)
                .contains(
                        Assertions.tuple("architecture.ResourceCheckCoverageTest$Unguarded.purge", false),
                        Assertions.tuple("architecture.ResourceCheckCoverageTest$Guarded.purge", true));
        assertThat(subjects)
                .filteredOn(s -> s.id().endsWith("Guarded.copy"))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.composite()).isTrue();
                    assertThat(s.coversEachName()).isFalse();
                });
    }

    @Service
    static class Unguarded {
        public void purge(String queueName) {
            // Deliberately checks nothing: the scan must report this entry point as unguarded.
        }
    }

    @Service
    static class Guarded {
        private ClusterAccessGuard guard;

        public void purge(UUID cluster, String queueName) {
            guard.requireResource(cluster, ResourceRef.subscription(queueName), "subscription:purge");
        }

        /** Two names, one check: the second is never looked at. */
        public void copy(UUID cluster, String sourceQueue, String targetQueue) {
            guard.requireResource(cluster, ResourceRef.subscription(sourceQueue), "subscription:read");
            Objects.requireNonNull(targetQueue, "named, and used, but never checked");
        }
    }

    private static List<Subject> scan(Iterable<JavaClass> classes) {
        List<Subject> subjects = new ArrayList<>();
        for (JavaClass c : classes) {
            if (!isEntryPoint(c)) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                int names = resourceNames(m);
                if (!m.getModifiers().contains(JavaModifier.PUBLIC)
                        || m.getModifiers().contains(JavaModifier.SYNTHETIC)
                        || names == 0) {
                    continue;
                }
                Set<String> sites = checkSitesReachedFrom(m);
                boolean covers =
                        // requireAll and a command's resources take every name at once.
                        sites.stream().anyMatch(site -> site.startsWith("requireAll@") || site.startsWith("resources@"))
                                || sites.size() >= names;
                subjects.add(new Subject(
                        c.getName().substring(BASE.length()) + "." + m.getName(),
                        !sites.isEmpty(),
                        names > 1,
                        covers,
                        sites.size()));
            }
        }
        return subjects;
    }

    private static boolean isEntryPoint(JavaClass c) {
        return c.getName().startsWith(BASE)
                && c.getAnnotations().stream()
                        .anyMatch(a -> STEREOTYPES.contains(a.getRawType().getName()));
    }

    /**
     * How many queue or address names the method is given: strings and lists of strings named for them, and the same
     * among the components of a request record of this code base.
     */
    private static int resourceNames(JavaMethod method) {
        int names = 0;
        for (Parameter p : method.reflect().getParameters()) {
            if (namesAResource(p.getType(), p.getParameterizedType(), p.getName())) {
                names++;
            }
            if (isRequestBody(p.getType())) {
                for (RecordComponent component : p.getType().getRecordComponents()) {
                    if (namesAResource(component.getType(), component.getGenericType(), component.getName())) {
                        names++;
                    }
                }
            }
        }
        return names;
    }

    /** A request a caller sends: a record of this code base named for one. */
    private static boolean isRequestBody(Class<?> type) {
        return type.isRecord()
                && type.getName().startsWith(BASE)
                && REQUEST_BODY.matcher(type.getSimpleName()).matches();
    }

    private static boolean namesAResource(Class<?> type, Type generic, String name) {
        if (type == String.class) {
            return NAMES_A_RESOURCE.matcher(name).matches();
        }
        return List.class.isAssignableFrom(type)
                && generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] == String.class
                && NAMES_SOME_RESOURCES.matcher(name).matches();
    }

    /** The check call sites reached from the method through calls, method references and lambdas of the code base. */
    private static Set<String> checkSitesReachedFrom(JavaMethod start) {
        Set<String> sites = new TreeSet<>();
        Deque<JavaCodeUnit> pending = new ArrayDeque<>(List.of(start));
        Set<JavaCodeUnit> seen = new HashSet<>(pending);
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.poll();
            List<JavaAccess<?>> accesses = new ArrayList<>(unit.getMethodCallsFromSelf());
            accesses.addAll(unit.getMethodReferencesFromSelf());
            for (JavaAccess<?> access : accesses) {
                if (isCheck(access)) {
                    sites.add(access.getName() + "@" + access.getOrigin().getFullName() + ":" + access.getLineNumber());
                }
                for (JavaCodeUnit callee : targetsOf(access)) {
                    if (seen.add(callee)) {
                        pending.add(callee);
                    }
                }
            }
            // The body of a lambda is a method of its own that nothing calls by name.
            for (JavaMethod lambda : unit.getOwner().getMethods()) {
                if (lambda.getName().startsWith("lambda$" + unit.getName() + "$") && seen.add(lambda)) {
                    pending.add(lambda);
                }
            }
        }
        return sites;
    }

    private static boolean isCheck(JavaAccess<?> access) {
        Set<String> names = CHECKS.get(access.getTargetOwner().getName());
        if (names == null || !names.contains(access.getName())) {
            return false;
        }
        // The resolver's can(cluster, permission) asks about the cluster; only the overloads on a resource count.
        return !access.getTargetOwner().getName().equals(PermissionResolver.class.getName())
                || !access.getName().equals("can")
                || ((JavaCodeUnitAccess<?>) access)
                        .getTarget().getRawParameterTypes().stream()
                                .anyMatch(t -> t.getName().equals(ResourceRef.class.getName()));
    }

    /** The code a call can run: the method itself, and what implements it when it is declared abstractly. */
    private static List<JavaCodeUnit> targetsOf(JavaAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        if (!owner.getName().startsWith(BASE) || OPAQUE.contains(owner.getName())) {
            return List.of();
        }
        List<JavaCodeUnit> targets = new ArrayList<>();
        for (JavaClass type : concreteTypes(owner)) {
            type.getMethods().stream()
                    .filter(m -> m.getName().equals(access.getName()))
                    .forEach(targets::add);
        }
        return targets;
    }

    private static List<JavaClass> concreteTypes(JavaClass owner) {
        List<JavaClass> types = new ArrayList<>(List.of(owner));
        types.addAll(owner.getAllSubclasses());
        return types;
    }
}
