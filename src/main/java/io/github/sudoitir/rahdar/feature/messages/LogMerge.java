package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.rahdar.platform.broker.LogPage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One bounded page across the partitions of a log. Each partition contributes at most a page, already in its own
 * offset order. {@link #page} keeps the earliest of those records. {@link #latest} keeps the newest, because a
 * latest tail is not a prefix and keeping the earliest would show the oldest tails first. Either page is ordered
 * by timestamp, then partition, then offset, and a record with no timestamp sorts last. The resume map is where
 * each unfinished partition continues: there is no offset that applies to every partition.
 *
 * <p>Timestamps can go backwards inside a partition, so a page is the next window of each partition, not a claim
 * about the whole log. A latest page's continuation points at older records. It does not point past a record the
 * page already shows, and it does not point past the end of a tail the page already shows.
 */
public final class LogMerge {

    private LogMerge() {}

    /**
     * The records read from one partition, the offset after them when that partition has more, and an exclusive end
     * when a later read must stop before records already shown.
     */
    public record Fetched(List<LogPage.LogRecord> records, Long next, Long end) {
        public Fetched(List<LogPage.LogRecord> records, Long next) {
            this(records, next, null);
        }
    }

    /** One record and the partition it was read from. */
    public record Tagged(int partition, LogPage.LogRecord record) {}

    /** Where the next read of one partition starts, and where it stops so it does not repeat a shown record. */
    public record Placed(long offset, Long end) {}

    /** The records to show, in order, and where each unfinished partition resumes. */
    public record Window(List<Tagged> records, Map<Integer, Placed> resume) {}

    /**
     * {@code at} is {@code partition:offset} pairs, such as {@code 0:120,1:40}. A third number, {@code
     * partition:offset:end}, stops that read before {@code end} so it does not repeat a record already shown. Blank
     * means the caller has not continued, so every partition is read from the start it asked for. A partition named
     * here is read from that offset; one that is absent has already been read to its end.
     */
    public static Map<Integer, Placed> resume(String at) {
        if (at == null || at.isBlank()) {
            return null;
        }
        SortedMap<Integer, Placed> out = new TreeMap<>();
        for (String part : at.split(",")) {
            String[] bits = part.split(":", -1);
            if (bits.length != 2 && bits.length != 3) {
                throw badAt();
            }
            try {
                int partition = Integer.parseInt(bits[0]);
                long offset = Long.parseLong(bits[1]);
                Long end = bits.length == 3 ? Long.parseLong(bits[2]) : null;
                if (partition < 0 || offset < 0 || (end != null && end < 0)) {
                    throw badAt();
                }
                out.put(partition, new Placed(offset, end));
            } catch (NumberFormatException _) {
                throw badAt();
            }
        }
        return Collections.unmodifiableSortedMap(out);
    }

    /**
     * The first {@code size} records of {@code fetched}, and the offset each partition should be read from next.
     * A partition whose fetched records were all kept resumes at {@code next} when it has one, and is otherwise
     * finished. A partition with a record left out of the page resumes at that record, so a later page does not
     * skip it or repeat one already shown.
     */
    public static Window page(Map<Integer, Fetched> fetched, int size) {
        List<Tagged> all = new ArrayList<>();
        for (var entry : fetched.entrySet()) {
            for (LogPage.LogRecord record : entry.getValue().records()) {
                all.add(new Tagged(entry.getKey(), record));
            }
        }
        all.sort(LogMerge::compare);
        int kept = size < 0 ? 0 : Math.min(size, all.size());
        List<Tagged> page = List.copyOf(all.subList(0, kept));
        SortedMap<Integer, Placed> resume = new TreeMap<>();
        for (var entry : fetched.entrySet()) {
            int partition = entry.getKey();
            Long excluded = null;
            for (LogPage.LogRecord record : entry.getValue().records()) {
                if (page.stream().noneMatch(tagged -> same(tagged, partition, record))) {
                    excluded = record.message().messageId();
                    break;
                }
            }
            if (excluded != null) {
                resume.put(partition, new Placed(excluded, entry.getValue().end()));
            } else if (entry.getValue().next() != null) {
                resume.put(
                        partition,
                        new Placed(entry.getValue().next(), entry.getValue().end()));
            }
        }
        // Map.copyOf drops the key order. The resume is read by partition, so it stays a sorted map.
        return new Window(page, Collections.unmodifiableSortedMap(resume));
    }

    /**
     * The newest {@code size} records of {@code fetched}. A record with no timestamp is not newer than one that has
     * one. The page is still ordered by timestamp, then partition, then offset. A partition whose records were all
     * kept is finished: its {@code next} is past that tail, so following it would walk toward a newer offset. A
     * partition with records left out resumes at the first of those, and stops before a record this page shows when
     * that record sits further along the same partition.
     */
    public static Window latest(Map<Integer, Fetched> fetched, int size) {
        List<Tagged> all = new ArrayList<>();
        for (var entry : fetched.entrySet()) {
            for (LogPage.LogRecord record : entry.getValue().records()) {
                all.add(new Tagged(entry.getKey(), record));
            }
        }
        all.sort(LogMerge::newestLast);
        int kept = size < 0 ? 0 : Math.min(size, all.size());
        List<Tagged> chosen = new ArrayList<>(all.subList(all.size() - kept, all.size()));
        chosen.sort(LogMerge::compare);
        List<Tagged> page = List.copyOf(chosen);
        SortedMap<Integer, Placed> resume = new TreeMap<>();
        for (var entry : fetched.entrySet()) {
            Placed placed = resumeLatest(entry.getKey(), entry.getValue().records(), page);
            if (placed != null) {
                resume.put(entry.getKey(), placed);
            }
        }
        return new Window(page, Collections.unmodifiableSortedMap(resume));
    }

    /**
     * Where a latest tail continues. Empty when the partition's fetched records were all shown: the offset after a
     * tail is newer, or the end, and the next page must not walk there.
     */
    private static Placed resumeLatest(int partition, List<LogPage.LogRecord> records, List<Tagged> page) {
        List<LogPage.LogRecord> excluded = new ArrayList<>();
        long minKept = Long.MAX_VALUE;
        boolean keptAny = false;
        for (LogPage.LogRecord record : records) {
            if (page.stream().anyMatch(tagged -> same(tagged, partition, record))) {
                keptAny = true;
                minKept = Math.min(minKept, record.message().messageId());
            } else {
                excluded.add(record);
            }
        }
        if (excluded.isEmpty()) {
            return null;
        }
        long older = excluded.getFirst().message().messageId();
        if (!keptAny || older > minKept) {
            return new Placed(older, null);
        }
        return new Placed(older, minKept);
    }

    /** No timestamp first, so a latest window does not treat a missing time as the newest record. */
    private static int newestLast(Tagged left, Tagged right) {
        boolean leftMissing = left.record().message().timestamp() <= 0;
        boolean rightMissing = right.record().message().timestamp() <= 0;
        if (leftMissing != rightMissing) {
            return leftMissing ? -1 : 1;
        }
        return compare(left, right);
    }

    /** No timestamp last; then timestamp, partition and offset. */
    static int compare(Tagged left, Tagged right) {
        boolean leftMissing = left.record().message().timestamp() <= 0;
        boolean rightMissing = right.record().message().timestamp() <= 0;
        if (leftMissing != rightMissing) {
            return leftMissing ? 1 : -1;
        }
        if (!leftMissing) {
            int byTime = Long.compare(
                    left.record().message().timestamp(),
                    right.record().message().timestamp());
            if (byTime != 0) {
                return byTime;
            }
        }
        int byPartition = Integer.compare(left.partition(), right.partition());
        if (byPartition != 0) {
            return byPartition;
        }
        return Long.compare(
                left.record().message().messageId(), right.record().message().messageId());
    }

    private static boolean same(Tagged tagged, int partition, LogPage.LogRecord record) {
        return tagged.partition() == partition
                && tagged.record().message().messageId() == record.message().messageId();
    }

    private static IllegalArgumentException badAt() {
        return new IllegalArgumentException("at must be partition:offset pairs, such as 0:120,1:40.");
    }
}
