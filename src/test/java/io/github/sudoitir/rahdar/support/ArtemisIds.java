package io.github.sudoitir.rahdar.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts.Of;

/** The ids an Artemis cluster gives its subscriptions and destinations, for a test with no broker to ask. */
public final class ArtemisIds {

    public static final Of OF = new Of() {
        @Override
        public String subscription(String name) {
            return subscriptionId(name);
        }

        @Override
        public String destination(String name) {
            return destinationId(name);
        }

        @Override
        public KindName kindLabel(String id) {
            return id == null
                    ? null
                    : id.startsWith("artemis.queue/")
                            ? new KindName("Queue", "Queues")
                            : new KindName("Address", "Addresses");
        }
    };

    private ArtemisIds() {}

    public static String subscriptionId(String name) {
        return name == null ? null : "artemis.queue/" + name;
    }

    public static String destinationId(String name) {
        return name == null ? null : "artemis.address/" + name;
    }

    /** Makes the mock build Artemis ids, for any cluster. */
    public static void stub(ResourceIdTexts ids) {
        when(ids.of(any())).thenReturn(OF);
        when(ids.subscription(any(), anyString())).thenAnswer(call -> subscriptionId(call.getArgument(1)));
        when(ids.destination(any(), anyString())).thenAnswer(call -> destinationId(call.getArgument(1)));
    }
}
