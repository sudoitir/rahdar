package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceFinding;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceInput;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.BatchVerdict;
import io.github.sudoitir.rahdar.platform.broker.TransferStaging.TransferSource;
import io.github.sudoitir.rahdar.system.artemis.AcceptanceProbe.Facts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What the target's queue says of itself is read through the node's client; the rules are {@link TargetAcceptance}'s. */
class ArtemisTransferAcceptanceTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final ArtemisNodes nodes = mock(ArtemisNodes.class);
    private final AcceptanceProbe probe = mock(AcceptanceProbe.class);
    private final ArtemisNode client = mock(ArtemisNode.class);
    private final ArtemisTransferAcceptance acceptance = new ArtemisTransferAcceptance(nodes, probe);

    private static Facts facts(String addressSettingsJson) {
        return new Facts(
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                addressSettingsJson == null
                        ? null
                        : JsonMapper.builder().build().readTree(addressSettingsJson),
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void theQueueIsReadOnTheNodesClientByItsAddressNameAndRoutingType() {
        Facts read = facts(null);
        when(nodes.on(CLUSTER, NODE)).thenReturn(client);
        when(probe.read(client, "orders.addr", "orders", "ANYCAST")).thenReturn(read);

        assertThat(acceptance.read(CLUSTER, NODE, new TransferSource("orders.addr", "orders", "ANYCAST")))
                .isSameAs(read);
    }

    @Test
    void aTargetThatDropsWhenFullIsRefusedBeforeABatchWithTheSnippetThatFixesIt() {
        BatchVerdict verdict =
                acceptance.batch(facts("{\"addressFullMessagePolicy\":\"DROP\"}"), 0, 90, "orders.addr", "orders");

        assertThat(verdict.refusal().code()).isEqualTo("address-full-drop");
        assertThat(verdict.refusal().words()).contains("drops messages silently");
        assertThat(verdict.refusal().snippet()).contains("address-full-policy").contains("orders.addr");
    }

    @Test
    void aTargetWithRoomLetsTheBatchProceed() {
        assertThat(acceptance
                        .batch(facts("{\"addressFullMessagePolicy\":\"FAIL\"}"), 0, 90, "orders.addr", "orders")
                        .proceed())
                .isTrue();
    }

    @Test
    void thePreviewVerdictIsTheTargetRulesOverTheSameFacts() {
        List<AcceptanceFinding> findings = acceptance.evaluate(
                facts("{\"addressFullMessagePolicy\":\"DROP\"}"),
                new AcceptanceInput(5L, 50L, false, true, true, false, false, 90),
                "orders.addr",
                "orders");

        assertThat(findings).extracting(AcceptanceFinding::code).contains("address-full-drop");
    }

    @Test
    void itServesArtemis() {
        assertThat(acceptance.system()).isEqualTo("artemis");
    }
}
