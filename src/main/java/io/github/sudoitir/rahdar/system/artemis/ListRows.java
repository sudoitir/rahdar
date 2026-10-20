package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.ArtemisLists;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** Reading the rows of an Artemis broker's {@code listX} operations and of its beans' attributes. */
public final class ListRows {

    /** The filter that lists everything. */
    public static final String ALL = ArtemisLists.ALL;

    private ListRows() {}

    /** Every bean's attributes, read in batches and parsed one by one. */
    public static <T> List<T> readAll(ArtemisNode client, List<String> objectNames, Function<JsonNode, T> parse) {
        return ArtemisLists.readAll(client.jolokia(), objectNames, parse);
    }

    public static long num(JsonNode row, String field) {
        return ArtemisLists.num(row, field);
    }

    public static boolean flag(JsonNode row, String field) {
        return ArtemisLists.flag(row, field);
    }

    public static String str(JsonNode row, String field) {
        return ArtemisLists.str(row, field);
    }
}
