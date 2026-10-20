package io.github.sudoitir.rahdar.feature.resources;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class ResourcePermissions {

    public static final String CONNECTION_READ = "connection:read";

    public static final String CONNECTION_CLOSE = "connection:close";

    public static final String ADDRESS_CREATE = "address:create";

    public static final String ADDRESS_DELETE = "address:delete";

    public static final String QUEUE_CREATE = "queue:create";

    public static final String QUEUE_DELETE = "queue:delete";

    public static final String QUEUE_UPDATE = "queue:update";

    public static final String QUEUE_PAUSE = "queue:pause";

    public static final String DIVERT_WRITE = "divert:write";

    private ResourcePermissions() {}
}
