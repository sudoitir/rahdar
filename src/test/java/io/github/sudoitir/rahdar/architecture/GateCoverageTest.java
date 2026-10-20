package io.github.sudoitir.rahdar.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approval gate covers what it claims to (ADR-0179):
 *
 * <ul>
 *   <li>every {@link Gated} method calls {@link OperationGate#run};
 *   <li>every gated type has exactly one {@link GatedOperation} bean, and every bean's type is gated somewhere, each
 *       with a parameters record of its own;
 *   <li>an endpoint documents the held {@code 202} ({@link HeldResponse}) exactly when it reaches a gated method;
 *   <li>a mutating endpoint that calls a service of the catalogue (a class with a gated method) reaches a gated
 *       method, unless it is listed in {@link #UNGATED} with the reason.
 * </ul>
 *
 * <p>"Reaches" follows calls, method references and lambdas through the code base, from the endpoint method. It is a
 * static over-approximation: it shows that an endpoint can get to the gate, and the integration tests of each module
 * show that it does.
 */
class GateCoverageTest extends PostgresIntegrationTest {

    private static final String BASE = "io.github.sudoitir.rahdar.";

    private static final Set<String> MUTATIONS = Set.of(
            PostMapping.class.getName(),
            PutMapping.class.getName(),
            PatchMapping.class.getName(),
            DeleteMapping.class.getName());

    private static final Set<String> MAPPINGS = Set.of(
            GetMapping.class.getName(),
            PostMapping.class.getName(),
            PutMapping.class.getName(),
            PatchMapping.class.getName(),
            DeleteMapping.class.getName());

    /** Mutating endpoints of the catalogue's services that are not gated, and why (design decision 9). */
    private static final Map<String, String> UNGATED = Map.ofEntries(
            Map.entry(
                    "feature.apitokens.web.TokensController.revoke",
                    "revoking one's own token only takes access away from oneself; token.revoke-any gates another's"),
            Map.entry(
                    "feature.apitokens.web.TokensController.rotate",
                    "replaces one's own token with one of the same grants; it grants nothing new"),
            Map.entry("feature.bulk.web.BulkController.preview", "a preview changes nothing; bulk.execute runs it"),
            Map.entry("feature.bulk.web.BulkController.stop", "only ends a run that was allowed or approved"),
            Map.entry("feature.messages.web.MessageController.send", "adds a message and removes nothing"),
            Map.entry(
                    "feature.plugins.web.PluginAdminController.checkUpdates",
                    "reads the update source and installs nothing"),
            Map.entry(
                    "feature.plugins.web.PluginAdminController.discard",
                    "drops a pending upload, which installs nothing"),
            Map.entry(
                    "feature.plugins.web.PluginAdminController.downloadUpdate",
                    "stores an update as a pending upload; plugin.activate-upload installs it"),
            Map.entry(
                    "feature.plugins.web.PluginAdminController.restart",
                    "restarts Rahdar for the plugins already changed through the gate"),
            Map.entry(
                    "feature.plugins.web.PluginAdminController.upload",
                    "stores a pending upload; plugin.activate-upload installs it"),
            Map.entry("feature.resources.web.QueueLifecycleController.createAddress", "creates and removes nothing"),
            Map.entry("feature.resources.web.QueueLifecycleController.createQueue", "creates and removes nothing"),
            Map.entry(
                    "feature.resources.web.QueueLifecycleController.pauseQueue",
                    "loses no message; pausing many queues at once is bulk.execute"),
            Map.entry(
                    "feature.resources.web.QueueLifecycleController.resumeQueue",
                    "loses no message; resuming many queues at once is bulk.execute"),
            Map.entry(
                    "feature.resources.web.QueueLifecycleController.resetCounter",
                    "resets a statistic and touches no message"),
            Map.entry(
                    "feature.resources.web.QueueLifecycleController.updateQueue",
                    "changes a queue's attributes and loses no message"),
            Map.entry(
                    "feature.routing.web.DivertController.createDivert",
                    "routing is outside the catalogue; a divert copies or moves messages and deletes none"),
            Map.entry(
                    "feature.routing.web.DivertController.deleteDivert",
                    "routing is outside the catalogue; removing a divert deletes no message"),
            Map.entry(
                    "feature.transfer.web.TransferController.preview",
                    "a preview changes nothing; transfer.execute runs it"),
            Map.entry(
                    "feature.transfer.web.TransferController.resume",
                    "continues a run that was allowed or approved, with the same plan"),
            Map.entry(
                    "feature.transfer.web.TransferController.returnOrphan",
                    "moves staged messages back to the queue they came from"),
            Map.entry(
                    "feature.transfer.web.TransferController.returnToSource",
                    "moves a run's messages back to the queue they came from"),
            Map.entry("feature.transfer.web.TransferController.stop", "only ends a run that was allowed or approved"),
            Map.entry(
                    "kernel.settings.web.SettingsController.preview",
                    "says whether a change set would run, be held or be denied, and changes nothing"));

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.sudoitir.rahdar");

    @Autowired
    List<GatedOperation<?>> operations;

    @Test
    void everyGatedMethodCallsTheGate() {
        List<JavaMethod> gated = gatedMethods();

        assertThat(gated).as("the scan sees the gated methods").hasSizeGreaterThan(40);
        assertThat(gated.stream()
                        .filter(m -> !callsTheGate(m))
                        .map(GateCoverageTest::id)
                        .toList())
                .as("a @Gated method that never calls OperationGate.run")
                .isEmpty();
    }

    @Test
    void everyGatedTypeHasOneOperationAndEachOperationItsOwnParameters() {
        Set<String> gatedTypes = gatedMethods().stream()
                .map(m -> m.reflect().getAnnotation(Gated.class).value())
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, List<String>> beansByType = new TreeMap<>();
        Map<String, List<String>> typesByParams = new TreeMap<>();
        for (GatedOperation<?> operation : operations) {
            beansByType
                    .computeIfAbsent(operation.type(), t -> new ArrayList<>())
                    .add(operation.getClass().getName());
            typesByParams
                    .computeIfAbsent(operation.paramsType().getName(), t -> new ArrayList<>())
                    .add(operation.type());
        }

        assertThat(beansByType.keySet())
                .as("the operation types with a bean are exactly the gated ones")
                .containsExactlyInAnyOrderElementsOf(gatedTypes);
        assertThat(beansByType)
                .as("a gated type with more than one operation bean")
                .allSatisfy((type, beans) -> assertThat(beans).as(type).hasSize(1));
        assertThat(typesByParams)
                .as("a parameters record shared by several operation types")
                .allSatisfy((params, types) -> assertThat(types).as(params).hasSize(1));
        assertThat(operations)
                .as("parameters are a record, which the gate hashes and replays")
                .allSatisfy(
                        o -> assertThat(o.paramsType().isRecord()).as(o.type()).isTrue());
    }

    @Test
    void anEndpointDocumentsTheHeldResponseExactlyWhenItReachesTheGate() {
        Set<String> gated = gatedMethods().stream().map(GateCoverageTest::id).collect(Collectors.toSet());
        List<String> undocumented = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (JavaMethod endpoint : endpoints()) {
            boolean reaches = reaches(endpoint, gated);
            boolean documented = endpoint.isAnnotatedWith(HeldResponse.class);
            if (reaches && !documented) {
                undocumented.add(id(endpoint));
            } else if (documented && !reaches) {
                stale.add(id(endpoint));
            }
        }

        assertThat(undocumented)
                .as("an endpoint that can be held but does not declare @HeldResponse")
                .isEmpty();
        assertThat(stale)
                .as("an endpoint that declares @HeldResponse but reaches no gated method")
                .isEmpty();
    }

    @Test
    void aMutatingEndpointOfTheCatalogueReachesTheGateOrSaysWhyNot() {
        Set<String> gated = gatedMethods().stream().map(GateCoverageTest::id).collect(Collectors.toSet());
        Set<String> catalogue =
                gatedMethods().stream().map(m -> m.getOwner().getName()).collect(Collectors.toSet());
        Set<String> ungated = new TreeSet<>();
        Set<String> needlessly = new TreeSet<>();
        for (JavaMethod endpoint : endpoints()) {
            if (!isMutation(endpoint) || !callsInto(endpoint, catalogue)) {
                continue;
            }
            boolean reaches = reaches(endpoint, gated);
            String name = id(endpoint);
            if (!reaches && !UNGATED.containsKey(name)) {
                ungated.add(name);
            } else if (reaches && UNGATED.containsKey(name)) {
                needlessly.add(name);
            }
        }

        assertThat(ungated)
                .as("a mutating endpoint of a gated service that reaches no gated method: gate it, or list it in"
                        + " UNGATED with the reason")
                .isEmpty();
        assertThat(needlessly).as("listed in UNGATED but reaches the gate").isEmpty();
    }

    private static List<JavaMethod> gatedMethods() {
        return CLASSES.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Gated.class))
                .toList();
    }

    private static List<JavaMethod> endpoints() {
        return CLASSES.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> MAPPINGS.contains(a.getRawType().getName())))
                .toList();
    }

    private static boolean isMutation(JavaMethod endpoint) {
        return endpoint.getAnnotations().stream()
                .anyMatch(a -> MUTATIONS.contains(a.getRawType().getName()));
    }

    private static boolean callsTheGate(JavaMethod method) {
        return bodyOf(method).stream()
                .flatMap(unit -> unit.getMethodCallsFromSelf().stream())
                .anyMatch(call -> call.getTargetOwner().isAssignableTo(OperationGate.class)
                        && call.getName().equals("run"));
    }

    /** Whether the endpoint calls a method of one of {@code owners} itself, its lambdas included. */
    private static boolean callsInto(JavaMethod endpoint, Set<String> owners) {
        return bodyOf(endpoint).stream()
                .flatMap(unit -> accessesOf(unit).stream())
                .anyMatch(access -> owners.contains(access.getTargetOwner().getName()));
    }

    /** Whether one of {@code targets} (by {@link #id}) is reached from {@code start}. */
    private static boolean reaches(JavaMethod start, Set<String> targets) {
        Deque<JavaCodeUnit> pending = new ArrayDeque<>(List.of(start));
        Set<JavaCodeUnit> seen = new HashSet<>(pending);
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.poll();
            if (unit != start && unit instanceof JavaMethod method && targets.contains(id(method))) {
                return true;
            }
            for (JavaAccess<?> access : accessesOf(unit)) {
                for (JavaCodeUnit callee : targetsOf(access)) {
                    if (seen.add(callee)) {
                        pending.add(callee);
                    }
                }
            }
            for (JavaCodeUnit lambda : lambdasOf(unit)) {
                if (seen.add(lambda)) {
                    pending.add(lambda);
                }
            }
        }
        return false;
    }

    /** The method and the lambdas written in it, which javac compiles to methods of their own. */
    private static List<JavaCodeUnit> bodyOf(JavaCodeUnit method) {
        List<JavaCodeUnit> body = new ArrayList<>(List.of(method));
        for (int i = 0; i < body.size(); i++) {
            body.addAll(lambdasOf(body.get(i)));
        }
        return body;
    }

    private static List<JavaCodeUnit> lambdasOf(JavaCodeUnit unit) {
        return unit.getOwner().getMethods().stream()
                .filter(m -> m.getName().startsWith("lambda$" + unit.getName() + "$"))
                .map(JavaCodeUnit.class::cast)
                .toList();
    }

    private static List<JavaAccess<?>> accessesOf(JavaCodeUnit unit) {
        List<JavaAccess<?>> accesses = new ArrayList<>(unit.getMethodCallsFromSelf());
        accesses.addAll(unit.getMethodReferencesFromSelf());
        return accesses;
    }

    /** The code a call can run: the method itself, and what implements it when it is declared abstractly. */
    private static List<JavaCodeUnit> targetsOf(JavaAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        // An operation's replay calls the gated method again, after an approval and never inside a request.
        if (!owner.getName().startsWith(BASE) || owner.isAssignableTo(GatedOperation.class)) {
            return List.of();
        }
        List<JavaClass> types = new ArrayList<>(List.of(owner));
        types.addAll(owner.getAllSubclasses());
        List<JavaCodeUnit> targets = new ArrayList<>();
        for (JavaClass type : types) {
            type.getMethods().stream()
                    .filter(m -> m.getName().equals(access.getName()))
                    .forEach(targets::add);
        }
        return targets;
    }

    private static String id(JavaMethod method) {
        return method.getOwner().getName().substring(BASE.length()) + "." + method.getName();
    }
}
