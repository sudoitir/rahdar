package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Effective access follows Kafka's rules: deny wins, READ implies DESCRIBE, prefixes and wildcards cover. */
class KafkaAclAccessTest {

    private static Acl acl(String principal, String operation, String permission, String pattern, String name) {
        return new Acl(principal, "*", operation, permission, "topic", pattern, name);
    }

    @Test
    void readAlsoAllowsDescribe() {
        List<Acl> acls = List.of(acl("User:app", "read", "allow", "literal", "orders"));

        assertThat(KafkaAclAccess.allowed(acls, "User:app", "topic", "orders")).containsExactly("describe", "read");
        assertThat(KafkaAclAccess.allowed(acls, "User:other", "topic", "orders"))
                .isEmpty();
    }

    @Test
    void aDenyWinsOverAnAllow() {
        List<Acl> acls = List.of(
                acl("User:app", "all", "allow", "prefixed", "ord"),
                acl("User:app", "write", "deny", "literal", "orders"));

        assertThat(KafkaAclAccess.allowed(acls, "User:app", "topic", "orders"))
                .doesNotContain("write")
                .contains("read");
        assertThat(KafkaAclAccess.denied(acls, "User:app", "topic", "orders", "write"))
                .isTrue();
        assertThat(KafkaAclAccess.allowed(acls, "User:app", "topic", "payments"))
                .isEmpty();
    }

    @Test
    void theWildcardPrincipalCoversEveryUser() {
        List<Acl> acls = List.of(acl("User:*", "describe", "allow", "literal", "*"));

        assertThat(KafkaAclAccess.allowed(acls, "User:anyone", "topic", "x")).containsExactly("describe");
    }

    @Test
    void anAclThatIsNotKafkaIsRefused() {
        assertThatThrownBy(() -> KafkaAclAccess.normalised(acl("app", "read", "allow", "literal", "t")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaAclAccess.normalised(acl("User:a", "fly", "allow", "literal", "t")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(KafkaAclAccess.normalised(acl("User:a", "READ", "ALLOW", "LITERAL", "t"))
                        .operation())
                .isEqualTo("read");
    }
}
