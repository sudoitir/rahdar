package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.rahdar.system.kafka.KafkaBrokers;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Brokers;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/brokers")
    public Brokers brokers(@PathVariable UUID clusterId) {
        return brokers.read(clusterId);
    }
}
