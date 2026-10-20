package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.Permissions;

/** SQL over messages, the message index and capture. Module descriptor (ADR-0070). */
public final class SqlModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("sql")
            .title("SQL console")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("resources")
            .require("routing")
            .permission(PermissionDef.resource(
                    SqlPermissions.CAPTURE_WRITE,
                    "Turn message capture on and off",
                    ResourceKind.QUEUE,
                    Permissions.QUEUE_READ))
            .apiPrefix("/api/v1/clusters/{clusterId}/sql")
            .build();

    private SqlModule() {}
}
