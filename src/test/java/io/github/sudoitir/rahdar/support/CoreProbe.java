package io.github.sudoitir.rahdar.support;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.messaging.Blob;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.SendTarget;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import java.util.List;
import java.util.UUID;

/** One Core call to the shared broker through the gateway, for a test that needs the Core meters to exist. */
public final class CoreProbe {

    private CoreProbe() {}

    public static void send(BrokerGateway gateway, UUID cluster) {
        NodeSpec node = new NodeSpec(
                NodeId.of("core-probe"),
                List.of(new Endpoint(Plane.DATA, BrokerGateway.CORE, ArtemisIntegrationTest.coreUrl())));
        try (ClusterConnection connection = gateway.dataPlane(
                List.of(node),
                new CoreConnectionSettings(
                        cluster,
                        ArtemisIntegrationTest.BROKER_USER,
                        ArtemisIntegrationTest.BROKER_PASSWORD,
                        null,
                        true))) {
            connection
                    .messages()
                    .send(
                            SendTarget.destination(ResourceId.of("artemis.address", "rahdar.core-probe")),
                            OutboundMessage.of(MessageBody.of(Blob.of(new byte[0]))));
        }
    }
}
