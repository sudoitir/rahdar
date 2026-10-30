package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import org.junit.jupiter.api.Test;

/** A log purge confirms the driver's count, and never turns an unknown count into zero. */
class BrokerMessagesPurgeTest {

    @Test
    void anUnavailableCountIsNotZeroAndAZeroCountIsNotOffered() {
        assertThat(BrokerMessages.recordsToSkip(Count.exact(12))).isEqualTo(12L);
        assertThat(BrokerMessages.recordsToSkip(Count.estimate(4))).isEqualTo(4L);
        assertThat(BrokerMessages.recordsToSkip(Count.unavailable("the end offset was not reported")))
                .isNull();

        assertThatThrownBy(() -> BrokerMessages.refuseUnlessCounted(null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("could not count")
                .hasMessageContaining("No record is deleted");
        assertThatThrownBy(() -> BrokerMessages.refuseUnlessCounted(0L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last record")
                .hasMessageContaining("open transaction");
        assertThat(BrokerMessages.recordsToSkip(Count.exact(1))).isEqualTo(1L);
    }
}
