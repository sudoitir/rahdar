package io.github.sudoitir.rahdar.support.wire;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WireRecorderTest {

    @Test
    void theOrderOfKeysAndOfBatchEntriesDoesNotChangeARecordedRequest() {
        String a =
                "POST /j | [{\"type\":\"read\",\"mbean\":\"b\"},{\"type\":\"exec\",\"arguments\":[\"{\\\"x\\\":1,\\\"a\\\":2}\"]}]";
        String b =
                "POST /j | [{\"arguments\":[\"{\\\"a\\\":2,\\\"x\\\":1}\"],\"type\":\"exec\"},{\"mbean\":\"b\",\"type\":\"read\"}]";
        assertThat(WireRecorder.normalise(a)).isEqualTo(WireRecorder.normalise(b));
    }

    @Test
    void volatileValuesAreReplaced() {
        assertThat(WireRecorder.normalise("POST /j | {\"q\":\"Q.18e6c6df.SRC\",\"t\":1790000000000}"))
                .isEqualTo("POST /j | {\"q\":\"Q.<hex>.SRC\",\"t\":<millis>}");
    }
}
