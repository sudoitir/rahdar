package io.github.sudoitir.rahdar.kernel.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    enum Action {
        PURGE;

        @Override
        public String toString() {
            return "purge the queue";
        }
    }

    record Params(
            String queue,
            UUID clusterId,
            Instant at,
            long count,
            List<String> tags,
            Map<String, Object> extra,
            String note,
            Action mode) {}

    record Ratio(double ratio) {}

    record Items(List<String> items) {}

    record Text(String text) {}

    // Expected values are written by hand and hashed outside Java, so a change of form is a test failure.
    private static final String GOLDEN = "{\"at\":\"2026-10-07T12:30:45.123Z\","
            + "\"clusterId\":\"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d\",\"count\":12345678901,"
            + "\"extra\":{\"a\":true,\"b\":[1,2,3]},\"mode\":\"PURGE\",\"queue\":\"orders.dlq\","
            + "\"tags\":[\"café\",\"line\\nbreak\"]}";

    private static Params golden() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("b", List.of(1, 2, 3));
        extra.put("gone", null);
        extra.put("a", true);
        return new Params(
                "orders.dlq",
                UUID.fromString("0A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D"),
                OffsetDateTime.of(2026, 10, 7, 14, 30, 45, 123_000_000, ZoneOffset.ofHours(2))
                        .toInstant(),
                12_345_678_901L,
                List.of("café", "line\nbreak"),
                extra,
                null,
                Action.PURGE);
    }

    @Test
    void writesTheGoldenForm() {
        assertThat(CanonicalJson.write(golden())).isEqualTo(GOLDEN);
    }

    @Test
    void hashesTypeVersionAndForm() {
        String canonical = CanonicalJson.write(golden());

        assertThat(HexFormat.of().formatHex(CanonicalJson.hash("subscription.purge", 1, canonical)))
                .isEqualTo("f26e5f827f632394a9f2d9cf58ebe1a77d4959d83d64fbaf3da3548879d02d90");
        assertThat(HexFormat.of().formatHex(CanonicalJson.hash("t", 1, "{}")))
                .isEqualTo("0aac668f8d910d54b2ca42539cb8ba55882ef398a8c04dd003bcddf160243668");
    }

    @Test
    void theHashChangesWithTheTypeAndTheVersion() {
        String canonical = CanonicalJson.write(golden());
        byte[] hash = CanonicalJson.hash("subscription.purge", 1, canonical);

        assertThat(CanonicalJson.hash("subscription.purge", 2, canonical)).isNotEqualTo(hash);
        assertThat(CanonicalJson.hash("subscription.delete", 1, canonical)).isNotEqualTo(hash);
    }

    @Test
    void refusesFloatingPointNumbers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CanonicalJson.write(new Ratio(0.5)))
                .withMessageContaining("/ratio")
                .withMessageContaining("floating-point");
    }

    @Test
    void refusesNullsInArrays() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CanonicalJson.write(new Items(Arrays.asList("a", null))))
                .withMessageContaining("/items/1");
    }

    @Test
    void normalizesStringsToNfc() {
        assertThat(CanonicalJson.write(new Text("é")))
                .isEqualTo(CanonicalJson.write(new Text("é")))
                .isEqualTo("{\"text\":\"é\"}");
    }

    @Test
    void escapesControlCharactersLikeRfc8785() {
        assertThat(CanonicalJson.write(new Text("a\"b\\c\u0001\t"))).isEqualTo("{\"text\":\"a\\\"b\\\\c\\u0001\\t\"}");
    }

    @Test
    void refusesUnpairedSurrogates() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CanonicalJson.write(new Text("x\ud800")))
                .withMessageContaining("surrogate");
    }

    @Test
    void acceptsUpToTheSizeCapAndNoMore() {
        int overhead = "{\"text\":\"\"}".length();
        String fits = "a".repeat(CanonicalJson.MAX_BYTES - overhead);

        assertThat(CanonicalJson.write(new Text(fits))).hasSize(CanonicalJson.MAX_BYTES);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CanonicalJson.write(new Text(fits + "a")))
                .withMessageContaining(String.valueOf(CanonicalJson.MAX_BYTES));
    }

    @Test
    void countsTheCapInUtf8Bytes() {
        int overhead = "{\"text\":\"\"}".length();
        String twoByteChars = "é".repeat((CanonicalJson.MAX_BYTES - overhead) / 2 + 1);

        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJson.write(new Text(twoByteChars)));
    }
}
