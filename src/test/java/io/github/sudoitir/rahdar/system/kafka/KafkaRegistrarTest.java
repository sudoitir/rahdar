package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ConnectionReport;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeOutcome;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.access.AccessSnapshot;
import io.github.sudoitir.openbroker.access.Assessment;
import io.github.sudoitir.openbroker.kafka.KafkaOptions;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.Checked;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.Resolved;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.KafkaCredentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The Kafka connection is checked as a whole, and a check names every node and each failure's cause. */
class KafkaRegistrarTest {

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final KafkaConnectionRepository connections = mock(KafkaConnectionRepository.class);
    private final ConnectionSettingsSource settings = mock(ConnectionSettingsSource.class);
    private boolean accepts;
    private final KafkaRegistrar registrar =
            new KafkaRegistrar(gateway, BrokerDrivers.load(), connections, settings, host -> accepts);

    private static KafkaConnection plaintext(String... servers) {
        return new KafkaConnection(
                KafkaModule.SYSTEM, List.of(servers), KafkaOptions.PLAINTEXT, null, null, null, null);
    }

    private static KafkaConnection sasl(String mechanism, KafkaCredentials credentials) {
        return new KafkaConnection(
                KafkaModule.SYSTEM, List.of("k1:9092"), KafkaOptions.SASL_SSL, mechanism, credentials, null, null);
    }

    // ---- bootstrap servers ----------------------------------------------------------------------------------

    @Test
    void everyTokenOfTheFieldIsKeptWithTheDefaultPort() {
        assertThat(KafkaRegistrar.parse(List.of("k1:9092, k2\nk3:9093", "k1:9092")))
                .containsExactly("k1:9092", "k2:9092", "k3:9093");
    }

    @Test
    void aSchemeOrAnAccountIsRefused() {
        assertThatThrownBy(() -> KafkaRegistrar.parse(List.of("kafka://k1:9092")))
                .hasMessageContaining("without a scheme or an account");
        assertThatThrownBy(() -> KafkaRegistrar.parse(List.of("user:pw@k1:9092")))
                .hasMessageContaining("without a scheme or an account");
        assertThatThrownBy(() -> KafkaRegistrar.parse(List.of("k1:notaport"))).hasMessageContaining("port");
    }

    // ---- security settings ----------------------------------------------------------------------------------

    @Test
    void aMechanismIsRequiredExactlyForSasl() {
        assertThatThrownBy(() -> KafkaRegistrar.validated(sasl(null, null)))
                .hasMessageContaining("saslMechanism is required");
        KafkaConnection stray = new KafkaConnection(
                KafkaModule.SYSTEM, List.of("k1"), KafkaOptions.SSL, KafkaOptions.PLAIN, null, null, null);
        assertThatThrownBy(() -> KafkaRegistrar.validated(stray)).hasMessageContaining("only for SASL");
    }

    @Test
    void credentialsMustMatchTheMechanismAndNameTheMissingMember() {
        assertThatThrownBy(() ->
                        KafkaRegistrar.validated(sasl(KafkaOptions.PLAIN, new KafkaCredentials(null, "pw", null))))
                .hasMessageContaining("credentials.username");
        assertThatThrownBy(() -> KafkaRegistrar.validated(
                        sasl(KafkaOptions.SCRAM_SHA_512, new KafkaCredentials("u", null, null))))
                .hasMessageContaining("credentials.password");
        assertThatThrownBy(() ->
                        KafkaRegistrar.validated(sasl(KafkaOptions.OAUTHBEARER, new KafkaCredentials("u", "p", null))))
                .hasMessageContaining("credentials.token");
        assertThat(KafkaRegistrar.validated(sasl(KafkaOptions.OAUTHBEARER, new KafkaCredentials(null, null, "t"))))
                .isNotNull();
    }

    @Test
    void anAccountOnAPlainConnectionIsRefused() {
        KafkaConnection withAccount = new KafkaConnection(
                KafkaModule.SYSTEM,
                List.of("k1"),
                KafkaOptions.PLAINTEXT,
                null,
                new KafkaCredentials("u", "p", null),
                null,
                null);
        assertThatThrownBy(() -> KafkaRegistrar.validated(withAccount)).hasMessageContaining("only for SASL");
    }

