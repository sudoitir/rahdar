package io.github.sudoitir.rahdar.feature.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.LogPage.LogRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A merged page is ordered by timestamp, then partition, then offset, and continues per partition. */
class LogMergeTest {

    @Test
    void thePageIsTimestampThenPartitionThenOffsetAndAMissingTimestampSortsLast() {
        Map<Integer, LogMerge.Fetched> fetched = fetched();

        LogMerge.Window page = LogMerge.page(fetched, 4);

        assertThat(page.records())
                .extracting(tagged ->
                        tagged.partition() + ":" + tagged.record().message().messageId())
                .containsExactly("0:10", "1:5", "0:11", "1:6");
        assertThat(page.records().get(3).record().message().timestamp()).isZero();
    }

    @Test
    void aPageBoundaryResumesAtTheRecordLeftOutOfEachPartition() {
        LogMerge.Window page = LogMerge.page(fetched(), 2);

        assertThat(page.records())
                .extracting(tagged ->
                        tagged.partition() + ":" + tagged.record().message().messageId())
                .containsExactly("0:10", "1:5");
        // The next fetched offset is not the resume: records 11 and 6 were read and left out of this page.
        assertThat(page.resume())
                .containsExactly(
                        Map.entry(0, new LogMerge.Placed(11L, null)), Map.entry(1, new LogMerge.Placed(6L, null)));
    }

    @Test
    void aPartitionWhoseRecordsWereAllKeptResumesAfterThem() {
        LogMerge.Window page = LogMerge.page(fetched(), 3);

        assertThat(page.resume())
                .containsExactly(
                        Map.entry(0, new LogMerge.Placed(12L, null)), Map.entry(1, new LogMerge.Placed(6L, null)));
    }

    @Test
    void latestKeepsTheNewestRecordsAndDoesNotContinuePastATailItShowed() {
        Map<Integer, LogMerge.Fetched> tails = new LinkedHashMap<>();
        tails.put(0, new LogMerge.Fetched(List.of(record(98, 10), record(99, 11)), 100L));
        tails.put(1, new LogMerge.Fetched(List.of(record(198, 5000), record(199, 5001)), 200L));

        LogMerge.Window page = LogMerge.latest(tails, 2);

        assertThat(page.records())
                .extracting(tagged ->
                        tagged.partition() + ":" + tagged.record().message().messageId())
                .containsExactly("1:198", "1:199");
        assertThat(page.resume()).containsExactly(Map.entry(0, new LogMerge.Placed(98L, null)));
    }

    @Test
    void latestStopsBeforeARecordTheSamePartitionAlreadyShowed() {
        Map<Integer, LogMerge.Fetched> tails = new LinkedHashMap<>();
        tails.put(0, new LogMerge.Fetched(List.of(record(8, 10), record(9, 100)), null));
        tails.put(1, new LogMerge.Fetched(List.of(record(8, 90), record(9, 5000)), null));

        LogMerge.Window page = LogMerge.latest(tails, 2);

        assertThat(page.records())
                .extracting(tagged ->
                        tagged.partition() + ":" + tagged.record().message().messageId())
                .containsExactly("0:9", "1:9");
        assertThat(page.resume())
                .containsExactly(Map.entry(0, new LogMerge.Placed(8L, 9L)), Map.entry(1, new LogMerge.Placed(8L, 9L)));
    }

    @Test
    void aBlankContinuationReadsEveryPartitionAndABadOneIsRefused() {
        assertThat(LogMerge.resume(null)).isNull();
        assertThat(LogMerge.resume("  ")).isNull();
        assertThat(LogMerge.resume("0:120,1:40"))
                .containsExactly(
                        Map.entry(0, new LogMerge.Placed(120L, null)), Map.entry(1, new LogMerge.Placed(40L, null)));
        assertThat(LogMerge.resume("0:10:11")).containsExactly(Map.entry(0, new LogMerge.Placed(10L, 11L)));
        assertThatThrownBy(() -> LogMerge.resume("120"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition:offset");
    }

    /** Partition 0 at 10 (t=100) and 11 (t=300); partition 1 at 5 (t=200) and 6 (no timestamp). */
    private static Map<Integer, LogMerge.Fetched> fetched() {
        Map<Integer, LogMerge.Fetched> fetched = new LinkedHashMap<>();
        fetched.put(0, new LogMerge.Fetched(List.of(record(10, 100), record(11, 300)), 12L));
        fetched.put(1, new LogMerge.Fetched(List.of(record(5, 200), record(6, 0)), 7L));
        return fetched;
    }

    private static LogRecord record(long offset, long timestamp) {
        return new LogRecord(
                new BrowsedMessage(
                        offset,
                        0,
                        true,
                        0,
                        timestamp,
                        0,
                        4,
                        null,
                        null,
                        null,
                        null,
                        "body",
                        BodyEncoding.TEXT,
                        Compression.NONE,
                        null,
                        false,
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of()),
                false,
                null);
    }
}
