package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.system.kafka.KafkaBrokerConfigChanges;
import io.github.sudoitir.rahdar.system.kafka.KafkaBrokerConfigs;
import io.github.sudoitir.rahdar.system.kafka.KafkaBrokers;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerConfig;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Brokers;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigPreview;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The brokers of a Kafka cluster, under the same {@code /clusters/{id}/kafka/} path as the other Kafka reads. Split
 * from {@link KafkaController} so that class stays within its dependency budget. The document keeps that class's tag,
 * so the broker read stays where clients already find it. Another system's cluster answers this with {@code 404}.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/kafka")
@Tag(name = "kafka-controller")
@RequiredArgsConstructor
public class KafkaBrokerController {

    private final KafkaBrokers brokers;
    private final KafkaBrokerConfigs configs;
    private final KafkaBrokerConfigChanges configChanges;

    @GetMapping("/brokers")
    public Brokers brokers(@PathVariable UUID clusterId) {
        return brokers.read(clusterId);
    }

    /** A broker's settings, or {@code default} for the cluster-wide default. */
    @GetMapping("/brokers/{broker}/config")
    public BrokerConfig brokerConfig(@PathVariable UUID clusterId, @PathVariable String broker) {
        return configs.read(clusterId, broker);
    }

    @PostMapping("/brokers/{broker}/config:preview")
    public ConfigPreview previewConfig(
            @PathVariable UUID clusterId, @PathVariable String broker, @RequestBody ConfigChangeRequest request) {
        return configs.preview(clusterId, broker, request);
    }

    @HeldResponse
    @PostMapping("/brokers/{broker}/config:apply")
    public ConfigPreview applyConfig(
            @PathVariable UUID clusterId, @PathVariable String broker, @RequestBody ConfigChangeRequest request) {
        return configChanges.apply(clusterId, broker, request);
    }
}
