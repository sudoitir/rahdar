package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArtemisFlowSamplerTest {

    @Test
    void theReceivingNodeIsTheLastSegmentOfAStoreAndForwardQueue() {
        assertThat(ArtemisFlowSampler.receivingNodeId("$.artemis.internal.sf.my-cluster.abc-123"))
                .isEqualTo("abc-123");
        assertThat(ArtemisFlowSampler.receivingNodeId("nodots")).isEqualTo("nodots");
    }
}
