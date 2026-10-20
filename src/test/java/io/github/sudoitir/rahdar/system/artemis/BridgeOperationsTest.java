package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The read-back a bridge creation rests on. The broker answers 200 for a document it
 * ignores entirely, so the only evidence is what {@code BridgeControl} reports — and
 * the names it reports depend on the declared concurrency.
 */
class BridgeOperationsTest {

    private static BridgeRow deployed(String name, Map<String, String> transformerProperties) {
        return new BridgeRow(
                null,
                null,
                name,
                "orders.out",
                "orders.in",
                null,
                null,
                transformerProperties.isEmpty() ? null : "com.example.Rewrite",
                transformerProperties,
                List.of("remote-a"),
                2000L,
                1.0,
                2000L,
                -1,
                0L,
                0L,
                true,
                true,
                true,
                false);
    }

    private static Map<String, Object> config(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "orders-out");
        m.put("queue-name", "orders.out");
        m.put("forwarding-address", "orders.in");
        m.put("static-connectors", List.of("remote-a"));
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @Test
    void aConcurrencyOfOneOrUnsetDeploysTheBareName() {
        assertThat(BridgeOperations.instanceNames("orders-out", 1)).containsExactly("orders-out");
        assertThat(BridgeOperations.instanceNames(config())).containsExactly("orders-out");
    }

    @Test
    void aConcurrencyAboveOneDeploysOneInstancePerWorker() {
        assertThat(BridgeOperations.instanceNames("orders-out", 3))
                .containsExactly("orders-out-0", "orders-out-1", "orders-out-2");
        assertThat(BridgeOperations.instanceNames(config("concurrency", 2)))
                .containsExactly("orders-out-0", "orders-out-1");
    }

    @Test
    void differencesNameOnlyTheFieldsTheBrokerReportsAndTheDocumentSet() {
        BridgeRow row = deployed("orders-out", Map.of());

        assertThat(BridgeOperations.differences(row, config())).isEmpty();
        // Unreported fields are declared and never compared: an absence of evidence is
        // not evidence of agreement (ADR-0090 D4a).
        assertThat(BridgeOperations.differences(
                        row, config("confirmation-window-size", 1024, "routing-type", "ANYCAST", "client-id", "x")))
                .isEmpty();
        assertThat(BridgeOperations.differences(
                        row, config("forwarding-address", "elsewhere", "ha", true, "retry-interval", 500L)))
                .containsExactly("forwarding-address", "ha", "retry-interval");
    }

    @Test
    void aTransformerIsComparedLikeAnyOtherFieldBecauseTheBrokerReportsItInFull() {
        Map<String, Object> withTransformer = config(
                "transformer-configuration",
                Map.of("class-name", "com.example.Rewrite", "properties", Map.of("k", "v")));

        assertThat(BridgeOperations.differences(deployed("orders-out", Map.of("k", "v")), withTransformer))
                .isEmpty();
        assertThat(BridgeOperations.differences(deployed("orders-out", Map.of("k", "other")), withTransformer))
                .containsExactly("transformer properties");
        assertThat(BridgeOperations.differences(deployed("orders-out", Map.of()), withTransformer))
                .containsExactly("transformer class-name");
        assertThat(BridgeOperations.differences(deployed("orders-out", Map.of("k", "v")), config()))
                .containsExactly("transformer class-name");
    }
}
