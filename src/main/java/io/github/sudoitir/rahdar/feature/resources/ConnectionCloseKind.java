package io.github.sudoitir.rahdar.feature.resources;

/**
 * The three closes Rahdar exposes (ADR-0057). One enum shared by the HTTP API and
 * the single MCP {@code connection_action} tool, so a kind cannot be reachable
 * through one and not the other.
 *
 * <p>All three take the same permission, and it is deliberately not any message
 * permission: being allowed to delete a queue's messages says nothing about being
 * allowed to disconnect the application producing them.
 */
public enum ConnectionCloseKind {
    /** Close one connection, and with it every session and consumer it carries. */
    CONNECTION("CLOSE_CONNECTION", "CONNECTION"),
    /** Close one channel within a connection. */
    CHANNEL("CLOSE_SESSION", "CHANNEL"),
    /** Close the connection behind one consumer, found from the consumer's own id. */
    CONSUMER("CLOSE_CONSUMER_CONNECTION", "CONSUMER"),
    /** Close every consumer connection bound to a destination, on every live node. */
    DESTINATION("CLOSE_DESTINATION", "DESTINATION");

    private final String auditName;
    private final String targetType;

    ConnectionCloseKind(String auditName, String targetType) {
        this.auditName = auditName;
        this.targetType = targetType;
    }

    public String auditName() {
        return auditName;
    }

    public String targetType() {
        return targetType;
    }

    public String permission() {
        return ResourcePermissions.CONNECTION_CLOSE;
    }

    /**
     * Whether this kind names a node rather than a cluster. A connection id is
     * issued by, and meaningful only on, one node — so a close by id is the one
     * mutating operation in Rahdar that is not a cluster-wide fan-out (D1).
     */
    public boolean nodeScoped() {
        return this != DESTINATION;
    }

    /** Whether this kind affects an unbounded number of connections, and so is capped (D6). */
    public boolean capped() {
        return this == DESTINATION;
    }
}
