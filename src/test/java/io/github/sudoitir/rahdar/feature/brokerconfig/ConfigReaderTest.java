package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigReader.NodeConfig;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ManagementRequest;
import io.github.sudoitir.rahdar.system.artemis.ManagementResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The address-setting match set a comparison reads: capped, with {@code #} always in. */
class ConfigReaderTest {

    private final JsonMapper mapper = new JsonMapper();
    private final ConfigReader reader = new ConfigReader(mapper);

    @Test
    void theDefaultMatchComesFirstAndBrokerPlumbingIsLeftOut() {
        List<String> matches = ConfigReader.matchesFor(List.of("orders", "activemq.notifications", "$sys.x", " "));

        assertThat(matches).containsExactly("#", "orders");
    }

    @Test
    void aReadIsOneBatchedRequestAndTheCapKeepsTheDefaultMatch() {
        List<String> matches = new ArrayList<>(
                IntStream.range(0, 40).mapToObj(i -> "addr-" + i).toList());
        matches.add("#");
        ArtemisNode client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn("m");
        List<List<ManagementRequest>> batches = new ArrayList<>();
        when(client.batch(anyList())).thenAnswer(call -> {
            List<ManagementRequest> requests = call.getArgument(0);
            batches.add(requests);
            return requests.stream().map(r -> mock(ManagementResponse.class)).toList();
        });
        when(client.parsed(any())).thenAnswer(call -> mapper.createObjectNode());

        NodeConfig config = reader.read(client, matches);

        assertThat(batches).hasSize(1);
        assertThat(config.matchesCompared()).isEqualTo(ConfigReader.MATCH_CAP);
        assertThat(config.matchesAvailable()).isEqualTo(41);
        assertThat(config.addressSettings()).hasSize(ConfigReader.MATCH_CAP);
        assertThat(config.addressSettings().get(0).get("match").asString()).isEqualTo("#");
    }
}
