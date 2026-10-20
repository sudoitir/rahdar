package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.rahdar.feature.brokerconfig.ObservedNodeConfig.ObservedBridge;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.BridgeOperations;
import io.github.sudoitir.rahdar.system.artemis.BridgeRow;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The management calls behind an apply: which request each write sends, and how the two batched reads of a
 * node are turned into the configuration the broker actually holds. The broker is a scripted client, so
 * these pin the wire shape and the parsing, not a broker's behaviour.
 */
class BrokerConfigOperationsTest {

    private static final String BROKER = "org.apache.activemq.artemis:broker=\"b1\"";
    private static final UUID NODE = UUID.randomUUID();

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final QueueLifecycleOperations queueOps = mock(QueueLifecycleOperations.class);
    private final DivertOperations divertOps = mock(DivertOperations.class);
    private final BridgeOperations bridgeOps = mock(BridgeOperations.class);
    private final ArtemisNode client = mock(ArtemisNode.class);
    private final BrokerConfigOperations ops = new BrokerConfigOperations(mapper, queueOps, divertOps, bridgeOps);

    /** Requests the scripted broker was sent, one list per batched POST. */
    private final List<List<JolokiaRequest>> batches = new ArrayList<>();

    private final List<Rule> rules = new ArrayList<>();
    private boolean dropLastResponse;

    private record Rule(Predicate<JolokiaRequest> when, JolokiaResponse answer) {}

    @BeforeEach
    void setUp() {
        when(client.resolveBrokerObjectName()).thenReturn(BROKER);
        when(client.parsed(any())).thenAnswer(invocation -> {
            JolokiaResponse res = invocation.getArgument(0);
            if (!res.ok()) {
                throw new BrokerConnectionException(BrokerConnectionException.Kind.BAD_RESPONSE, "entry failed");
            }
            return res.valueParsed(mapper);
        });
        when(client.batch(any())).thenAnswer(invocation -> {
            List<JolokiaRequest> requests = invocation.getArgument(0);
            batches.add(requests);
            List<JolokiaResponse> out = new ArrayList<>();
            for (JolokiaRequest request : requests) {
                out.add(answerFor(request));
            }
            if (dropLastResponse) {
                out.removeLast();
            }
            return out;
        });
    }

    private JolokiaResponse answerFor(JolokiaRequest request) {
        for (Rule rule : rules) {
            if (rule.when().test(request)) {
                return rule.answer();
            }
        }
        return failure("no such MBean");
    }

    private void on(Predicate<JolokiaRequest> when, JolokiaResponse answer) {
        rules.add(new Rule(when, answer));
    }

    private JolokiaResponse json(String value) {
        return new JolokiaResponse(200, mapper.readTree(value), null, null, null);
    }

    /** A JSON array of strings, as Jolokia answers a search. */
    private JolokiaResponse names(String... names) {
        return json(mapper.writeValueAsString(List.of(names)));
    }

    /** A JSON document delivered as a string value, which is how the broker answers its *AsJSON operations. */
    private JolokiaResponse asString(String document) {
        return json(mapper.writeValueAsString(document));
    }

    private static JolokiaResponse failure(String error) {
        return new JolokiaResponse(500, null, error, "java.lang.Exception", null);
    }

    private static Predicate<JolokiaRequest> exec(String operation, String firstArgument) {
        return r -> "exec".equals(r.type())
                && operation.equals(r.operation())
                && firstArgument.equals(String.valueOf(r.arguments().get(0)));
    }

    private static Predicate<JolokiaRequest> readOf(String mbean) {
        return r -> "read".equals(r.type()) && mbean.equals(r.mbean());
    }

    private static Predicate<JolokiaRequest> search(String pattern) {
        return r -> "search".equals(r.type()) && pattern.equals(r.mbean());
    }

    private static JolokiaResponse ok() {
        return new JolokiaResponse(200, null, null, null, null);
    }

    // ---- writes ---------------------------------------------------------------

    @Test
    void addAddressSettingsSendsTheMatchAndTheValuesAsJson() {
        when(client.single(any())).thenReturn(ok());

        ops.addAddressSettings(client, BROKER, "orders.#", Map.of("max-size-bytes", 1024));

        ArgumentCaptor<JolokiaRequest> sent = ArgumentCaptor.forClass(JolokiaRequest.class);
        verify(client).single(sent.capture());
        assertThat(sent.getValue().operation()).isEqualTo(BrokerConfigOperations.ADD_ADDRESS_SETTINGS);
        assertThat(sent.getValue().arguments()).containsExactly("orders.#", "{\"max-size-bytes\":1024}");
    }

