package io.github.sudoitir.rahdar.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The contract types a decoded field as non-null when present, so a null one is absent. */
class DecodedRecordViewTest {

    @Test
    void anUndecodedRecordOmitsTheFieldsTheContractTypesAsNonNull() throws Exception {
        String json = JsonMapper.builder().build().writeValueAsString(DecodedRecordView.undecoded("no-schema-id"));

        assertThat(json).isEqualTo("{\"reason\":\"no-schema-id\"}");
    }
}
