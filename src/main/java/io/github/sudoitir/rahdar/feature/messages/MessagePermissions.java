package io.github.sudoitir.rahdar.feature.messages;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class MessagePermissions {

    public static final String MESSAGE_READ = "message:read";

    public static final String MESSAGE_SEND = "message:send";

    public static final String MESSAGE_MOVE = "message:move";

    public static final String MESSAGE_DELETE = "message:delete";

    public static final String SUBSCRIPTION_PURGE = "subscription:purge";

    private MessagePermissions() {}
}
