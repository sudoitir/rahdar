package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.kernel.core.ShutdownPhases;
import io.github.sudoitir.rahdar.kernel.core.ShutdownStep;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The broker transport's shutdown steps (operational-health spec). */
@Configuration(proxyBeanMethods = false)
class BrokerShutdownSteps {

    @Bean
    ShutdownStep brokerCallsShutdown(NodeCallLimiter limiter) {
        return new ShutdownStep("broker-calls", ShutdownPhases.BROKER_CALLS, limiter::close, limiter::open);
    }

    @Bean
    ShutdownStep subscriptionsShutdown(BrokerNotifications subscriptions) {
        return new ShutdownStep("subscriptions", ShutdownPhases.SUBSCRIPTIONS, subscriptions::closeAll);
    }

    @Bean
    ShutdownStep connectionsShutdown(BrokerGateway gateway) {
        return new ShutdownStep("core-pool", ShutdownPhases.CORE_POOL, gateway::destroy);
    }
}
