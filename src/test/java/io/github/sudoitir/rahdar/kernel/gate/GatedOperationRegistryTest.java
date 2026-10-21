package io.github.sudoitir.rahdar.kernel.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class GatedOperationRegistryTest {

    record Purge(String queue) {}

    record Rename(String from) {}

    record Other(String x) {}

    private record Op<P extends Record>(String type, Class<P> paramsType) implements GatedOperation<P> {

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Set<Trait> traits(P params) {
            return Set.of();
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.ON_APPROVAL;
        }

        @Override
        public OperationScope scope(P params) {
            return OperationScope.GLOBAL;
        }

        @Override
        public String summary(P params) {
            return type;
        }

        @Override
        public List<DisplayRow> display(P params) {
            return List.of();
        }

        @Override
        public Set<String> redactedPaths() {
            return Set.of();
        }

        @Override
        public Effect estimate(P params) {
            return new Effect(1, "item", "k", null);
        }

        @Override
        public void replay(P params) {
            // the registry never replays; only its lookups are under test
        }
    }

    private static GatedOperationRegistry registry(GatedOperation<?>... rahdar) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (GatedOperation<?> op : rahdar) {
            factory.registerSingleton(op.type(), op);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        ObjectProvider<GatedOperation<?>> provider = (ObjectProvider) factory.getBeanProvider(GatedOperation.class);
        GatedOperationRegistry registry = new GatedOperationRegistry(provider);
        registry.afterSingletonsInstantiated();
        return registry;
    }

    private static PluginHandle plugin(String id, GatedOperation<?>... ops) throws Exception {
        PluginHandle handle = mock(PluginHandle.class);
        when(handle.id()).thenReturn(id);
        Map<String, GatedOperation> beans = new LinkedHashMap<>();
        for (GatedOperation<?> op : ops) {
            beans.put(op.type(), op);
        }
        when(handle.beansOfType(GatedOperation.class)).thenReturn((Map) beans);
        when(handle.runInPlugin(any())).thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        return handle;
    }

    @Test
    void rahdarAndPluginOperationsAreFoundByTypeAndByParameters() throws Exception {
        var registry = registry(new Op<>("subscription.purge", Purge.class));
        registry.attach(plugin("acme-notes", new Op<>("acme-notes:rename", Rename.class)));

        assertThat(registry.forType("subscription.purge")).isPresent();
        assertThat(registry.forType("acme-notes:rename")).isPresent();
        assertThat(registry.forParams(Rename.class))
                .get()
                .extracting(GatedOperation::type)
                .isEqualTo("acme-notes:rename");
        assertThat(registry.forParams(Other.class)).isEmpty();
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void aPluginTypeOutsideItsNamespaceFailsTheAttach() throws Exception {
        var registry = registry();

        PluginHandle rahdarType = plugin("acme-notes", new Op<>("subscription.purge", Purge.class));
        PluginHandle otherNamespace = plugin("acme-notes", new Op<>("other-plugin:x", Purge.class));

        assertThatThrownBy(() -> registry.attach(rahdarType))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acme-notes:");
        assertThatThrownBy(() -> registry.attach(otherNamespace)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void aRahdarTypeWithAColonIsRefusedAtBoot() {
        Op<Rename> namespaced = new Op<>("acme-notes:rename", Rename.class);

        assertThatThrownBy(() -> registry(namespaced)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDuplicateTypeOrParametersFailsTheAttachAndChangesNothing() throws Exception {
        var registry = registry(new Op<>("subscription.purge", Purge.class));

        PluginHandle sharesParameters =
                plugin("acme-notes", new Op<>("acme-notes:a", Rename.class), new Op<>("acme-notes:b", Purge.class));

        assertThatThrownBy(() -> registry.attach(sharesParameters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("share the parameters");
        assertThat(registry.all()).hasSize(1);

        registry.attach(plugin("acme-notes", new Op<>("acme-notes:a", Rename.class)));
        PluginHandle duplicateType = plugin("acme-other", new Op<>("acme-other:a", Rename.class));
        assertThatThrownBy(() -> registry.attach(duplicateType)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void anUpgradeReplacesThePluginsOperationsAndTheOldDetachLeavesTheNewOnes() throws Exception {
        var registry = registry();
        PluginHandle old = plugin("acme-notes", new Op<>("acme-notes:a", Rename.class));
        PluginHandle next = plugin("acme-notes", new Op<>("acme-notes:b", Rename.class));

        registry.attach(old);
        registry.attach(next);
        registry.detach(old);

        assertThat(registry.forType("acme-notes:a")).isEmpty();
        assertThat(registry.forType("acme-notes:b")).isPresent();

        registry.detach(next);
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void aPluginsOperationRunsInsideThePlugin() throws Exception {
        var registry = registry();
        PluginHandle handle = plugin("acme-notes", new Op<>("acme-notes:a", Rename.class));
        registry.attach(handle);

        registry.forParams(Rename.class).orElseThrow().estimate(new Rename("x"));

        Mockito.verify(handle).runInPlugin(any());
    }
}