    @Test
    void aRefusedWriteRaisesInsteadOfPassing() {
        when(client.single(any())).thenReturn(failure("something else broke"));

        assertThatThrownBy(() -> ops.removeAddressSettings(client, BROKER, "orders.#"))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("removeAddressSettings failed");
        assertThatThrownBy(() -> ops.removeSecuritySettings(client, BROKER, "orders.#"))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("removeSecuritySettings failed");
        assertThatThrownBy(() -> ops.addAddressSettings(client, BROKER, "m", Map.of()))
                .isInstanceOf(BrokerConnectionException.class);
        assertThatThrownBy(() -> ops.addSecuritySettings(client, BROKER, "m", Map.of()))
                .isInstanceOf(BrokerConnectionException.class);
    }

    @Test
    void aKnownRefusalKeepsItsKind() {
        when(client.single(any())).thenReturn(failure("AMQ229203: Address Does Not Exist"));

        assertThatThrownBy(() -> ops.removeAddressSettings(client, BROKER, "x"))
                .isInstanceOfSatisfying(
                        ManagementRefusal.class, e -> assertThat(e.kind()).isEqualTo(ManagementRefusal.Kind.ALREADY));
    }

    @Test
    void removeSettingsSendTheMatch() {
        when(client.single(any())).thenReturn(ok());

        ops.removeAddressSettings(client, BROKER, "a.#");
        ops.removeSecuritySettings(client, BROKER, "s.#");

        ArgumentCaptor<JolokiaRequest> sent = ArgumentCaptor.forClass(JolokiaRequest.class);
        verify(client, times(2)).single(sent.capture());
        assertThat(sent.getAllValues().get(0).operation()).isEqualTo(BrokerConfigOperations.REMOVE_ADDRESS_SETTINGS);
        assertThat(sent.getAllValues().get(0).arguments()).containsExactly("a.#");
        assertThat(sent.getAllValues().get(1).operation()).isEqualTo(BrokerConfigOperations.REMOVE_SECURITY_SETTINGS);
        assertThat(sent.getAllValues().get(1).arguments()).containsExactly("s.#");
    }

    @Test
    void securitySettingsSendOneCommaJoinedSortedRoleListPerPermissionTypeInOrder() {
        when(client.single(any())).thenReturn(ok());

        ops.addSecuritySettings(
                client,
                BROKER,
                "orders.#",
                Map.of(
                        PermissionType.SEND, Set.of("writers", "admins"),
                        PermissionType.CONSUME, Set.of("readers")));

        ArgumentCaptor<JolokiaRequest> sent = ArgumentCaptor.forClass(JolokiaRequest.class);
        verify(client).single(sent.capture());
        List<Object> args = sent.getValue().arguments();
        assertThat(args).hasSize(PermissionType.values().length + 1);
        assertThat(args.get(0)).isEqualTo("orders.#");
        assertThat(args.get(PermissionType.SEND.ordinal() + 1)).isEqualTo("admins,writers");
        assertThat(args.get(PermissionType.CONSUME.ordinal() + 1)).isEqualTo("readers");
        // A permission nobody holds is an empty string, which is how the broker is told "no roles".
        assertThat(args.get(PermissionType.MANAGE.ordinal() + 1)).isEqualTo("");
    }

    @Test
    void divertBridgeAddressAndQueueWritesDelegate() {
        Map<String, Object> config = Map.of("name", "d");

        ops.createDivert(client, BROKER, config);
        ops.destroyDivert(client, BROKER, "d");
        ops.createBridge(client, BROKER, config);
        ops.destroyBridge(client, BROKER, "b");
        ops.createAddress(client, BROKER, "orders", Set.of("MULTICAST", "ANYCAST"));
        ops.updateAddress(client, BROKER, "orders", Set.of("MULTICAST", "ANYCAST"));
        ops.createQueue(client, BROKER, config);

        verify(divertOps).createVerified(client, BROKER, config);
        verify(divertOps).destroyDivert(client, BROKER, "d");
        verify(bridgeOps).createVerified(client, BROKER, config);
        verify(bridgeOps).destroyBridge(client, BROKER, "b");
        verify(queueOps).createAddress(client, BROKER, "orders", "ANYCAST,MULTICAST");
        verify(queueOps).updateAddress(client, BROKER, "orders", "ANYCAST,MULTICAST");
        verify(queueOps).createQueue(client, BROKER, config);
    }

