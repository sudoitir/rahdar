package io.github.sudoitir.rahdar.platform.broker.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions.Entry;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /systems} lists the drivers on the class path, each with how its clusters are registered. */
class SystemsControllerTest {

    private final MockMvc mvc = standaloneSetup(new SystemsController(
                    BrokerDrivers.load(),
                    List.of(
                            options(ArtemisModule.SYSTEM, Entry.JOLOKIA_SEEDS),
                            options(KafkaModule.SYSTEM, Entry.BOOTSTRAP))))
            .build();

    @Test
    void listsKafkaAndArtemisWithTheirConnectionStyle() throws Exception {
        mvc.perform(get("/systems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == 'kafka')].name").value("Apache Kafka"))
                .andExpect(jsonPath("$.data[?(@.id == 'kafka')].connection").value("bootstrap"))
                .andExpect(
                        jsonPath("$.data[?(@.id == 'kafka')].supportedVersions").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.id == 'artemis')].connection").value("jolokia-seeds"));
    }

    @Test
    void aDriverWithoutConnectionOptionsIsNotOffered() throws Exception {
        MockMvc onlyKafka = standaloneSetup(new SystemsController(
                        BrokerDrivers.load(), List.of(options(KafkaModule.SYSTEM, Entry.BOOTSTRAP))))
                .build();
        onlyKafka
                .perform(get("/systems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private static ConnectionOptions options(String system, Entry entry) {
        ConnectionOptions options = mock(ConnectionOptions.class);
        when(options.system()).thenReturn(system);
        when(options.entry()).thenReturn(entry);
        return options;
    }
}
