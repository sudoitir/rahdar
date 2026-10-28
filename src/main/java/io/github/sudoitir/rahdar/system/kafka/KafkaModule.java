package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;

/** What only Apache Kafka has: its registration, health and the Kafka namespace (ADR-0216). Module descriptor (ADR-0070). */
public final class KafkaModule {

    /** The broker SDK's id of the system, which prefixes its kinds. */
    public static final String SYSTEM = "kafka";

    public static final String ACCESS_RULE_READ = "access-rule:read";

    public static final String ACCESS_RULE_WRITE = "access-rule:write";

    public static final String TRANSACTION_READ = "transaction:read";

    public static final String TRANSACTION_ABORT = "transaction:abort";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("kafka")
            .title("Kafka")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .permission(PermissionDef.cluster(ACCESS_RULE_READ, "See a Kafka cluster's ACLs", Permissions.CLUSTER_READ))
            .permission(
                    PermissionDef.cluster(ACCESS_RULE_WRITE, "Add and remove a Kafka cluster's ACLs", ACCESS_RULE_READ))
            .permission(PermissionDef.cluster(
                    TRANSACTION_READ, "See a Kafka cluster's transactions", Permissions.CLUSTER_READ))
            .permission(PermissionDef.cluster(
                    TRANSACTION_ABORT, "Abort a Kafka cluster's hanging transactions", TRANSACTION_READ))
            .build();

    private KafkaModule() {}
}
