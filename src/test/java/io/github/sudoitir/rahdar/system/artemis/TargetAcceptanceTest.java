package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceFinding;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceInput;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.BatchVerdict;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.FindingKind;
import io.github.sudoitir.rahdar.system.artemis.AcceptanceProbe.Facts;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Every row of the acceptance table (message-transfer spec, design D4), and each check that could not be made. */
class TargetAcceptanceTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final long MB = 1_000_000;

    /** A target that accepts anything: every field answered, nothing in the way. */
    static final class F {
        Boolean queueExists = true;
        String filter = "";
        Long ringSize = -1L;
        Boolean lastValue = false;
        Integer consumers = 1;
        Long messageCount = 0L;
        Long addressSize = 0L;
        Integer limitPercent = 0;
        String settings =
                "{\"addressFullMessagePolicy\":\"PAGE\",\"autoCreateQueues\":true,\"redistributionDelay\":-1}";
        Double disk = 0.10;
        Integer maxDisk = 90;
        Integer memory = 5;
        Integer idCache = 20000;
        Boolean persistIdCache = true;

        Facts facts() {
            return new Facts(
                    queueExists,
                    filter,
                    "ANYCAST",
                    ringSize,
                    lastValue,
                    consumers,
                    messageCount,
                    null,
                    addressSize,
                    false,
                    limitPercent,
                    settings == null ? null : JSON.readTree(settings),
                    disk,
                    maxDisk,
                    memory,
                    idCache,
                    persistIdCache);
        }
    }

    /** The selection and the node, beside the facts. */
    static final class In {
        Long count = 100L;
        Long bytes = 1 * MB;
        boolean sameQueueSameNode;
        boolean sameCluster;
        boolean live = true;
        boolean backup;
        boolean splitBrain;
    }

    private static Arguments row(String name, Consumer<F> facts, Consumer<In> in, FindingKind kind, String code) {
        return Arguments.of(name, facts, in, kind, code);
    }

    private static Arguments row(String name, Consumer<F> facts, FindingKind kind, String code) {
        return row(name, facts, i -> {}, kind, code);
    }

    private static Consumer<F> settings(String json) {
        return f -> f.settings = json;
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                row(
                        "same queue on the same node",
                        f -> {},
                        i -> i.sameQueueSameNode = true,
                        FindingKind.REFUSE,
                        "same-queue"),
                row("split-brain", f -> {}, i -> i.splitBrain = true, FindingKind.REFUSE, "target-split-brain"),
                row("backup", f -> {}, i -> i.backup = true, FindingKind.REFUSE, "target-backup"),
                row("not live", f -> {}, i -> i.live = false, FindingKind.REFUSE, "target-not-live"),
                row(
                        "missing queue, no auto-create",
                        f -> {
                            f.queueExists = false;
                            f.settings = "{\"autoCreateQueues\":false}";
                        },
                        FindingKind.REFUSE,
                        "queue-missing"),
                row(
                        "missing queue, auto-create unknown",
                        f -> {
                            f.queueExists = false;
                            f.settings = "{}";
                        },
                        FindingKind.UNKNOWN,
                        "queue-missing"),
                row("filtered queue", f -> f.filter = "region = 'eu'", FindingKind.REFUSE, "target-filtered"),
                row(
                        "address-full DROP",
                        settings("{\"addressFullMessagePolicy\":\"DROP\"}"),
                        FindingKind.REFUSE,
                        "address-full-drop"),
                row(
                        "page-full DROP",
                        settings("{\"addressFullMessagePolicy\":\"PAGE\",\"pageLimitBytes\":1000,"
                                + "\"pageFullMessagePolicy\":\"DROP\"}"),
                        FindingKind.REFUSE,
                        "page-full-drop"),
                row("ring smaller than the selection", f -> f.ringSize = 50L, FindingKind.REFUSE, "ring-too-small"),
                row(
                        "ring pushes out what it holds",
                        f -> {
                            f.ringSize = 150L;
                            f.messageCount = 100L;
                        },
                        FindingKind.WARN,
                        "ring-pushes-out"),
                row(
                        "FAIL without the room",
                        f -> {
                            f.settings =
                                    "{\"addressFullMessagePolicy\":\"FAIL\",\"maxSizeBytes\":%d}".formatted(100 * MB);
                            f.addressSize = 90 * MB;
                        },
                        i -> i.bytes = 84 * MB,
                        FindingKind.REFUSE,
                        "capacity"),
                row(
                        "BLOCK with tight room",
                        f -> {
                            f.settings =
                                    "{\"addressFullMessagePolicy\":\"BLOCK\",\"maxSizeBytes\":%d}".formatted(100 * MB);
                            f.addressSize = 85 * MB;
                        },
                        i -> i.bytes = 10 * MB,
                        FindingKind.WARN,
                        "capacity-tight"),
                row(
                        "FAIL over max-size-messages",
                        f -> {
                            f.settings = "{\"addressFullMessagePolicy\":\"FAIL\",\"maxSizeMessages\":120}";
                            f.messageCount = 50L;
                        },
                        FindingKind.REFUSE,
                        "capacity-messages"),
                row(
                        "FAIL address memory high",
                        f -> {
                            f.settings = "{\"addressFullMessagePolicy\":\"FAIL\"}";
                            f.memory = 95;
                        },
                        FindingKind.WARN,
                        "memory-tight"),
                row("disk at its limit", f -> f.disk = 0.91, FindingKind.REFUSE, "disk-full"),
                row("disk near its limit", f -> f.disk = 0.85, FindingKind.WARN, "disk-tight"),
                row("last-value queue", f -> f.lastValue = true, FindingKind.WARN, "last-value"),
                row("duplicate cache off", f -> f.idCache = 0, FindingKind.WARN, "duplicates-possible"),
                row(
                        "duplicate cache not persisted",
                        f -> f.persistIdCache = false,
                        FindingKind.WARN,
                        "duplicates-possible"),
                row(
                        "may redistribute again",
                        f -> {
                            f.consumers = 0;
                            f.settings = "{\"redistributionDelay\":0}";
                        },
                        i -> i.sameCluster = true,
                        FindingKind.WARN,
                        "may-redistribute"),
                // What could not be read is stated as unknown, never as a pass.
                row("queue did not answer", f -> f.queueExists = null, FindingKind.UNKNOWN, "queue-unknown"),
                row("filter did not answer", f -> f.filter = null, FindingKind.UNKNOWN, "filter-unknown"),
                row("settings did not answer", settings(null), FindingKind.UNKNOWN, "address-settings"),
                row("disk did not answer", f -> f.disk = null, FindingKind.UNKNOWN, "disk-unknown"),
                row(
                        "duplicate cache did not answer",
                        f -> f.idCache = null,
                        FindingKind.UNKNOWN,
                        "duplicate-detection"),
                row(
                        "FAIL with the address size unknown",
                        f -> {
                            f.settings =
                                    "{\"addressFullMessagePolicy\":\"FAIL\",\"maxSizeBytes\":%d}".formatted(100 * MB);
                            f.addressSize = null;
                        },
                        FindingKind.UNKNOWN,
                        "capacity-unknown"),
                row(
                        "FAIL with the selection size unknown",
                        settings("{\"addressFullMessagePolicy\":\"FAIL\",\"maxSizeBytes\":%d}".formatted(100 * MB)),
                        i -> i.bytes = null,
                        FindingKind.UNKNOWN,
                        "capacity-unknown"),
                row(
                        "ring with the selection size unknown",
                        f -> f.ringSize = 50L,
                        i -> i.count = null,
                        FindingKind.UNKNOWN,
                        "ring-unknown"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void findsWhatTheTableSays(String name, Consumer<F> facts, Consumer<In> in, FindingKind kind, String code) {
        List<AcceptanceFinding> findings = evaluate(facts, in);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(code);
            assertThat(f.kind()).isEqualTo(kind);
            assertThat(f.words()).isNotBlank();
        });
    }

    @Test
    void aTargetWithRoomAndNothingInTheWayHasNoFinding() {
        assertThat(evaluate(f -> {}, i -> {})).isEmpty();
    }

    @Test
    void aMissingQueueTheBrokerWillCreateIsNoFinding() {
        assertThat(evaluate(f -> f.queueExists = false, i -> {})).isEmpty();
    }

    @Test
    void redistributionIsOnlyAConcernWithinOneCluster() {
        assertThat(evaluate(
                        f -> {
                            f.consumers = 0;
                            f.settings = "{\"redistributionDelay\":0}";
                        },
                        i -> {}))
                .extracting(AcceptanceFinding::code)
                .doesNotContain("may-redistribute");
    }

    @Test
    void notEnoughRoomStatesBothFigures() {
        List<AcceptanceFinding> findings = evaluate(
                f -> {
                    f.settings = "{\"addressFullMessagePolicy\":\"FAIL\",\"maxSizeBytes\":%d}".formatted(100 * MB);
                    f.addressSize = 90 * MB;
                },
                i -> i.bytes = 84 * MB);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("capacity"))
                .singleElement()
                .satisfies(f -> assertThat(f.words()).contains("84.0 MB").contains("10.0 MB"));
    }

    @Test
    void aSilentDropNamesTheSettingToChange() {
        assertThat(evaluate(settings("{\"addressFullMessagePolicy\":\"DROP\"}"), i -> {}))
                .filteredOn(f -> f.code().equals("address-full-drop"))
                .singleElement()
                .satisfies(f -> assertThat(f.snippet()).contains("<address-full-policy>PAGE</address-full-policy>"));
    }

    @Test
    void aTargetThatIsNotLiveIsNotReadFurther() {
        // The facts of a node that is not live are not read at all, so none of their checks is reported.
        List<AcceptanceFinding> findings = TargetAcceptance.evaluate(
                null, new AcceptanceInput(100L, MB, false, false, false, false, false, 90), "orders", "orders");

        assertThat(findings).extracting(AcceptanceFinding::code).containsExactly("target-not-live");
    }

    // ---- before each batch ---------------------------------------------------------

    @Test
    void aBatchProceedsWhenTheTargetHasRoom() {
        assertThat(batch(f -> {}, MB).proceed()).isTrue();
    }

    @Test
    void aBatchFailsWhenTheTargetWouldNowDropMessages() {
        assertThat(batch(f -> f.filter = "x = 1", MB).refusal().code()).isEqualTo("target-filtered");
        assertThat(batch(settings("{\"addressFullMessagePolicy\":\"DROP\"}"), MB)
                        .refusal()
                        .code())
                .isEqualTo("address-full-drop");
    }

    @Test
    void aBatchWaitsWhenTheTargetIsAtTheThreshold() {
        Consumer<F> nearlyFull = f -> {
            f.settings = "{\"addressFullMessagePolicy\":\"FAIL\",\"maxSizeBytes\":%d}".formatted(100 * MB);
            f.addressSize = 89 * MB;
        };
        assertThat(batch(nearlyFull, 2 * MB).waitReason()).contains("full");
        assertThat(batch(nearlyFull, 0).proceed()).isTrue();

        assertThat(batch(
                                f -> {
                                    f.settings = "{\"addressFullMessagePolicy\":\"BLOCK\"}";
                                    f.limitPercent = 95;
                                },
                                0)
                        .waitReason())
                .contains("95%");
        assertThat(batch(f -> f.disk = 0.85, 0).waitReason()).contains("disk");
    }

    @Test
    void aPagingAddressNeverMakesABatchWait() {
        // PAGE spills to disk rather than refusing: a full address is no reason to wait.
        assertThat(batch(f -> f.limitPercent = 120, MB).proceed()).isTrue();
    }

    @Test
    void whatABatchCannotReadDoesNotStopIt() {
        assertThat(batch(
                                f -> {
                                    f.filter = null;
                                    f.settings = null;
                                    f.disk = null;
                                },
                                MB)
                        .proceed())
                .isTrue();
    }

    private static List<AcceptanceFinding> evaluate(Consumer<F> tweakFacts, Consumer<In> tweakInput) {
        F f = new F();
        tweakFacts.accept(f);
        In in = new In();
        tweakInput.accept(in);
        return TargetAcceptance.evaluate(
                f.facts(),
                new AcceptanceInput(
                        in.count,
                        in.bytes,
                        in.sameQueueSameNode,
                        in.sameCluster,
                        in.live,
                        in.backup,
                        in.splitBrain,
                        90),
                "orders",
                "orders");
    }

    private static BatchVerdict batch(Consumer<F> tweak, long batchBytes) {
        F f = new F();
        tweak.accept(f);
        return TargetAcceptance.batch(f.facts(), batchBytes, 90, "orders", "orders");
    }
}
