package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;

/** What only RabbitMQ has: its registration, health and the RabbitMQ namespace (ADR-0223). Module descriptor (ADR-0070). */
public final class RabbitMqModule {

    /** The broker SDK's id of the system, which prefixes its kinds. */
    public static final String SYSTEM = "rabbitmq";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id(SYSTEM)
            .title("RabbitMQ")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .build();

    private RabbitMqModule() {}
}
