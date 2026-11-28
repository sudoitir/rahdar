package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.rahdar.system.kafka.KafkaClusterView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterView;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One read of a Kafka cluster, under the same {@code /clusters/{id}/kafka/} path as the other Kafka reads. The permit
 * is held by {@link KafkaClusterView}, so a tool that calls the service pays the same bound. Another system's cluster
 * answers this with {@code 404}.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/kafka")
@Tag(name = "kafka-controller")
@RequiredArgsConstructor
public class KafkaClusterController {

    private final KafkaClusterView cluster;

    @GetMapping("/cluster")
    public ClusterView cluster(@PathVariable UUID clusterId) {
        return cluster.read(clusterId);
    }
}