    // ---- what a check reports -------------------------------------------------------------------------------

    private static NodeDescription broker(String id, String address, boolean voter, boolean leader) {
        return new NodeDescription(
                NodeId.of(id),
                id,
                new Membership(Optional.empty(), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(Endpoint.of(Plane.DATA, KafkaOptions.PROTOCOL, address)),
                Optional.empty(),
                List.of(),
                Attributes.builder()
                        .putRaw("kafka.cluster_id", "Mk3OEYBSD34fcwNTJENDM2Qk")
                        .putRaw("kafka.quorum_voter", voter)
                        .putRaw("kafka.quorum_leader", leader)
                        .build(),
                Optional.empty());
    }

    private static BrokerException refused(String reason, Throwable cause) {
        return new BrokerException(
                Status.UNAVAILABLE,
                new ErrorInfo(reason, ErrorReasons.DOMAIN, Map.of()),
                Dispatch.NOT_SENT,
                null,
                "broker said no",
                cause);
    }

    /** A failure the driver attributed to {@code endpoint}, as {@code Reach} does for a broker it could not reach. */
    private static BrokerException refused(String reason, String endpoint, Throwable cause) {
        return new BrokerException(
                Status.UNAVAILABLE,
                new ErrorInfo(reason, ErrorReasons.DOMAIN, Map.of("kafka.endpoint", endpoint)),
                Dispatch.NOT_SENT,
                null,
                "broker said no",
                cause);
    }

    @Test
    void aPartlyReachedClusterNamesTheNodeItCouldNotReach() {
        NodeDescription one = broker("1", "k1:9092", true, true);
        NodeDescription two = broker("2", "k2:9092", true, false);
        BrokerException unreachable =
                refused(ErrorReasons.UNREACHABLE, new java.net.ConnectException("Connection refused"));
        Partial<List<NodeDescription>> nodes = new Partial<>(
                List.of(one, two),
                Map.of(
                        one.node(),
                        NodeOutcome.APPLIED,
                        two.node(),
                        NodeOutcome.APPLIED,
                        NodeId.of("3"),
                        NodeOutcome.FAILED),
                Map.of(NodeId.of("3"), unreachable));
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(new ConnectionReport(nodes, new AccessSnapshot(Map.of(), Instant.now())));

        Checked checked = registrar.check(plaintext("k1"));

        assertThat(checked.passed()).isTrue();
        assertThat(checked.view().clusterId()).isEqualTo("Mk3OEYBSD34fcwNTJENDM2Qk");
        assertThat(checked.view().nodes())
                .extracting(n -> n.id() + ":" + n.outcome() + ":" + n.cause())
                .containsExactly("1:OK:null", "2:OK:null", "3:UNREACHABLE:REFUSED");
        assertThat(checked.view().quorum().available()).isTrue();
        assertThat(checked.view().quorum().leader()).isEqualTo("1");
        assertThat(checked.view().quorum().voters()).isEqualTo(2);
        assertThat(checked.view().version().mode()).isEqualTo("KRaft");
        assertThat(checked.view().version().exact()).isFalse();
        assertThat(checked.view().version().text()).startsWith("at least ");
        assertThat(checked.nodes()).extracting(n -> n.name()).containsExactly("broker-1", "broker-2");
    }

    @Test
    void aNodeCarriesItsVersionAndWhereItSitsAgainstTheSupportedRange() {
        NodeDescription one = withVersion(broker("1", "k1:9092", true, true), "at least 3.0");
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(new ConnectionReport(
                        Partial.complete(List.of(one), List.of(one.node())),
                        new AccessSnapshot(Map.of(), Instant.now())));

        Checked checked = registrar.check(plaintext("k1"));

        assertThat(checked.view().nodes().getFirst().version().text()).isEqualTo("at least 3.0");
        assertThat(checked.view().nodes().getFirst().version().support())
                .isEqualTo(io.github.sudoitir.rahdar.platform.broker.BrokerVersion.Support.SUPPORTED);
        assertThat(checked.view().version().exact()).isFalse();
    }

    @Test
    void aVersionThatNamesNoReleaseIsUnknown() {
        assertThat(supportOf("kafka")).isEqualTo(BrokerVersion.Support.UNKNOWN);
        assertThat(supportOf("3")).isEqualTo(BrokerVersion.Support.UNKNOWN);
        assertThat(supportOf("3.")).isEqualTo(BrokerVersion.Support.UNKNOWN);
        assertThat(supportOf("3.x")).isEqualTo(BrokerVersion.Support.UNKNOWN);
        assertThat(supportOf("build 3.0")).isEqualTo(BrokerVersion.Support.SUPPORTED);
    }

    @Test
    void anUnavailableCapabilityCarriesTheDriversRemedy() {
        NodeDescription one = broker("1", "k1:9092", true, true);
        io.github.sudoitir.openbroker.access.Capability quorum =
                new io.github.sudoitir.openbroker.access.Capability("kafka.quorum");
        Map<io.github.sudoitir.openbroker.access.Capability, Assessment> assessments = Map.of(
                quorum,
                Assessment.unavailable(
                        "The controller quorum is not readable.",
                        Optional.of(new io.github.sudoitir.openbroker.access.Remedy(
                                "Upgrade the metadata version.", "kafka-features.sh upgrade", "text/x-shellscript"))));
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(new ConnectionReport(
                        Partial.complete(List.of(one), List.of(one.node())),
                        new AccessSnapshot(assessments, Instant.now())));

        var finding = registrar.check(plaintext("k1")).view().access().getFirst();

        assertThat(finding.snippet()).isEqualTo("kafka-features.sh upgrade");
        assertThat(finding.remedy().mediaType()).isEqualTo("text/x-shellscript");
    }

    private static NodeDescription withVersion(NodeDescription node, String version) {
        return new NodeDescription(
                node.node(),
                node.nativeId(),
                node.membership(),
                node.functions(),
                node.endpoints(),
                node.version(),
                node.conditions(),
                Attributes.builder()
                        .putRaw("kafka.cluster_id", "Mk3OEYBSD34fcwNTJENDM2Qk")
                        .putRaw("kafka.cluster_version", version)
                        .putRaw("kafka.cluster_mode", "kraft")
                        .build(),
                node.serverTime());
    }

    private BrokerVersion.Support supportOf(String version) {
        NodeDescription one = withVersion(broker("1", "k1:9092", true, true), version);
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(new ConnectionReport(
                        Partial.complete(List.of(one), List.of(one.node())),
                        new AccessSnapshot(Map.of(), Instant.now())));
        return registrar
                .check(plaintext("k1"))
                .view()
                .nodes()
                .getFirst()
                .version()
                .support();
    }

    @Test
    void aClusterWithNoVotersIsZooKeeperAndItsQuorumIsUnavailable() {
        NodeDescription one = broker("1", "k1:9092", false, false);
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(new ConnectionReport(
                        Partial.complete(List.of(one), List.of(one.node())),
                        new AccessSnapshot(Map.of(), Instant.now())));

        Checked checked = registrar.check(plaintext("k1"));

        assertThat(checked.view().version().mode()).isEqualTo("ZooKeeper");
        assertThat(checked.view().quorum().available()).isFalse();
        assertThat(checked.view().quorum().reason()).contains("ZooKeeper");
    }

    @Test
    void eachFailureNamesItsCauseAndTheNextAction() {
        assertCause(refused(ErrorReasons.CREDENTIALS_REJECTED, null), "AUTHENTICATION");
        assertCause(refused(ErrorReasons.NOT_AUTHORIZED, null), "AUTHORIZATION");
        assertCause(refused(ErrorReasons.TLS_HANDSHAKE_FAILED, null), "TLS");
        assertCause(refused(ErrorReasons.UNSUPPORTED_VERSION, null), "UNSUPPORTED_VERSION");
        assertCause(refused(ErrorReasons.UNREACHABLE, new java.net.UnknownHostException("k1")), "DNS");
        assertCause(refused(ErrorReasons.UNREACHABLE, new java.net.ConnectException("Connection refused")), "REFUSED");
        assertCause(refused(ErrorReasons.UNREACHABLE, new java.net.SocketTimeoutException("timed out")), "TIMEOUT");
    }

    private void assertCause(BrokerException failure, String cause) {
        doThrow(failure).when(gateway).verify(any(), any(), any(), any());

        Checked checked = registrar.check(plaintext("k1"));

        assertThat(checked.passed()).isFalse();
        assertThat(checked.view().failure().cause()).isEqualTo(cause);
        assertThat(checked.view().failure().next()).isNotBlank();
        assertThat(checked.view().supported()).isEqualTo(!cause.equals("UNSUPPORTED_VERSION"));
    }

    /**
     * The hint is for a failure on an address the cluster named. Here the brokers advertise the bootstrap
     * addresses themselves, so the timeout is in reaching them, not in what they advertise.
     */
    @Test
    void aTimeoutOnTheBootstrapAddressIsNotBlamedOnAdvertisedListeners() {
        doThrow(refused(ErrorReasons.UNREACHABLE, "k1:9092", new java.net.SocketTimeoutException("timed out")))
                .when(gateway)
                .verify(any(), any(), any(), any());
        accepts = true;

        var failure = registrar.check(plaintext("k1")).view().failure();

        assertThat(failure.cause()).isEqualTo("TIMEOUT");
        assertThat(failure.message()).doesNotContain("advertise");
        assertThat(failure.next()).doesNotContain("advertised.listeners");
    }

    @Test
    void aTimeoutOnAnAddressTheClusterNamedSaysTheBrokersAdvertiseAnUnreachableAddress() {
        doThrow(refused(ErrorReasons.UNREACHABLE, "elsewhere:9092", new java.net.SocketTimeoutException("timed out")))
                .when(gateway)
                .verify(any(), any(), any(), any());
        accepts = true;

        var failure = registrar.check(plaintext("k1")).view().failure();

        assertThat(failure.cause()).isEqualTo("ADVERTISED_UNREACHABLE");
        assertThat(failure.message()).startsWith("Brokers advertise addresses Rahdar cannot reach.");
        assertThat(failure.next()).contains("bootstrap server answered").contains("advertised.listeners");
    }

    /** A broker whose SASL login fails is never described as one that advertises an unreachable address. */
    @Test
    void aRejectedLoginIsNotAnAdvertisedAddress() {
        doThrow(refused(ErrorReasons.CREDENTIALS_REJECTED, "k1:9092", null))
                .when(gateway)
                .verify(any(), any(), any(), any());
        accepts = true;

        var failure = registrar.check(plaintext("k1")).view().failure();

        assertThat(failure.cause()).isEqualTo("AUTHENTICATION");
        assertThat(failure.next()).contains("user name").doesNotContain("advertised.listeners");
    }

    @Test
    void aTimeoutWithNothingListeningAtTheBootstrapServerIsAboutTheBootstrapServer() {
        doThrow(refused(ErrorReasons.UNREACHABLE, new java.net.SocketTimeoutException("timed out")))
                .when(gateway)
                .verify(any(), any(), any(), any());
        accepts = false;

        var failure = registrar.check(plaintext("k1")).view().failure();

        assertThat(failure.cause()).isEqualTo("TIMEOUT");
        assertThat(failure.next()).doesNotContain("advertised.listeners").contains("reachable from Rahdar");
    }

    @Test
    void aNameThatDoesNotResolveIsNotMistakenForAnAdvertisedAddress() {
        doThrow(refused(ErrorReasons.UNREACHABLE, new java.net.UnknownHostException("k1")))
                .when(gateway)
                .verify(any(), any(), any(), any());
        accepts = true;

        assertThat(registrar.check(plaintext("k1")).view().failure().next()).doesNotContain("advertised.listeners");
    }

    @Test
    void anAuthorizationFailureSaysTheAccountWasAccepted() {
        doThrow(refused(ErrorReasons.NOT_AUTHORIZED, null)).when(gateway).verify(any(), any(), any(), any());

        assertThat(registrar.check(plaintext("k1")).view().failure().message()).contains("accepted");
    }

    // ---- editing --------------------------------------------------------------------------------------------

    private UUID storedScram() {
        UUID id = UUID.randomUUID();
        when(connections.findById(id))
                .thenReturn(Optional.of(new KafkaConnectionEntity(
                        id, "k1:9092", KafkaOptions.SASL_SSL, KafkaOptions.SCRAM_SHA_512, null)));
        when(settings.coreSettings(id)).thenReturn(new CoreConnectionSettings(id, "app", "stored-pw", null, true));
        return id;
    }

    @Test
    void anOmittedSecretKeepsTheStoredOneWhenNothingAboutTheDestinationChanged() {
        UUID id = storedScram();

        Resolved resolved = registrar.edited(
                id, new KafkaConnectionUpdate(KafkaModule.SYSTEM, null, null, null, null, null, "renamed-client"));

        KafkaConnection connection = (KafkaConnection) resolved.connection();
        assertThat(connection.credentials().password()).isEqualTo("stored-pw");
        assertThat(connection.clientId()).isEqualTo("renamed-client");
        assertThat(resolved.account()).isNull();
    }

    @Test
    void aNewServerNeedsTheSecretAgain() {
        UUID id = storedScram();

        assertThatThrownBy(() -> registrar.edited(
                        id,
                        new KafkaConnectionUpdate(
                                KafkaModule.SYSTEM, List.of("k1:9092", "k9:9092"), null, null, null, null, null)))
                .hasMessageContaining("Enter the password again");
    }

    @Test
    void aNewUserNameNeedsTheSecretAgain() {
        UUID id = storedScram();

        assertThatThrownBy(() -> registrar.edited(
                        id,
                        new KafkaConnectionUpdate(
                                KafkaModule.SYSTEM,
                                null,
                                null,
                                null,
                                new KafkaCredentials("someone", null, null),
                                null,
                                null)))
                .hasMessageContaining("Enter the password again");
    }

    @Test
    void aRotatedPasswordIsStoredAndUsedForTheCheck() {
        UUID id = storedScram();

        Resolved resolved = registrar.edited(
                id,
                new KafkaConnectionUpdate(
                        KafkaModule.SYSTEM, null, null, null, new KafkaCredentials("app", "new-pw", null), null, null));

        assertThat(((KafkaConnection) resolved.connection()).credentials().password())
                .isEqualTo("new-pw");
        assertThat(resolved.account().secret()).isEqualTo("new-pw");
    }

    @Test
    void movingToAPlainProtocolClearsTheStoredAccount() {
        UUID id = storedScram();

        Resolved resolved = registrar.edited(
                id,
                new KafkaConnectionUpdate(KafkaModule.SYSTEM, null, KafkaOptions.PLAINTEXT, null, null, null, null));

        assertThat(((KafkaConnection) resolved.connection()).credentials()).isNull();
        assertThat(resolved.account().secret()).isNull();
    }

    @Test
    void aRequestMayCarryAtMostThirtyTwoBootstrapServers() {
        List<String> many =
                IntStream.rangeClosed(1, 33).mapToObj(n -> "k" + n + ":9092").toList();

        assertThat(KafkaRegistrar.parse(many.subList(0, 32))).hasSize(32);
        assertThatThrownBy(() -> KafkaRegistrar.parse(many))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At most 32");
    }

    @Test
    void onlyTheFirstThreeBootstrapServersAreProbedAndTogetherNotOneAfterAnother() {
        doThrow(refused(ErrorReasons.UNREACHABLE, new java.net.SocketTimeoutException("timed out")))
                .when(gateway)
                .verify(any(), any(), any(), any());
        AtomicInteger probed = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch overlapping = new CountDownLatch(3);
        KafkaRegistrar slow = new KafkaRegistrar(gateway, BrokerDrivers.load(), connections, settings, _ -> {
            probed.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            overlapping.countDown();
            try {
                // Released once every probe has entered, so a serial probe cannot finish the others.
                overlapping.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return false;
        });
        String[] servers =
                IntStream.rangeClosed(1, 10).mapToObj(n -> "k" + n + ":9092").toArray(String[]::new);

        var failure = slow.check(plaintext(servers)).view().failure();

        assertThat(failure.next()).doesNotContain("advertised.listeners");
        assertThat(probed).hasValue(3);
        assertThat(maxInFlight).as("three probes at once, not ten in turn").hasValue(3);
    }
}
