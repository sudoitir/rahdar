package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;

/** Jolokia and Core transport, the per-node rate limit, capabilities. Module descriptor (ADR-0070). */
public final class BrokerModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("broker")
            .title("Broker connectivity")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .settingKey(BrokerSettings.RATE_LIMIT)
            .settingKey(BrokerSettings.CONNECT_TIMEOUT)
            .settingKey(BrokerSettings.READ_TIMEOUT)
            .settingKey(BrokerSettings.BULK_CAP)
            .settingKey(BrokerSettings.BULK_SUBSCRIPTION_CAP)
            .build();

    private BrokerModule() {}
}
