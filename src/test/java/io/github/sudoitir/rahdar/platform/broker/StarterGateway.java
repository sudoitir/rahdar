package io.github.sudoitir.rahdar.platform.broker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerAutoConfiguration;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerConnectOptions;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerProperties;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.SslBundleTls;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** A {@link BrokerGateway} wired the way the application wires it: by OpenBroker's starter (ADR-0212). */
final class StarterGateway {

    private StarterGateway() {}

    /** Artemis's connection options, which is what the seed and data-plane probes need to know. */
    private static ConnectionOptions jolokiaSeeded() {
        ConnectionOptions options = mock(ConnectionOptions.class);
        when(options.system()).thenReturn("artemis");
        when(options.entry()).thenReturn(ConnectionOptions.Entry.JOLOKIA_SEEDS);
        return options;
    }

    /** The gateway over {@code bundles}, with a node directory and settings source nobody fills. */
    static BrokerGateway of(SslBundles bundles, BrokerTimeouts timeouts) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SslBundles.class, () -> bundles);
        context.registerBean(BrokerTimeouts.class, () -> timeouts);
        context.registerBean(RahdarCallInterceptor.class, () -> mock(RahdarCallInterceptor.class));
        context.registerBean(BrokerConnectOptionsCustomizer.class);
        context.registerBean(BrokerLinks.class, () -> new BrokerLinks(new SimpleMeterRegistry()));
        context.register(OpenBrokerAutoConfiguration.class);
        context.refresh();
        OpenBrokerConnectOptions options = context.getBean(OpenBrokerConnectOptions.class);
        return new BrokerGateway(
                context.getBean(BrokerDrivers.class),
                mock(NodeDirectory.class),
                List.of(jolokiaSeeded()),
                context.getBean(SslBundleTls.class),
                options,
                context.getBean(OpenBrokerProperties.class),
                new BrokerTelemetry(context.getBean(BrokerLinks.class), options));
    }
}
