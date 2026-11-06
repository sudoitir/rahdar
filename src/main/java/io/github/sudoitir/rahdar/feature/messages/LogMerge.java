package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.rahdar.platform.broker.LogPage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * about the whole log. When an earlier offset sorts after a later one, that earlier offset stays owed and the
 * continuation still reads what follows the record this page showed. A latest page's continuation points at older
 * records. It does not point past a record the page already shows, and it does not point past the end of a tail
 * the page already shows.
 */
public final class LogMerge {

    private LogMerge() {}

    /**
     * The records read from one partition, the offset after the forward read when that partition has more, an
     * exclusive end when a later read must stop, offsets that were owed and not read this time, and the offset the
     * next forward read starts at when it is already known.
     */
    public record Fetched(List<LogPage.LogRecord> records, Long next, Long end, List<Span> owed, Long watermark) {
        public Fetched(List<LogPage.LogRecord> records, Long next) {
            this(records, next, null, List.of(), null);
        }

        public Fetched(List<LogPage.LogRecord> records, Long next, Long end) {
            this(records, next, end, List.of(), null);
        }

        public Fetched {
            if (records == null) {
                records = List.of();
            }
            owed = owed == null || owed.isEmpty() ? List.of() : List.copyOf(owed);
        }
    }

    /** One record and the partition it was read from. */
    public record Tagged(int partition, LogPage.LogRecord record) {}

    /** Inclusive offsets still to deliver before a partition's forward cursor. */
    public record Span(long from, long to) {
        public Span {
            if (from < 0 || to < from) {
                throw new IllegalArgumentException("pending range");
            }
        }

        private String text() {
            return from == to ? Long.toString(from) : from + "-" + to;
        }
    }

    /**
     * Where the next read of one partition starts, where it stops, and the offsets before {@code offset} that this
     * page did not show.
     */
    public record Placed(long offset, Long end, List<Span> pending) {
        public Placed(long offset, Long end) {
            this(offset, end, List.of());
        }

        public Placed {
            pending = pending == null || pending.isEmpty() ? List.of() : List.copyOf(pending);
        }

