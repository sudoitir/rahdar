package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Reads of resources through the SDK, with the driver's refusals as Rahdar's problems, and the reads of an attribute. */
final class RabbitMqReads {

    /** The page size of a list, and the most one request may ask for. */
    static final int DEFAULT_LIMIT = 100;

    static final int MAX_LIMIT = 500;

    /** The most resources one internal read gathers; a listing that reaches it says its answer is partial. */
    static final int CAP = 5_000;

    private static final int PAGE = 500;

    private RabbitMqReads() {}

    static int limit(Integer asked) {
        return asked == null ? DEFAULT_LIMIT : Math.clamp(asked, 1, MAX_LIMIT);
    }

    /** Where a list paged by offset resumes: the cursor's number, or the start. */
    static int offset(String cursor) {
        try {
            return cursor == null || cursor.isBlank() ? 0 : Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a cursor this list issued.", e);
        }
    }

    /** One page of the query, from the cursor when there is one. */
    static ResourcePage page(ClusterConnection connection, ResourceQuery query, String cursor, Integer limit) {
        return RabbitMqFailures.call(() -> rawPage(connection, query, cursor, limit));
    }

    /** {@link #page} with the driver's refusal left as it is, for a caller that answers some of them itself. */
    static ResourcePage rawPage(ClusterConnection connection, ResourceQuery query, String cursor, Integer limit) {
        ResourceQuery asked = query.limit(limit(limit));
        if (cursor != null && !cursor.isBlank()) {
            asked = asked.continueFrom(cursor);
        }
        return connection.resources().read(asked).orThrow();
    }

    /** What the read found, and whether the cap cut it short. */
    record Found(List<Resource> items, boolean truncated) {}

    /** Every resource the query selects, up to {@link #CAP}. */
    static Found all(ClusterConnection connection, ResourceQuery query) {
        List<Resource> out = new ArrayList<>();
        Optional<String> next = Optional.empty();
        do {
            ResourceQuery base = query.limit(PAGE);
            ResourceQuery asked = next.map(base::continueFrom).orElse(base);
            ResourcePage page = RabbitMqFailures.call(
                    () -> connection.resources().read(asked).orThrow());
            out.addAll(page.items());
            next = page.continueToken();
            if (out.size() >= CAP && next.isPresent()) {
                return new Found(out, true);
            }
        } while (next.isPresent());
        return new Found(out, false);
    }

    static ResourceQuery of(KindId kind, Map<String, String> selector) {
        return ResourceQuery.of(kind).where(selector);
    }

    static String text(Resource resource, String attribute) {
        return resource.attributes().raw(attribute).map(String::valueOf).orElse("");
    }

    static boolean flag(Resource resource, String attribute) {
        return resource.attributes()
                .raw(attribute)
                .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                .orElse(false);
    }

    static long number(Resource resource, String attribute) {
        return resource.attributes()
                .raw(attribute)
                .map(v -> v instanceof Number n ? n.longValue() : parse(String.valueOf(v)))
                .orElse(0L);
    }

    /** The attributes that start with the prefix, without it, in their text form. */
    static Map<String, String> members(Resource resource, String prefix) {
        Map<String, String> out = new TreeMap<>();
        resource.attributes().asMap().forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                out.put(key.substring(prefix.length()), String.valueOf(value));
            }
        });
        return out;
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException _) {
            return 0L;
        }
    }
}
