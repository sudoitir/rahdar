package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;

/** What only Apache Kafka has: its registration, health and the Kafka namespace (ADR-0216). Module descriptor (ADR-0070). */
public final class KafkaModule {

    /** The broker SDK's id of the system, which prefixes its kinds. */
    public static final String SYSTEM = "kafka";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("kafka")
            .title("Kafka")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .build();

    private KafkaModule() {}
}
