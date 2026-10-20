package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerDrivers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The broker SDK's drivers, loaded once from the application's own class path (ADR-0203). */
@Configuration(proxyBeanMethods = false)
class BrokerDriversConfiguration {

    @Bean(destroyMethod = "close")
    BrokerDrivers brokerDrivers() {
        return BrokerDrivers.load();
    }
}
