package io.github.sudoitir.rahdar.support;

import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.ArtemisConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Credentials;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.UUID;

/** Registration requests for the clusters a test runs against. */
public final class Registrations {

    private Registrations() {}

    /** An Artemis registration from Jolokia seeds. */
    public static RegisterClusterRequest artemis(
            List<String> seedUrls,
            String name,
            String description,
            Credentials credentials,
            Credentials coreCredentials,
            String tlsBundle,
            String managementUrlPattern,
            UUID environmentId,
            Boolean adopt) {
        return new RegisterClusterRequest(
                ArtemisModule.SYSTEM,
                name,
                description,
                environmentId,
                adopt,
                new ArtemisConnection(
                        ArtemisModule.SYSTEM, seedUrls, credentials, coreCredentials, tlsBundle, managementUrlPattern));
    }
}
