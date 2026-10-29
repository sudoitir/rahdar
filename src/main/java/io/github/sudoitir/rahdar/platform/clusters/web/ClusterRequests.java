package io.github.sudoitir.rahdar.platform.clusters.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.platform.clusters.ManagementUrlPattern;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** The write side of the cluster API. */
public final class ClusterRequests {

    private ClusterRequests() {}

    /**
     * {@code POST /clusters}: the system the cluster runs, its name, and a {@code connection} shaped by that
     * system (ADR-0219). A system Rahdar does not load, or a connection for another system, is refused as an
     * unsupported system.
     *
     * @param system the broker system's id, such as {@code kafka}
     * @param name optional display name; defaults to the first seed's or bootstrap server's host
     * @param environmentId optional environment to place the cluster in
     * @param adopt whether to save what the cluster's live nodes run as its revision 1 (ADR-0176); Artemis only
     * @param connection how to reach the cluster
     */
    public record RegisterClusterRequest(
            @NotBlank String system,
            String name,
            String description,
            UUID environmentId,
            Boolean adopt,
            @NotNull @Valid Connection connection) {

        public record Credentials(
                @NotBlank String username, @NotBlank String password) {

            /** The password is never printed. */
            @Override
            public String toString() {
                return "Credentials[username=" + username + ", password=" + SecretRedactor.MASK + "]";
            }
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean adopts() {
            return Boolean.TRUE.equals(adopt);
        }

        /** The Artemis connection; what the Jolokia registration reads. */
        @JsonIgnore
        @Schema(hidden = true)
        public ArtemisConnection artemis() {
            return (ArtemisConnection) connection;
        }

        @JsonIgnore
        @Schema(hidden = true)
        public List<String> seedUrls() {
            return artemis().seedUrls();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public Credentials credentials() {
            return artemis().credentials();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public Credentials coreCredentials() {
            return artemis().coreCredentials();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public String tlsBundle() {
            return connection.tlsBundle();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public String managementUrlPattern() {
            return artemis().managementUrlPattern();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean hasCredentials() {
            return credentials() != null;
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean hasCoreCredentials() {
            return coreCredentials() != null;
        }

        /** How a cluster is reached: a sealed union keyed by the cluster's system. */
        @JsonTypeInfo(
                use = JsonTypeInfo.Id.NAME,
                include = JsonTypeInfo.As.EXISTING_PROPERTY,
                property = "system",
                visible = true,
                defaultImpl = UnknownConnection.class)
        @JsonSubTypes({
            @JsonSubTypes.Type(value = ArtemisConnection.class, name = "artemis"),
            @JsonSubTypes.Type(value = KafkaConnection.class, name = "kafka")
        })
        @Schema(
                discriminatorProperty = "system",
                discriminatorMapping = {
                    @DiscriminatorMapping(value = "artemis", schema = ArtemisConnection.class),
                    @DiscriminatorMapping(value = "kafka", schema = KafkaConnection.class)
                },
                oneOf = {ArtemisConnection.class, KafkaConnection.class})
        public sealed interface Connection permits ArtemisConnection, KafkaConnection, UnknownConnection {

            String system();

            /** The Spring SSL bundle the cluster is reached with, or {@code null}. */
            String tlsBundle();
        }

        /** A connection for a system no installed driver serves; refused before anything else is read. */
        public record UnknownConnection(String system) implements Connection {

            @Override
            public String tlsBundle() {
                return null;
            }
        }

        /**
         * An Artemis cluster is registered from a <em>list</em> of seed URLs (ADR-0013): paste the management
         * addresses you can actually reach, and discovery matches them to broker NodeIDs. One seed is enough: the
         * rest of the cluster's nodes get a management URL derived from {@code managementUrlPattern} and proved by
         * NodeID (ADR-0175).
         *
         * @param seedUrls full Jolokia base URLs, e.g. {@code http://broker-1:8161/console/jolokia}
         * @param credentials optional HTTP Basic credentials, shared by every node
         * @param coreCredentials optional Core-protocol credentials; when omitted, the Core client reuses
         *     {@code credentials} (ADR-0026)
         * @param tlsBundle optional Spring SSL bundle name for HTTPS brokers
         * @param managementUrlPattern optional {@code scheme://{host}:port/path}; defaults to the first seed with
         *     its host replaced by {@code {host}}
         */
        public record ArtemisConnection(
                @NotBlank @Schema(allowableValues = "artemis")
                String system,

                @NotEmpty List<@NotBlank String> seedUrls,
                @Valid Credentials credentials,
                @Valid Credentials coreCredentials,
                String tlsBundle,
                String managementUrlPattern)
                implements Connection {

            @JsonIgnore
            @Schema(hidden = true)
            @AssertTrue(message = "Put the account in the Management account fields, not in the URL.") public boolean isSeedsWithoutAccount() {
                return seedUrls == null || seedUrls.stream().noneMatch(ManagementUrlPattern::hasUserInfo);
            }

            @JsonIgnore
            @Schema(hidden = true)
            @AssertTrue(
                    message =
                            "managementUrlPattern must be http(s)://{host}[:port][/path], with {host} as the whole host")
            public boolean isPatternValid() {
                return managementUrlPattern == null
                        || managementUrlPattern.isBlank()
                        || ManagementUrlPattern.isValid(managementUrlPattern);
            }
        }

        /**
         * A Kafka cluster is reached at its bootstrap servers (ADR-0219).
         *
         * @param bootstrapServers {@code host:port} entries, the port defaulting to 9092; never a scheme or an account
         * @param securityProtocol PLAINTEXT, SSL, SASL_PLAINTEXT or SASL_SSL
         * @param saslMechanism PLAIN, SCRAM-SHA-256, SCRAM-SHA-512 or OAUTHBEARER, exactly when the protocol is SASL
         * @param credentials a user name and password for PLAIN and SCRAM, a token for OAUTHBEARER
         * @param tlsBundle optional Spring SSL bundle: the trust store and a client certificate
         * @param clientId optional client id the brokers see in their logs
         */
        public record KafkaConnection(
                @NotBlank @Schema(allowableValues = "kafka") String system,
                @NotEmpty List<@NotBlank String> bootstrapServers,
                @NotBlank String securityProtocol,
                String saslMechanism,
                @Valid KafkaCredentials credentials,
                String tlsBundle,
                String clientId)
                implements Connection {}
    }

    /** A Kafka account: a user name and password, or a bearer token. Never returned. */
    public record KafkaCredentials(String username, String password, String token) {

        /** The password and the token are never printed; whether each is set is. */
        @Override
        public String toString() {
            return "KafkaCredentials[username=" + username + ", password="
                    + (password == null ? "none" : SecretRedactor.MASK) + ", token="
                    + (token == null ? "none" : SecretRedactor.MASK) + "]";
        }
    }

    /**
     * One account in {@link UpdateClusterRequest}: the password is optional, because an empty one keeps the
     * stored one.
     */
    public record AccountUpdate(String username, String password) {

        /** What a JSON {@code null} for the Core account reads as: clear it, so Core uses the management account. */
        public static final AccountUpdate CLEAR = new AccountUpdate(null, null);

        /** The password is never printed. */
        @Override
        public String toString() {
            return "AccountUpdate[username=" + username + ", password="
                    + (password == null ? "none" : SecretRedactor.MASK) + "]";
        }

        boolean named() {
            return username != null && !username.isBlank();
        }
    }

    /** Tells a Core account that is {@code null} in the request from one that is left out: that one is {@code null} in Java. */
    static final class CoreAccountDeserializer extends ValueDeserializer<AccountUpdate> {

        @Override
        public AccountUpdate deserialize(JsonParser parser, DeserializationContext context) {
            return context.readValue(parser, AccountUpdate.class);
        }

        @Override
        public AccountUpdate getNullValue(DeserializationContext context) {
            return AccountUpdate.CLEAR;
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /**
     * {@code PATCH /clusters/{id}}: change the cluster's name, description and environment, and its
     * {@code connection}, shaped by its system. A field left out is left as it is, and a secret left out keeps the
     * stored one. {@code ?dryRun=true} checks the edited connection and saves nothing. Typed confirmation of a
     * credential change is enforced in the UI.
     *
     * @param connection the new connection; its {@code system} must be the cluster's
     */
    public record UpdateClusterRequest(
            String name,
            String description,
            UUID environmentId,
            @Valid ConnectionUpdate connection) {

        /** An edit of how a cluster is reached: a sealed union keyed by the cluster's system. */
        @JsonTypeInfo(
                use = JsonTypeInfo.Id.NAME,
                include = JsonTypeInfo.As.EXISTING_PROPERTY,
                property = "system",
                visible = true,
                defaultImpl = UnknownConnectionUpdate.class)
        @JsonSubTypes({
            @JsonSubTypes.Type(value = ArtemisConnectionUpdate.class, name = "artemis"),
            @JsonSubTypes.Type(value = KafkaConnectionUpdate.class, name = "kafka")
        })
        @Schema(
                discriminatorProperty = "system",
                discriminatorMapping = {
                    @DiscriminatorMapping(value = "artemis", schema = ArtemisConnectionUpdate.class),
                    @DiscriminatorMapping(value = "kafka", schema = KafkaConnectionUpdate.class)
                },
                oneOf = {ArtemisConnectionUpdate.class, KafkaConnectionUpdate.class})
        public sealed interface ConnectionUpdate
                permits ArtemisConnectionUpdate, KafkaConnectionUpdate, UnknownConnectionUpdate {

            String system();
        }

        /** An edit for a system no installed driver serves. */
        public record UnknownConnectionUpdate(String system) implements ConnectionUpdate {}

        /**
         * An Artemis cluster's edit. {@code core} set to {@code null} clears the Core account, so Core connections use
         * the management account again.
         *
         * @param seedUrls the management URLs the operator gives as seeds; a seed no longer listed stops being one
         * @param tlsBundle a Spring SSL bundle name; empty clears it
         */
        public record ArtemisConnectionUpdate(
                @Schema(allowableValues = "artemis") String system,
                List<@NotBlank String> seedUrls,
                String managementUrlPattern,
                String tlsBundle,
                AccountUpdate management,

                @JsonDeserialize(using = CoreAccountDeserializer.class)
                @Schema(
                        nullable = true,
                        description =
                                "Null clears the Core account, so Core uses the management account. Leave the field out to keep it.")
                AccountUpdate core)
                implements ConnectionUpdate {

            /** The wire name of this connection's system. */
            public static final String SYSTEM = "artemis";

            @JsonIgnore
            @Schema(hidden = true)
            @AssertTrue(message = "an account needs a username") public boolean isAccountNamed() {
                return (management == null || management.named())
                        && (core == null || core == AccountUpdate.CLEAR || core.named());
            }

            @JsonIgnore
            @Schema(hidden = true)
            @AssertTrue(
                    message =
                            "managementUrlPattern must be http(s)://{host}[:port][/path], with {host} as the whole host")
            public boolean isPatternValid() {
                return managementUrlPattern == null || ManagementUrlPattern.isValid(managementUrlPattern);
            }

            @JsonIgnore
            @Schema(hidden = true)
            @AssertTrue(message = "Put the account in the Management account fields, not in the URL.") public boolean isSeedsWithoutAccount() {
                return seedUrls == null || seedUrls.stream().noneMatch(ManagementUrlPattern::hasUserInfo);
            }
        }

        /**
         * A Kafka cluster's edit: a field left out is left as it is, and a secret left out keeps the stored one,
         * unless the bootstrap servers, the protocol, the mechanism or the user name changed, which need it again so
         * a stored secret never reaches a server the cluster did not already use.
         */
        public record KafkaConnectionUpdate(
                @Schema(allowableValues = "kafka") String system,
                List<@NotBlank String> bootstrapServers,
                String securityProtocol,
                String saslMechanism,
                @Valid KafkaCredentials credentials,
                String tlsBundle,
                String clientId)
                implements ConnectionUpdate {}

        @JsonIgnore
        @Schema(hidden = true)
        public ArtemisConnectionUpdate artemis() {
            return connection instanceof ArtemisConnectionUpdate a ? a : null;
        }

        @JsonIgnore
        @Schema(hidden = true)
        public List<String> seedUrls() {
            return artemis() == null ? null : artemis().seedUrls();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public String managementUrlPattern() {
            return artemis() == null ? null : artemis().managementUrlPattern();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public String tlsBundle() {
            return artemis() == null ? null : artemis().tlsBundle();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public AccountUpdate management() {
            return artemis() == null ? null : artemis().management();
        }

        @JsonIgnore
        @Schema(hidden = true)
        public AccountUpdate core() {
            return artemis() == null ? null : artemis().core();
        }

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "name must not be blank") public boolean isNameValid() {
            return name == null || !name.isBlank();
        }
    }

    /**
     * {@code PATCH /clusters/{id}/nodes/{nodeId}} — give a discovered node a
     * reachable management URL, a reachable Core URL, or both. At least one is
     * required (ADR-0026); a manual value is never overwritten by discovery.
     */
    public record NodeOverrideRequest(String jolokiaUrl, String coreUrl) {

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "at least one of jolokiaUrl or coreUrl is required") public boolean isAtLeastOneUrlPresent() {
            return isPresent(jolokiaUrl) || isPresent(coreUrl);
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean hasJolokiaUrl() {
            return isPresent(jolokiaUrl);
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean hasCoreUrl() {
            return isPresent(coreUrl);
        }

        private static boolean isPresent(String value) {
            return value != null && !value.isBlank();
        }
    }
}
