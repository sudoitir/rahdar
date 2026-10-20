package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The roles the broker's own user management holds for one account, read with
 * {@code listUser(username)} on the broker MBean. Security-setting permissions are checked
 * against the account Rahdar connects to Core with, so those roles are the ones a recommended
 * security setting has to name (ADR-0177).
 *
 * <p>The operation exists on the basic security manager and on the properties login module.
 * Any other module (LDAP, a custom one) refuses it, and so does an account without management
 * rights; either is a normal answer rather than a failure, so it is returned as a reason the
 * operator can read, never thrown. A blank username is never sent: {@code listUser("")} lists
 * every user on the broker.
 */
@Component
public class BrokerAccountRoles {

    /** The roles read, or why they could not be. Exactly one of the two is set. */
    public record Read(List<String> roles, String unreadableReason) {

        public Read {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public static Read of(List<String> roles) {
            return new Read(roles, null);
        }

        public static Read unreadable(String reason) {
            return new Read(List.of(), reason);
        }

        public boolean readable() {
            return unreadableReason == null;
        }
    }

    public Read read(ArtemisNode client, String username) {
        if (username == null || username.isBlank()) {
            return Read.unreadable("Rahdar connects to the broker without an account");
        }
        JsonNode users;
        try {
            users = client.execOnBrokerParsed("listUser(java.lang.String)", username);
        } catch (BrokerConnectionException e) {
            return Read.unreadable(reasonFor(e, username));
        }
        List<String> roles = new ArrayList<>();
        for (JsonNode user : users) {
            if (username.equals(user.path("username").asString(null))) {
                user.path("roles").forEach(role -> roles.add(role.asString()));
            }
        }
        if (roles.isEmpty()) {
            return Read.unreadable("the broker lists no roles for " + username);
        }
        return Read.of(roles.stream().distinct().sorted().toList());
    }

    private static String reasonFor(BrokerConnectionException e, String username) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return switch (e.kind()) {
            case CREDENTIALS_REJECTED -> "the broker refused to let this account read users";
            case BAD_RESPONSE -> {
                if (message.contains("SecurityException") || message.contains("status 403")) {
                    yield "this account is not allowed to read the broker's users";
                }
                if (message.contains("No operation") || message.contains("InstanceNotFound")) {
                    yield "this broker release does not offer listUser";
                }
                if (message.contains("does not exist")) {
                    yield "the broker's user store has no user " + username;
                }
                yield "the broker's login module does not support listing users (LDAP and custom modules do not)";
            }
            default ->
                "the broker could not be asked (" + e.kind().defaultMessage().replaceAll("\\.$", "") + ")";
        };
    }
}