    @Test
    void updateQueuePatchesTheDeclaredKeysWithoutAutoCreateAddress() {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("name", "orders.q");
        declared.put("address", "orders");
        declared.put("routing-type", "ANYCAST");
        declared.put("auto-create-address", true);
        declared.put("max-consumers", 3);

        ops.updateQueue(client, BROKER, declared);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(queueOps)
                .updateQueue(
                        eq(client),
                        eq(BROKER),
                        eq(ArtemisMBeans.queue(BROKER, "orders", "orders.q", "ANYCAST")),
                        patch.capture());
        assertThat(patch.getValue())
                .containsEntry("max-consumers", 3)
                .doesNotContainKey("auto-create-address")
                .containsKey("name");
    }

    // ---- the read scope ---------------------------------------------------------

    @Test
    void theScopeCollectsOwnedAndDeclaredMatchesAndEndpoints() {
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1,
                List.of(new AddressDecl("orders", Set.of("ANYCAST"), List.of())),
                List.of(new AddressSettingDecl("orders.#", Map.of())),
                List.of(new SecuritySettingDecl("orders.#", Map.of())),
                List.of(
                        new DivertDecl("d1", "orders", "audit", null, false, null, null, null),
                        new DivertDecl("d2", null, null, null, false, null, null, null)),
                List.of());

        ReadScope scope = ReadScope.of(doc, Set.of("owned.#"), Set.of("owned.sec"));

