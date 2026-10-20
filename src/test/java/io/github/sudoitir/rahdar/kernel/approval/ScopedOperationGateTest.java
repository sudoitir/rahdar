package io.github.sudoitir.rahdar.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperationRegistry;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class ScopedOperationGateTest {

    record Params(String name) {}

    private final OperationGate engine = mock(OperationGate.class);
    private final GatedOperationRegistry registry = mock(GatedOperationRegistry.class);

    @SuppressWarnings("unchecked")
    private void registered(String type) {
        GatedOperation operation = mock(GatedOperation.class);
        when(operation.type()).thenReturn(type);
        when(registry.forParams(Params.class)).thenReturn(Optional.of(operation));
    }

    @Test
    void aRahdarTypeIsRefusedAndNeverReachesTheEngine() {
        registered("subscription.purge");
        OperationGate gate = new ScopedOperationGate(engine, registry, "acme");
        Operation operation = Operation.of(new Params("a"));

        assertThatThrownBy(() -> gate.run(operation, () -> "ran"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subscription.purge");
        assertThatThrownBy(() -> gate.preview(operation)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(engine);
    }

    @Test
    void anotherPluginsTypeIsRefused() {
        registered("other:thing");
        OperationGate gate = new ScopedOperationGate(engine, registry, "acme");
        Operation operation = Operation.of(new Params("a"));

        assertThatThrownBy(() -> gate.run(operation, () -> "ran")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(engine);
    }

    @Test
    void theOwnTypeIsHandedToTheEngine() {
        registered("acme:thing");
        Operation operation = Operation.of(new Params("a"));
        when(engine.run(ArgumentMatchers.eq(operation), ArgumentMatchers.any())).thenReturn("held");

        assertThat(new ScopedOperationGate(engine, registry, "acme").run(operation, () -> "ran"))
                .isEqualTo("held");
    }
}
