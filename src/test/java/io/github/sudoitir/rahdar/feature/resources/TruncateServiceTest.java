package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.TruncateService.Cut;
import io.github.sudoitir.rahdar.feature.resources.TruncateService.TruncateEffect;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A cut names offsets or a time, and its effect names every group that loses records, the biggest loss first. */
class TruncateServiceTest {

    private static AttributeChange change(String key, long before, long after) {
        return new AttributeChange(key, Optional.of(before), Optional.of(after));
    }

    @Test
    void theEffectListsTheRecordsAndEachGroupThatLosesSomeBiggestFirst() {
        Preview preview = new Preview(
                List.of(
                        change("kafka.log_start_offset.0", 0, 6),
                        change("kafka.unread_lost.billing", 0, 3),
                        change("kafka.unread_lost.audit", 0, 5)),
                Set.of(),
                new Estimate(Count.estimate(6), "{message}"),
                "etag",
                PreconditionEnforcement.CHECKED_BEFORE);

        TruncateEffect effect = TruncateService.effectOf(preview);

        assertThat(effect.records()).isEqualTo(6);
        assertThat(effect.groups())
                .extracting(g -> g.group() + ":" + g.records())
                .containsExactly("audit:5", "billing:3");
    }

    @Test
    void anEstimateTheDriverCannotGiveIsAbsentNotZero() {
        Preview preview = new Preview(
                List.of(),
                Set.of(),
                new Estimate(new Count.Unavailable("not countable"), "{message}"),
                "etag",
                PreconditionEnforcement.CHECKED_BEFORE);

        assertThat(TruncateService.effectOf(preview).records()).isNull();
    }

    @Test
    void aCutIsOffsetsOrATimeAndNotBothNorNeither() {
        assertThat(new Cut(Map.of(0, 5L), null).offsets()).containsEntry(0, 5L);
        assertThat(new Cut(null, Instant.parse("2026-10-28T09:30:00Z")).time()).isNotNull();
        assertThatThrownBy(() -> new Cut(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Cut(Map.of(0, 5L), Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }
}