        assertThat(scope.addressSettingMatches()).containsExactlyInAnyOrder("owned.#", "orders.#");
        assertThat(scope.securitySettingMatches()).containsExactlyInAnyOrder("owned.sec", "orders.#");
        assertThat(scope.divertNames()).containsExactlyInAnyOrder("d1", "d2");
        assertThat(scope.addresses()).containsOnlyKeys("orders");
        assertThat(scope.divertEndpoints()).containsExactlyInAnyOrder("orders", "audit");
        assertThat(scope.bridges()).isEmpty();
    }

    // ---- the observed read --------------------------------------------------------

    private static ReadScope scope(
            Set<String> settings,
            Set<String> security,
            Map<String, AddressDecl> addresses,
            Set<String> endpoints,
            List<BridgeDecl> bridges) {
        return new ReadScope(settings, security, Set.of(), addresses, endpoints, bridges);
    }

    private static BridgeDecl bridgeNamed(String name) {
        return new BridgeDecl(
                name,
                "q",
                "fwd",
                null,
                null,
                List.of("c1"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void readsANodeInTwoBatchedPostsAndBuildsItsConfiguration() {
        String orders = ArtemisMBeans.address(BROKER, "orders");
        String ordersQueue = ArtemisMBeans.queue(BROKER, "orders", "orders.q", "ANYCAST");
        String divertMbean = BROKER + ",component=addresses,address=\"orders\",subcomponent=diverts,divert=\"d1\"";
        String bridgeA = BROKER + ",component=bridges,name=\"to-dc2-0\"";
        String bridgeB = BROKER + ",component=bridges,name=\"to-dc2-1\"";
        String stray = BROKER + ",component=bridges,name=\"stray\"";
        String bound = BROKER + ",component=addresses,address=\"orders\",subcomponent=queues,routing-type=\"anycast\","
                + "queue=\"orders.extra\"";

        on(
                r -> "read".equals(r.type())
                        && r.attribute() != null
                        && r.attribute().contains("Active"),
                json(
                        "{\"Active\":true,\"AddressNames\":[\"orders\",\"orders.eu\",\"activemq.notifications\",\"other\"],"
                                + "\"QueueNames\":[\"orders.q\",\"orders.extra\",\"loose\"],\"DivertNames\":[\"d1\"]}"));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "#"), json("\"{\\\"max-size-bytes\\\":10}\""));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "orders.#"), asString("[]"));
        on(
                exec(BrokerConfigOperations.GET_ROLES, "#"),
                json("\"[{\\\"name\\\":\\\"admins\\\",\\\"send\\\":true,"
                        + "\\\"consume\\\":true},{\\\"name\\\":\\\"\\\",\\\"send\\\":true},{\\\"name\\\":\\\"guests\\\","
                        + "\\\"browse\\\":true}]\""));
        on(search(ArtemisMBeans.divertsPattern(BROKER)), names(divertMbean));
        on(search(ArtemisMBeans.bridgesPattern(BROKER)), names(bridgeA, bridgeB, stray));
        on(
                readOf(divertMbean),
                json("{\"UniqueName\":\"d1\",\"Address\":\"orders\",\"ForwardingAddress\":\"audit\","
                        + "\"RoutingType\":\"multicast\",\"Exclusive\":true}"));
        on(
                readOf(bridgeA),
                json("{\"Name\":\"to-dc2-0\",\"QueueName\":\"q\",\"ForwardingAddress\":\"fwd\","
                        + "\"StaticConnectors\":[\"c1\"],\"Started\":true,\"Connected\":true}"));
        on(readOf(bridgeB), json("{\"Name\":\"to-dc2-1\",\"QueueName\":\"q\",\"Started\":true,\"Connected\":false}"));
        on(readOf(stray), json("{\"Name\":\"stray\",\"Started\":false}"));
        on(readOf(orders), json("{\"RoutingTypes\":[\"ANYCAST\"],\"AddressSize\":42,\"MessageCount\":7}"));
        on(readOf(ArtemisMBeans.address(BROKER, "orders.eu")), json("{\"RoutingTypes\":[\"MULTICAST\"]}"));
        on(readOf(ordersQueue), json("{\"Name\":\"orders.q\"}"));
        on(
                search(orders + ",subcomponent=queues,*"),
                names(
                        bound,
                        "not an object name",
                        BROKER
                                + ",component=addresses,address=\"orders\",subcomponent=queues,queue=\"no-routing-type\""));
        when(queueOps.toQueueConfig(any())).thenReturn(Map.of("name", "orders.q", "address", "orders"));

        ReadScope scope = scope(
                Set.of("orders.#"),
                Set.of(),
                Map.of(
                        "orders",
                        new AddressDecl(
                                "orders",
                                Set.of("ANYCAST"),
                                List.of(new QueueDecl(
                                        "orders.q", "ANYCAST", null, true, null, null, null, null, null)))),
                Set.of("orders"),
                List.of(bridgeNamed("to-dc2")));

        ObservedNodeConfig observed = ops.read(client, NODE, "node-a", scope);

        assertThat(batches).hasSize(2);
        assertThat(observed.live()).isTrue();
        assertThat(observed.nodeId()).isEqualTo(NODE);
        assertThat(observed.nodeName()).isEqualTo("node-a");
        assertThat(observed.unavailableReason()).isNull();
        // The default match is always read, the declared one after it; a non-object answer is no setting.
        assertThat(observed.addressSettings()).containsOnlyKeys("#").containsEntry("#", Map.of("max-size-bytes", 10));
        assertThat(observed.securitySettings().get("#"))
                .containsOnlyKeys(PermissionType.SEND, PermissionType.CONSUME, PermissionType.BROWSE)
                .containsEntry(PermissionType.SEND, Set.of("admins"))
                .containsEntry(PermissionType.BROWSE, Set.of("guests"));
        assertThat(observed.diverts()).containsOnlyKeys("d1");
        assertThat(observed.diverts().get("d1").exclusive()).isTrue();
        assertThat(observed.diverts().get("d1").forwardingAddress()).isEqualTo("audit");
        // The declared bridge's workers are one item; an undeclared one is kept under its own name.
        assertThat(observed.bridges()).containsOnlyKeys("to-dc2", "stray");
        ObservedBridge grouped = observed.bridges().get("to-dc2");
        assertThat(grouped.instances()).isEqualTo(2);
        assertThat(grouped.started()).isTrue();
        assertThat(grouped.connected()).isFalse();
        assertThat(observed.bridges().get("stray").started()).isFalse();
        // Addresses: the read ones carry their routing types and usage, the rest are known by name.
        assertThat(observed.addresses().get("orders")).containsExactly("ANYCAST");
        assertThat(observed.addresses().get("orders.eu")).containsExactly("MULTICAST");
        assertThat(observed.addresses().get("other")).isEmpty();
        assertThat(observed.addressUsage().get("orders").bytes()).isEqualTo(42);
        assertThat(observed.addressUsage().get("orders").messages()).isEqualTo(7);
        // A declared queue is read in full; a queue bound to a declared address is named from its MBean;
        // any other is known by name alone.
        assertThat(observed.queues().get("orders.q")).containsEntry("address", "orders");
        assertThat(observed.queues().get("orders.extra"))
                .containsEntry("name", "orders.extra")
                .containsEntry("routing-type", "ANYCAST");
        assertThat(observed.queues().get("loose")).hasSize(1).containsEntry("name", "loose");
    }

    @Test
    void aNodeThatIsNotActiveIsReadAsNotLive() {
        on(
                r -> "read".equals(r.type())
                        && r.attribute() != null
                        && r.attribute().contains("Active"),
                json("{\"Active\":false}"));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "#"), failure("nope"));
        on(exec(BrokerConfigOperations.GET_ROLES, "#"), failure("nope"));
        on(r -> "search".equals(r.type()), json("null"));

        ObservedNodeConfig observed =
                ops.read(client, NODE, "node-a", scope(Set.of("#"), Set.of("#"), Map.of(), Set.of(), List.of()));

        assertThat(observed.live()).isFalse();
        assertThat(observed.addressSettings()).isEmpty();
        assertThat(observed.securitySettings()).isEmpty();
        assertThat(observed.diverts()).isEmpty();
        assertThat(observed.bridges()).isEmpty();
        assertThat(observed.addresses()).isEmpty();
        assertThat(observed.queues()).isEmpty();
        // Nothing to read on the second pass is nothing to send, so only the first POST goes out.
        assertThat(batches).hasSize(1);
    }

    @Test
    void aBatchAnsweredInPartIsRefused() {
        on(r -> true, json("{\"Active\":true}"));
        dropLastResponse = true;

        var readScope = scope(Set.of(), Set.of(), Map.of(), Set.of(), List.of());

        assertThatThrownBy(() -> ops.read(client, NODE, "node-a", readScope))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("answered")
                .hasMessageContaining("batched requests");
    }

    @Test
    void anAddressSettingCoveringAnAddressPullsItIntoTheRead() {
        on(
                r -> "read".equals(r.type())
                        && r.attribute() != null
                        && r.attribute().contains("Active"),
                json("{\"Active\":true,\"AddressNames\":[\"orders.eu\",\"orders.us\",\"activemq.x\",\"billing\"]}"));
        on(r -> "search".equals(r.type()), json("[]"));
        on(readOf(ArtemisMBeans.address(BROKER, "orders.eu")), json("{\"RoutingTypes\":[\"ANYCAST\"]}"));
        on(readOf(ArtemisMBeans.address(BROKER, "orders.us")), failure("gone"));

        ObservedNodeConfig observed =
                ops.read(client, NODE, "node-a", scope(Set.of("orders.#"), Set.of(), Map.of(), Set.of(), List.of()));

        List<String> readAddresses =
                batches.get(1).stream().map(JolokiaRequest::mbean).toList();
        assertThat(readAddresses)
                .containsExactly(
                        ArtemisMBeans.address(BROKER, "orders.eu"), ArtemisMBeans.address(BROKER, "orders.us"));
        assertThat(observed.addresses().get("orders.eu")).containsExactly("ANYCAST");
        // An address whose MBean would not answer is still known by name.
        assertThat(observed.addresses().get("orders.us")).isEmpty();
        assertThat(observed.addressUsage()).containsOnlyKeys("orders.eu");
    }

    // ---- reading a node for adoption ------------------------------------------------

    @Test
    void adoptionReadsEveryNonSystemAddressWithItsSettingsAndRoles() {
        String divertMbean = BROKER + ",component=addresses,address=\"a\",subcomponent=diverts,divert=\"d\"";
        on(
                r -> "read".equals(r.type())
                        && r.attribute() != null
                        && r.attribute().contains("Active"),
                json("{\"Active\":true,\"AddressNames\":[\"a\",\"b\",\"$sys.x\",\"activemq.notifications\"]}"));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "#"), json("\"{\\\"a\\\":1}\""));
        on(exec(BrokerConfigOperations.GET_ROLES, "#"), json("\"[{\\\"name\\\":\\\"r\\\",\\\"send\\\":true}]\""));
        on(search(ArtemisMBeans.divertsPattern(BROKER)), names(divertMbean));
        on(readOf(ArtemisMBeans.address(BROKER, "a")), json("{\"RoutingTypes\":[\"MULTICAST\"]}"));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "a"), json("\"{\\\"b\\\":2}\""));
        on(exec(BrokerConfigOperations.GET_ROLES, "a"), json("\"[]\""));
        on(readOf(ArtemisMBeans.address(BROKER, "b")), failure("gone"));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "b"), failure("gone"));
        on(exec(BrokerConfigOperations.GET_ROLES, "b"), failure("gone"));
        on(readOf(divertMbean), json("{\"UniqueName\":\"d\",\"Address\":\"a\"}"));

        ObservedNodeConfig observed = ops.readForAdoption(client, NODE, "node-a");

        assertThat(observed.live()).isTrue();
        assertThat(observed.addresses()).containsOnlyKeys("a", "b");
        assertThat(observed.addresses().get("a")).containsExactly("MULTICAST");
        assertThat(observed.addresses().get("b")).isEmpty();
        assertThat(observed.addressSettings()).containsOnlyKeys("#", "a");
        assertThat(observed.securitySettings()).containsOnlyKeys("#", "a");
        assertThat(observed.securitySettings().get("#")).containsEntry(PermissionType.SEND, Set.of("r"));
        assertThat(observed.securitySettings().get("a")).isEmpty();
        assertThat(observed.diverts()).containsOnlyKeys("d");
        assertThat(observed.queues()).isEmpty();
    }

    @Test
    void adoptionWithoutDefaultSettingsStillReadsTheAddresses() {
        on(
                r -> "read".equals(r.type())
                        && r.attribute() != null
                        && r.attribute().contains("Active"),
                json("{\"Active\":true,\"AddressNames\":[\"a\"]}"));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "#"), failure("no"));
        on(exec(BrokerConfigOperations.GET_ROLES, "#"), failure("no"));
        on(r -> "search".equals(r.type()), json("[]"));
        on(readOf(ArtemisMBeans.address(BROKER, "a")), json("{\"RoutingTypes\":[\"ANYCAST\"]}"));
        on(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, "a"), json("\"[]\""));
        on(exec(BrokerConfigOperations.GET_ROLES, "a"), json("\"[]\""));

        ObservedNodeConfig observed = ops.readForAdoption(client, NODE, "node-a");

        assertThat(observed.addressSettings()).containsOnlyKeys("a").containsEntry("a", Map.of());
        assertThat(observed.securitySettings()).containsOnlyKeys("a");
    }

    // ---- pure helpers -------------------------------------------------------------------

    @Test
    void rolesAreGroupedPerPermissionAndBlankNamesAreDropped() {
        JsonNode array = mapper.readTree("[{\"name\":\"b\",\"send\":true},{\"name\":\"a\",\"send\":true,\"view\":true},"
                + "{\"name\":\" \",\"send\":true},{\"send\":true}]");

        Map<PermissionType, Set<String>> roles = BrokerConfigOperations.roles(array);

        assertThat(roles).containsOnlyKeys(PermissionType.SEND, PermissionType.VIEW);
        assertThat(roles.get(PermissionType.SEND)).containsExactly("a", "b");
        assertThat(BrokerConfigOperations.roles(null)).isEmpty();
        assertThat(BrokerConfigOperations.roles(mapper.readTree("{}"))).isEmpty();
    }

    @Test
    void bridgesGroupUnderTheirDeclaredNameOnlyWhenTheSuffixIsANumber() {
        BridgeRow numbered = bridgeRow("to-dc2-3");
        BridgeRow lookalike = bridgeRow("to-dc2-east");
        BridgeRow unnamed = bridgeRow(null);

        Map<String, ObservedBridge> grouped = BrokerConfigOperations.groupBridges(
                List.of(numbered, lookalike, unnamed), List.of(bridgeNamed("to-dc2"), bridgeNamed("absent")));

        assertThat(grouped).containsOnlyKeys("to-dc2", "to-dc2-east");
        assertThat(grouped.get("to-dc2").instances()).isEqualTo(1);
        assertThat(grouped.get("to-dc2").config().name()).isEqualTo("to-dc2-3");
    }

    private BridgeRow bridgeRow(String name) {
        return BridgeRow.parse(
                mapper.readTree(
                        name == null ? "{}" : "{\"Name\":\"" + name + "\",\"Started\":true,\"Connected\":true}"),
                NODE,
                "node-a");
    }
}
