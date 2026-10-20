package io.github.sudoitir.rahdar.feature.queues;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class QueuePermissions {

    public static final String DESTINATION_CREATE = "destination:create";

    public static final String DESTINATION_DELETE = "destination:delete";

    public static final String SUBSCRIPTION_CREATE = "subscription:create";

    public static final String SUBSCRIPTION_DELETE = "subscription:delete";

    public static final String SUBSCRIPTION_UPDATE = "subscription:update";

    public static final String SUBSCRIPTION_PAUSE = "subscription:pause";
    public static final String ROUTE_WRITE = "route:write";

    private QueuePermissions() {}
}
