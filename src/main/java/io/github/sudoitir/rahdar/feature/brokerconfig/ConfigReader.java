package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads one node's effective configuration in a single batched Jolokia POST
 * (non-negotiable #1), for {@code ConfigDiffService} to compare against another
 * node's.
 *
 * <p>Everything here was verified against the dev pair in the slice-0 surface check
 * (`docs/broker-management-notes.md` §14):
 *
 * <ul>
 *   <li>A <b>passive backup answers in full</b> — the same 90 attributes and 78
 *       operations as the primary — so no part of this is capability-gated.
 *   <li>{@code getAddressSettingsAsJSON} resolves the <em>effective</em> settings for
 *       any match string, including one the node hosts no address for. So the backup
 *       answers for the primary's addresses even though its own {@code AddressNames}
 *       is empty while it is passive.
 *   <li>There is <b>no operation that enumerates configured match patterns</b>, so the
 *       compared set is {@code #} plus the address names both sides report — capped,
 *       and the cap is disclosed rather than applied silently.
 *   <li>Acceptors come from the {@code AcceptorsAsJSON} attribute rather than the
 *       {@code component=acceptors,*} MBean search: it batches with everything else
 *       and carries the parameters, where the search costs a second round trip and
 *       returns only the queried node's own acceptor.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ConfigReader {

    private final ObjectMapper mapper;

    /** Always compared, and always first. */
    public static final String DEFAULT_MATCH = "#";

    /** Upper bound on address settings compared per node. Disclosed, never silent. */
    public static final int MATCH_CAP = 25;

    /**
     * One node's configuration.
     *
     * @param brokerAttributes the broker MBean's attributes
     * @param addressSettings effective settings, one element per compared match, each
     *     carrying a {@code match} field so the comparison can key on it
     * @param securitySettings roles for {@code #}
     * @param acceptors the parsed {@code AcceptorsAsJSON}
     * @param active whether the node reports itself as serving; a passive node with a
     *     reduced surface must be stated rather than diffed
     * @param matchesCompared how many address-setting matches were read
     * @param matchesAvailable how many were known about
     */
    public record NodeConfig(
            JsonNode brokerAttributes,
            JsonNode addressSettings,
            JsonNode securitySettings,
            JsonNode acceptors,
            boolean active,
            int matchesCompared,
            int matchesAvailable) {}

    /**
     * Read one node. {@code matches} is the address-setting match set to resolve,
     * already unioned across both nodes by the caller so the two sides compare
     * like for like.
     *
     * @throws BrokerConnectionException if the node cannot be read at all — the caller
     *     marks that side unavailable rather than rendering half a diff
     */
    public NodeConfig read(ArtemisNode client, List<String> matches) {
        String mbean = client.resolveBrokerObjectName();

        List<String> compared = cap(matches);
        List<JolokiaRequest> requests = new ArrayList<>();
        requests.add(JolokiaRequest.read(mbean, "Active", "AcceptorsAsJSON"));
        requests.add(JolokiaRequest.readAll(mbean));
        requests.add(JolokiaRequest.exec(mbean, "getRolesAsJSON(java.lang.String)", DEFAULT_MATCH));
        for (String match : compared) {
            requests.add(JolokiaRequest.exec(mbean, "getAddressSettingsAsJSON(java.lang.String)", match));
        }

        List<JolokiaResponse> responses = client.batch(requests);
        if (responses.size() != requests.size()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    "The node answered " + responses.size() + " of " + requests.size() + " batched requests.");
        }

        JsonNode head = client.parsed(responses.get(0));
        boolean active = head.path("Active").asBoolean(false);
        JsonNode acceptors = parseEmbedded(head.get("AcceptorsAsJSON"));
        JsonNode attributes = client.parsed(responses.get(1));
        JsonNode roles = client.parsed(responses.get(2));

        ArrayNode settings = mapper.createArrayNode();
        for (int i = 0; i < compared.size(); i++) {
            JsonNode resolved = client.parsed(responses.get(3 + i));
            if (resolved != null && resolved.isObject()) {
                ObjectNode element = (ObjectNode) resolved.deepCopy();
                // The identity the comparison keys on, so reordering is not drift.
                element.put("match", compared.get(i));
                settings.add(element);
            }
        }

        return new NodeConfig(attributes, settings, roles, acceptors, active, compared.size(), matches.size());
    }

    /**
     * The match set to compare: {@code #} first, then the cluster's known addresses.
     *
     * <p>These come from the caller's {@code queue_snapshot} cache, not from a broker
     * read, so a comparison still costs exactly one batched POST per node. Reading
     * {@code AddressNames} off the broker first would need a second round trip per
     * node — and a passive backup reports none anyway, so the cache is the better
     * source in both cases.
     */
    public static List<String> matchesFor(Collection<String> knownAddresses) {
        Set<String> matches = new LinkedHashSet<>();
        matches.add(DEFAULT_MATCH);
        for (String address : knownAddresses) {
            // Broker plumbing, not operator-facing configuration.
            if (address != null
                    && !address.isBlank()
                    && !address.startsWith("activemq.")
                    && !address.startsWith("$sys.")) {
                matches.add(address);
            }
        }
        return List.copyOf(matches);
    }

    /** {@code #} is always kept, even when the cap truncates the rest. */
    private static List<String> cap(List<String> matches) {
        if (matches.size() <= MATCH_CAP) {
            return matches;
        }
        List<String> capped = new ArrayList<>();
        capped.add(DEFAULT_MATCH);
        for (String match : matches) {
            if (capped.size() >= MATCH_CAP) {
                break;
            }
            if (!DEFAULT_MATCH.equals(match)) {
                capped.add(match);
            }
        }
        return List.copyOf(capped);
    }

    /** {@code AcceptorsAsJSON} is a JSON-encoded string inside the attribute (Phase 0). */
    private JsonNode parseEmbedded(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? mapper.readTree(value.asString()) : value;
    }
}
