package io.github.sudoitir.rahdar.feature.bulk;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.queues.QueuePermissions;
import java.util.Locale;

/** The single-queue operation a bulk run applies, and the permission it needs. */
public enum BulkOperation {
    PAUSE(QueuePermissions.SUBSCRIPTION_PAUSE, false),
    RESUME(QueuePermissions.SUBSCRIPTION_PAUSE, false),
    PURGE(MessagePermissions.SUBSCRIPTION_PURGE, true),
    DELETE(QueuePermissions.SUBSCRIPTION_DELETE, true);

    private final String permission;
    private final boolean destructive;

    BulkOperation(String permission, boolean destructive) {
        this.permission = permission;
        this.destructive = destructive;
    }

    public String permission() {
        return permission;
    }

    /** Destroys messages, so its estimate is checked against {@code safety.bulk-cap}. */
    public boolean destructive() {
        return destructive;
    }

    /** The run's audit action, {@code bulk.<operation>}. */
    public String auditAction() {
        return "bulk." + name().toLowerCase(Locale.ROOT);
    }
}
