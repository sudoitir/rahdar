package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import java.util.List;

/**
 * One request to an Artemis node's management agent: a read, an operation or a search. Rahdar's own
 * name for what the broker SDK's Artemis driver sends, so only this module knows the driver's types.
 *
 * @param attribute the attributes a read names; null reads them all
 * @param operation an operation with its signature, for an exec
 */
public record ManagementRequest(
        String type, String mbean, List<String> attribute, String operation, List<Object> arguments) {

    /** Read one or more attributes of an MBean. */
    public static ManagementRequest read(String mbean, String... attributes) {
        return new ManagementRequest("read", mbean, List.of(attributes), null, null);
    }

    /** Read every attribute of an MBean. */
    public static ManagementRequest readAll(String mbean) {
        return new ManagementRequest("read", mbean, null, null, null);
    }

    /** Invoke an operation; {@code operation} includes its signature. */
    public static ManagementRequest exec(String mbean, String operation, Object... arguments) {
        return new ManagementRequest("exec", mbean, null, operation, List.of(arguments));
    }

    public static ManagementRequest search(String pattern) {
        return new ManagementRequest("search", pattern, null, null, null);
    }

    JolokiaRequest toDriver() {
        return new JolokiaRequest(type, mbean, attribute, operation, arguments);
    }

    /** Leaves the arguments out: an exec can carry a password, and this may reach a log. */
    @Override
    public String toString() {
        return "ManagementRequest[type=" + type + ", mbean=" + mbean + ", attribute=" + attribute + ", operation="
                + operation + ", arguments=" + (arguments == null ? "none" : arguments.size() + " withheld") + "]";
    }
}
