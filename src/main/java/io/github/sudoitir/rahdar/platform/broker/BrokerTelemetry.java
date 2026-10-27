package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.messaging.Disposition;
import io.github.sudoitir.openbroker.messaging.LinkListener;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessagingObserver;
import io.github.sudoitir.openbroker.messaging.Observation;
import io.github.sudoitir.openbroker.messaging.OperationInfo;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.opentelemetry.OpenTelemetryMessaging;
import io.opentelemetry.api.OpenTelemetry;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What the gateway hands the broker SDK to watch messaging with (ADR-0211): OpenBroker's OpenTelemetry
 * observer, so sends, receives, processing and settlements are spans and metrics in Rahdar's own export,
 * and a listener per cluster that records link state in {@link BrokerLinks} and counts recoveries.
 *
 * <p>The observer never writes trace context into a message. Rahdar moves, retries, relays and
 * transfers messages that producers wrote, and they must arrive with exactly the properties they left
 * with; a {@code traceparent} written over the producer's would also cut the producer's own trace.
 */
@Component
public class BrokerTelemetry {

    private final MessagingObserver observer;
    private final OpenTelemetryMessaging otel;
    private final BrokerLinks links;

    @Autowired
    public BrokerTelemetry(ObjectProvider<OpenTelemetry> openTelemetry, BrokerLinks links) {
        this(openTelemetry.getIfAvailable(), links);
    }

    private BrokerTelemetry(OpenTelemetry openTelemetry, BrokerLinks links) {
        this.otel = openTelemetry == null ? null : OpenTelemetryMessaging.of(openTelemetry);
        this.observer = otel == null ? MessagingObserver.none() : new WithoutPropagation(otel);
        this.links = links;
    }

    /** Observes nothing and records no links, for a gateway built by hand. */
    public static BrokerTelemetry none() {
        return new BrokerTelemetry((OpenTelemetry) null, null);
    }

    MessagingObserver observer() {
        return observer;
    }

    /** Names what a link is for, in diagnostics. */
    void label(String linkId, String owner) {
        if (links != null) {
            links.label(linkId, owner);
        }
    }

    /** The listener a cluster's transport is watched with; empty when nothing listens. */
    LinkListener listener(UUID clusterId) {
        LinkListener recorded = links == null ? event -> {} : links.listener(clusterId);
        if (otel == null) {
            return recorded;
        }
        return event -> {
            recorded.changed(event);
            otel.changed(event);
        };
    }

    /** The OpenTelemetry observer, with every carrier write dropped. */
    record WithoutPropagation(MessagingObserver delegate) implements MessagingObserver {

        private static final BiConsumer<String, String> DROPPED = (key, value) -> {};

        @Override
        public Observation onSend(OperationInfo op, OutboundMessage message, BiConsumer<String, String> carrier) {
            return delegate.onSend(op, message, DROPPED);
        }

        @Override
        public Observation onReceive(OperationInfo op) {
            return delegate.onReceive(op);
        }

        @Override
        public Observation onProcess(OperationInfo op, Message message) {
            return delegate.onProcess(op, message);
        }

        @Override
        public Observation onSettle(OperationInfo op, Message message, Disposition disposition) {
            return delegate.onSettle(op, message, disposition);
        }
    }
}
