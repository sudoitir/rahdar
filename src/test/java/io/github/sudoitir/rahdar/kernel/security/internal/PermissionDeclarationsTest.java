package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.app.RahdarFeatures;
import io.github.sudoitir.rahdar.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import java.util.List;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.security.access.prepost.PreAuthorize;

/** The permission declaration check (operational-health spec): each mismatch kind, and healthy. */
class PermissionDeclarationsTest {

    static class Guarded {
        @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.Permissions).USER_ADMIN)")
        public void constant() {
            // Only the annotation is read.
        }

        @PreAuthorize("@perm.can(#clusterId, 'subscription:read') and @perm.can(#clusterId, #permission)")
        public void literalWithCluster(String clusterId, String permission) {
            // Only the annotation is read.
        }

        @PreAuthorize("@perm.can('*')")
        public void wildcard() {
            // Only the annotation is read.
        }
    }

    private final FeatureRegistry registry = mock(FeatureRegistry.class);
    private final PermissionDeclarations declarations = new PermissionDeclarations(registry);

    private void catalogue(CatalogueEntry... entries) {
        when(registry.catalogue()).thenReturn(List.of(entries));
    }

    private static CatalogueEntry entry(String action, PermissionScope scope, String... requires) {
        return new CatalogueEntry(
                action,
                "Does " + action,
                "security",
                "Security",
                scope,
                scope == PermissionScope.RESOURCE ? Set.of(ResourceType.SUBSCRIPTION) : Set.of(),
                Set.of(requires));
    }

    private void rahdarBeans() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(Guarded.class);
        context.refresh();
        ApplicationReadyEvent ready = mock(ApplicationReadyEvent.class);
        when(ready.getApplicationContext()).thenReturn(context);
        declarations.onReady(ready);
    }

    @Test
    void guardsReadConstantsAndLiteralsAndSkipVariablesAndWildcards() {
        assertThat(GuardPermissions.of(Guarded.class, getClass().getClassLoader()))
                .extracting(GuardPermissions.GuardRef::permission, GuardPermissions.GuardRef::withCluster)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(Permissions.USER_ADMIN, false), Tuple.tuple("subscription:read", true));
    }

    @Test
    void healthyWhenEveryGuardedPermissionIsRegisteredAndDescribed() {
        catalogue(
                entry(Permissions.USER_ADMIN, PermissionScope.GLOBAL),
                entry("subscription:read", PermissionScope.RESOURCE));
        rahdarBeans();

        assertThat(declarations.mismatches()).isEmpty();
    }

    @Test
    void reportsAGuardNamingAnUnregisteredPermission() {
        catalogue(entry(Permissions.USER_ADMIN, PermissionScope.GLOBAL));
        rahdarBeans();

        assertThat(declarations.mismatches())
                .singleElement()
                .asString()
                .contains("'subscription:read'", "Guarded#literalWithCluster", "not in the permission catalogue");
    }

    @Test
    void reportsAGlobalPermissionCheckedAgainstACluster() {
        catalogue(
                entry(Permissions.USER_ADMIN, PermissionScope.GLOBAL),
                entry("subscription:read", PermissionScope.GLOBAL));
        rahdarBeans();

        assertThat(declarations.mismatches()).singleElement().asString().contains("global scope");
    }

    @Test
    void reportsARequiredPermissionThatIsNotInTheCatalogue() {
        catalogue(entry("subscription:purge", PermissionScope.RESOURCE, "subscription:read"));

        assertThat(declarations.mismatches())
                .singleElement()
                .asString()
                .contains("'subscription:purge'", "requires 'subscription:read'", "not in the permission catalogue");
    }

    @Test
    void reportsRequirementsThatFormACycleOnce() {
        catalogue(
                entry("a:x", PermissionScope.CLUSTER, "a:y"),
                entry("a:y", PermissionScope.CLUSTER, "a:z"),
                entry("a:z", PermissionScope.CLUSTER, "a:x"),
                entry("a:ok", PermissionScope.CLUSTER, "a:x"));

        assertThat(declarations.mismatches())
                .singleElement()
                .asString()
                .contains("require each other", "a:x -> a:y -> a:z -> a:x");
    }

    @Test
    void reportsAResourcePermissionThatNamesNoKind() {
        catalogue(new CatalogueEntry(
                "subscription:read", "Read", "queues", "Queues", PermissionScope.RESOURCE, Set.of(), Set.of()));

        assertThat(declarations.mismatches()).singleElement().asString().contains("names no kind");
    }

    @Test
    void theCoreCatalogueIsConsistent() {
        catalogue(RahdarFeatures.descriptors().stream()
                .flatMap(d -> d.permissions().stream()
                        .map(p -> new CatalogueEntry(
                                p.action(), p.label(), d.id(), d.title(), p.scope(), p.resourceTypes(), p.requires())))
                .toArray(CatalogueEntry[]::new));

        assertThat(declarations.mismatches()).isEmpty();
    }

    @Test
    void reportsAnUndescribedEntry() {
        catalogue(new CatalogueEntry(
                "subscription:read",
                " ",
                "queues",
                "Queues",
                PermissionScope.RESOURCE,
                Set.of(ResourceType.SUBSCRIPTION),
                Set.of()));

        assertThat(declarations.mismatches()).singleElement().asString().contains("no description");
    }

    @Test
    void reportsAPluginManifestReferenceUntilThePluginDeactivates() {
        catalogue(entry("acme:read", PermissionScope.CLUSTER));
        PluginDescriptor descriptor = mock(PluginDescriptor.class);
        when(descriptor.id()).thenReturn("acme");
        when(descriptor.basePackage()).thenReturn("com.acme");
        when(descriptor.mcpTools()).thenReturn(List.of());
        when(descriptor.metrics())
                .thenReturn(List.of(new PluginDescriptor.Metric("acme:count", "Count", "count", "queue", "acme:gone")));
        PluginHandle handle = mock(PluginHandle.class);
        when(handle.id()).thenReturn("acme");
        when(handle.descriptor()).thenReturn(descriptor);
        when(handle.applicationContext()).thenReturn(new GenericApplicationContext());
        when(handle.classLoader()).thenReturn(getClass().getClassLoader());

        declarations.attach(handle);
        assertThat(declarations.mismatches()).singleElement().asString().contains("'acme:gone'", "plugin acme");

        declarations.detach(handle);
        assertThat(declarations.mismatches()).isEmpty();
    }
}