        /** Ranges such as {@code 10} or {@code 10-12.14}, or null when nothing is owed. */
        public String pendingText() {
            if (pending.isEmpty()) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            for (Span span : pending) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(span.text());
            }
            return out.toString();
        }
    }

    /** The records to show, in order, and where each unfinished partition resumes. */
    public record Window(List<Tagged> records, Map<Integer, Placed> resume) {}

    /**
     * {@code at} is {@code partition:offset} pairs, such as {@code 0:120,1:40}. A third number, {@code
     * partition:offset:end}, stops that read before {@code end}. A fourth, {@code partition:offset::10} or {@code
     * partition:offset:end:10-12.14}, names inclusive offsets before {@code offset} that are still to deliver, so a
     * later page does not repeat a record already shown and does not drop what follows it. Blank means the caller
     * has not continued, so every partition is read from the start it asked for. A partition named here is read from
     * that offset; one that is absent has already been read to its end.
     */
    public static Map<Integer, Placed> resume(String at) {
        if (at == null || at.isBlank()) {
            return null;
        }
        SortedMap<Integer, Placed> out = new TreeMap<>();
        for (String part : at.split(",")) {
            String[] bits = part.split(":", -1);
            if (bits.length < 2 || bits.length > 4) {
                throw badAt();
            }
            try {
                int partition = Integer.parseInt(bits[0]);
                long offset = Long.parseLong(bits[1]);
                Long end = bits.length >= 3 && !bits[2].isEmpty() ? Long.parseLong(bits[2]) : null;
                List<Span> pending = bits.length == 4 ? parsePending(bits[3], offset) : List.of();
                if (partition < 0 || offset < 0 || (end != null && end < 0)) {
                    throw badAt();
                }
                out.put(partition, new Placed(offset, end, pending));
            } catch (NumberFormatException _) {
                throw badAt();
            }
        }
        return Collections.unmodifiableSortedMap(out);
    }

    /**
     * The first {@code size} records of {@code fetched}, and where each partition is read from next. A partition
     * whose fetched records were all kept resumes at {@code next} when it has one. A record left out that sits
     * before one this page showed stays owed, and the forward cursor moves past the shown record.
     */
    public static Window page(Map<Integer, Fetched> fetched, int size) {
        return window(fetched, earliest(fetched, size), true);
    }

    /**
     * The newest {@code size} records of {@code fetched}. A record with no timestamp is not newer than one that has
     * one. The page is still ordered by timestamp, then partition, then offset. A partition whose records were all
     * kept is finished: its {@code next} is past that tail. A record left out of the tail stays owed, and the
     * continuation does not read past the tail.
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
        return window(fetched, List.copyOf(chosen), false);
    }

    private static List<Tagged> earliest(Map<Integer, Fetched> fetched, int size) {
        List<Tagged> all = new ArrayList<>();
        for (var entry : fetched.entrySet()) {
            for (LogPage.LogRecord record : entry.getValue().records()) {
                all.add(new Tagged(entry.getKey(), record));
            }
        }
        all.sort(LogMerge::compare);
        int kept = size < 0 ? 0 : Math.min(size, all.size());
        return List.copyOf(all.subList(0, kept));
    }

    private static Window window(Map<Integer, Fetched> fetched, List<Tagged> page, boolean followNext) {
        SortedMap<Integer, Placed> resume = new TreeMap<>();
        for (var entry : fetched.entrySet()) {
            Placed placed = resumeFor(entry.getKey(), entry.getValue(), page, followNext);
            if (placed != null) {
                resume.put(entry.getKey(), placed);
            }
        }
        // Map.copyOf drops the key order. The resume is read by partition, so it stays a sorted map.
        return new Window(page, Collections.unmodifiableSortedMap(resume));
    }

    /**
     * Where one partition continues. A shown offset that sorts before an earlier one, or before one left between
     * shown offsets, must not be read again, and the offset after it must not be abandoned.
     */
    private static Placed resumeFor(int partition, Fetched fetched, List<Tagged> page, boolean followNext) {
        List<Long> unshown = new ArrayList<>();
        List<Long> shown = new ArrayList<>();
        long maxId = -1;
        for (LogPage.LogRecord record : fetched.records()) {
            long id = record.message().messageId();
            maxId = Math.max(maxId, id);
            if (page.stream().anyMatch(tagged -> same(tagged, partition, record))) {
                shown.add(id);
            } else {
                unshown.add(id);
            }
        }
        Long end = fetched.end();
        Long earliestOmitted = unshown.stream().min(Long::compare).orElse(null);
        // A forward read from the earliest omitted offset would return a shown record that sits after it.
        boolean shownAfterOmitted = earliestOmitted != null && shown.stream().anyMatch(id -> id > earliestOmitted);
        if (!shownAfterOmitted && fetched.owed().isEmpty()) {
            if (earliestOmitted != null) {
                return new Placed(earliestOmitted, end);
            }
            if (!followNext || fetched.next() == null) {
                return null;
            }
            return new Placed(fetched.next(), end);
        }
        // A latest tail whose omitted offsets are all older than the ones it showed stops before the shown one.
        if (!followNext && !shown.isEmpty() && fetched.owed().isEmpty() && earliestOmitted != null) {
            long minShown = shown.stream().mapToLong(Long::longValue).min().orElseThrow();
            boolean after = unshown.stream().anyMatch(id -> id > minShown);
            if (!after) {
                return new Placed(earliestOmitted, minShown);
            }
        }
        long mark = watermark(fetched, maxId);
        // A conditional expression would unbox a null end. A forward page keeps the caller's end, which may be absent.
        Long bound = followNext || end != null ? end : Long.valueOf(mark);
        List<Span> holes = new ArrayList<>(merge(spans(unshown), fetched.owed()));
        holes.removeIf(span -> span.from() >= mark);
        if (holes.isEmpty()) {
            if (!followNext || fetched.next() == null) {
                return null;
            }
            return new Placed(fetched.next(), end);
        }
        return new Placed(mark, bound, holes);
    }

    /** The offset the next forward read starts at. A known watermark wins over the last record in the candidate list. */
    private static long watermark(Fetched fetched, long maxId) {
        if (fetched.watermark() != null) {
            return fetched.watermark();
        }
        if (fetched.next() != null) {
            return fetched.next();
        }
        return maxId + 1;
    }

    private static List<Span> spans(List<Long> offsets) {
        if (offsets.isEmpty()) {
            return List.of();
        }
        List<Long> sorted = new ArrayList<>(offsets);
        sorted.sort(Long::compare);
        List<Span> out = new ArrayList<>();
        long from = sorted.getFirst();
        long to = from;
        for (int i = 1; i < sorted.size(); i++) {
            long id = sorted.get(i);
            if (id == to + 1) {
                to = id;
            } else if (id != to) {
                out.add(new Span(from, to));
                from = id;
                to = id;
            }
        }
        out.add(new Span(from, to));
        return out;
    }

    private static List<Span> merge(List<Span> left, List<Span> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        List<Span> all = new ArrayList<>(left.size() + right.size());
        all.addAll(left);
        all.addAll(right);
        all.sort(Comparator.comparingLong(Span::from));
        List<Span> out = new ArrayList<>();
        for (Span span : all) {
            if (out.isEmpty()) {
                out.add(span);
                continue;
            }
            Span prev = out.getLast();
            if (span.from() <= prev.to() + 1) {
                out.set(out.size() - 1, new Span(prev.from(), Math.max(prev.to(), span.to())));
            } else {
                out.add(span);
            }
        }
        return List.copyOf(out);
    }

    private static List<Span> parsePending(String text, long watermark) {
        if (text.isBlank()) {
            throw badAt();
        }
        List<Span> spans = new ArrayList<>();
        long previous = -1;
        for (String piece : text.split("\\.", -1)) {
            String[] bounds = piece.split("-", -1);
            if (bounds.length != 1 && bounds.length != 2) {
                throw badAt();
            }
            long from = Long.parseLong(bounds[0]);
            long to = bounds.length == 2 ? Long.parseLong(bounds[1]) : from;
            if (from < 0 || to < from || to >= watermark || from <= previous) {
                throw badAt();
            }
            spans.add(new Span(from, to));
            previous = to;
        }
        return List.copyOf(spans);
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
        return new IllegalArgumentException(
                "at must be partition:offset pairs, such as 0:120,1:40, or partition:offset::10 when an earlier"
                        + " offset is still owed.");
    }
}
