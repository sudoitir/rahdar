package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.BrokerSystem;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.ConnectionReport;
import io.github.sudoitir.openbroker.Credentials;
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
import io.github.sudoitir.openbroker.access.Capability;
import io.github.sudoitir.openbroker.access.Remedy;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
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
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.DiscoveredNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.Resolved;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RabbitMqCredentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.RabbitMqConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.RabbitMqConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.UnknownConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RabbitMqConnectionDetail;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionEntity;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The RabbitMQ connection is checked as a whole, and a check names every endpoint, every node and each failure's cause. */
class RabbitMqRegistrarTest {

    private static final UUID CLUSTER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final RabbitMqCredentials LOGIN = new RabbitMqCredentials("ops", "pw", null);

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final BrokerDrivers drivers = mock(BrokerDrivers.class);
    private final RabbitMqConnectionRepository connections = mock(RabbitMqConnectionRepository.class);
    private final ConnectionSettingsSource settings = mock(ConnectionSettingsSource.class);
    private Optional<String> amqpFailure = Optional.empty();
    private final RabbitMqRegistrar registrar =
            new RabbitMqRegistrar(gateway, drivers, connections, settings, host -> amqpFailure);

    private static RabbitMqConnection connection(List<String> urls, List<String> amqp) {
        return new RabbitMqConnection(RabbitMqModule.SYSTEM, urls, amqp, LOGIN, null, null, null);
    }

    private static NodeDescription node(String name, String version, boolean running, boolean alarm) {
        String host = name.substring(name.indexOf('@') + 1);
        return new NodeDescription(
                NodeId.of(name),
                name,
                new Membership(Optional.of("prod-rabbit"), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(
                        Endpoint.of(Plane.MANAGEMENT, "http", "http://" + host + ":15672"),
                        Endpoint.of(Plane.DATA, "amqp", host + ":5672")),
                Optional.of(version),
                List.of(
                        new Condition(
                                ConditionTypes.SERVING,
                                running ? ConditionStatus.TRUE : ConditionStatus.FALSE,
                                "",
                                "",
                                Optional.empty()),
                        new Condition(
                                ConditionTypes.FULL,
                                alarm ? ConditionStatus.TRUE : ConditionStatus.FALSE,
                                "",
                                alarm ? "The node raised a memory alarm." : "",
                                Optional.empty())),
                Attributes.builder().build(),
                Optional.empty());
    }

    private static ConnectionReport report(Map<Capability, Assessment> access, NodeDescription... nodes) {
        return new ConnectionReport(
                Partial.complete(
                        List.of(nodes),
                        java.util.Arrays.stream(nodes)
                                .map(NodeDescription::node)
                                .toList()),
                new AccessSnapshot(access, Instant.now()));
    }

    private static BrokerException refused(String reason, Throwable cause) {
        return new BrokerException(
                Status.UNAVAILABLE,
                new ErrorInfo(reason, ErrorReasons.DOMAIN, Map.of()),
                Dispatch.NOT_SENT,
                null,
                "rabbit said no",
                cause);
    }

    // ---- what a check reports -------------------------------------------------------------------------------

    @Test
    void aReachedClusterNamesItsNodesEndpointsAndLowestVersion() {
        NodeDescription one = node("rabbit@r1", "4.0.2", true, false);
        NodeDescription two = node("rabbit@r2", "3.13.7", true, true);
        when(gateway.verify(any(), any(), any(), any())).thenReturn(report(Map.of(), one, two));

        Checked checked =
                registrar.check(connection(List.of("http://r1", "http://r2"), List.of("amqp://r1", "amqp://r2")));

        assertThat(checked.passed()).isTrue();
        assertThat(checked.view().clusterId()).isEqualTo("prod-rabbit");
        assertThat(checked.view().version().text()).isEqualTo("3.13.7");
        assertThat(checked.view().version().exact()).isTrue();
        assertThat(checked.view().version().mode()).isEqualTo("Cluster of 2 nodes");
        assertThat(checked.view().quorum()).isNull();
        assertThat(checked.view().nodes())
                .extracting(n -> n.id() + ":" + n.outcome() + ":" + n.message())
                .containsExactly("rabbit@r1:OK:null", "rabbit@r2:OK:The node raised a memory alarm.");
        assertThat(checked.view().endpoints())
                .extracting(e -> e.plane() + " " + e.address() + " " + e.outcome())
                .containsExactly(
                        "MANAGEMENT http://r1:15672 OK",
                        "MANAGEMENT http://r2:15672 OK",
                        "DATA amqp://r1:5672 OK",
                        "DATA amqp://r2:5672 OK");
        assertThat(checked.nodes()).extracting(DiscoveredNode::nativeId).containsExactly("rabbit@r1", "rabbit@r2");
        assertThat(checked.nodes().getFirst().endpoints()).containsExactly(Endpoint.of(Plane.DATA, "amqp", "r1:5672"));
    }

    @Test
    void theDriverIsGivenEveryUrlAndEndpointWithTheOptions() {
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(report(Map.of(), node("rabbit@r1", "4.0.2", true, false)));
        RabbitMqConnection given = new RabbitMqConnection(
                RabbitMqModule.SYSTEM, List.of("https://r1"), List.of("amqps://r1"), LOGIN, "trust", "/events", 30);

        registrar.check(given);

        ArgumentCaptor<ClusterSpec> spec = ArgumentCaptor.forClass(ClusterSpec.class);
        verify(gateway).verify(any(), spec.capture(), any(), any());
        assertThat(spec.getValue().clusterEndpoints())
                .containsExactly(
                        Endpoint.of(Plane.MANAGEMENT, "https", "https://r1:15671"),
                        Endpoint.of(Plane.DATA, "amqps", "r1:5671"));
        assertThat(spec.getValue().options())
                .containsEntry("rabbitmq.watch_interval", "PT30S")
                .containsEntry("rabbitmq.event_vhost", "/events");
    }

    @Test
    void aManagementUrlThatIsDownIsNamedEvenWhenAnotherAnswered() {
        when(gateway.verify(any(), any(), any(), any())).thenAnswer(call -> {
            ClusterSpec spec = call.getArgument(1);
            if (spec.clusterEndpoints().size() == 1
                    && spec.clusterEndpoints().getFirst().address().contains("r2")) {
                throw refused(ErrorReasons.UNREACHABLE, new java.net.ConnectException("Connection refused"));
            }
            return report(Map.of(), node("rabbit@r1", "4.0.2", true, false));
        });

        Checked checked = registrar.check(connection(List.of("http://r1", "http://r2"), List.of()));

        assertThat(checked.passed()).isTrue();
        assertThat(checked.view().endpoints())
                .extracting(e -> e.address() + " " + e.outcome() + " " + e.cause())
                .containsExactly("http://r1:15672 OK null", "http://r2:15672 UNREACHABLE REFUSED");
    }

    @Test
    void aNodeThatIsNotRunningIsShownButNotStored() {
        NodeDescription down = node("rabbit@r2", "4.0.2", false, false);
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(report(Map.of(), node("rabbit@r1", "4.0.2", true, false), down));

        Checked checked = registrar.check(connection(List.of("http://r1"), List.of()));

        assertThat(checked.view().nodes().get(1).outcome()).isEqualTo("UNREACHABLE");
        assertThat(checked.view().nodes().get(1).message()).isEqualTo("The node is not running.");
        assertThat(checked.nodes()).extracting(DiscoveredNode::nativeId).containsExactly("rabbit@r1");
    }

    @Test
    void aNodeTheDriverCouldNotDescribeIsNamedWithItsCause() {
        NodeDescription one = node("rabbit@r1", "4.0.2", true, false);
        NodeId lost = NodeId.of("rabbit@r3");
        Partial<List<NodeDescription>> nodes = new Partial<>(
                List.of(one),
                Map.of(one.node(), NodeOutcome.APPLIED, lost, NodeOutcome.FAILED),
                Map.of(lost, refused(ErrorReasons.UNREACHABLE, new java.net.UnknownHostException("r3"))));
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(new ConnectionReport(nodes, new AccessSnapshot(Map.of(), Instant.now())));

        Checked checked = registrar.check(connection(List.of("http://r1"), List.of()));

        assertThat(checked.view().nodes())
                .extracting(n -> n.id() + ":" + n.outcome() + ":" + n.cause())
                .containsExactly("rabbit@r1:OK:null", "rabbit@r3:UNREACHABLE:DNS");
        assertThat(checked.view().version().mode()).isEqualTo("Single node");
    }

    @Test
    void aNodeThatFailedAfterBeingDescribedIsUnreachable() {
        NodeDescription one = node("rabbit@r1", "4.0.2", true, false);
        Partial<List<NodeDescription>> nodes = new Partial<>(
                List.of(one),
                Map.of(one.node(), NodeOutcome.FAILED),
                Map.of(one.node(), refused(ErrorReasons.UNREACHABLE, null)));
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(new ConnectionReport(nodes, new AccessSnapshot(Map.of(), Instant.now())));

        Checked checked = registrar.check(connection(List.of("http://r1"), List.of()));

        assertThat(checked.view().nodes().getFirst().outcome()).isEqualTo("UNREACHABLE");
        assertThat(checked.view().nodes().getFirst().cause()).isEqualTo("TIMEOUT");
        assertThat(checked.nodes()).isEmpty();
    }

    @Test
    void anUnavailableCapabilityCarriesTheDriversRemedy() {
        Map<Capability, Assessment> access = Map.of(
                new Capability("rabbitmq.shovels"),
                Assessment.unavailable(
                        "The shovel plugin is not enabled.",
                        Optional.of(new Remedy(
                                "Enable the plugin.",
                                "rabbitmq-plugins enable rabbitmq_shovel",
                                "text/x-shellscript"))));
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(report(access, node("rabbit@r1", "4.0.2", true, false)));

        var finding = registrar
                .check(connection(List.of("http://r1"), List.of()))
                .view()
                .access()
                .getFirst();

        assertThat(finding.capability()).isEqualTo("rabbitmq.shovels");
        assertThat(finding.snippet()).isEqualTo("rabbitmq-plugins enable rabbitmq_shovel");
        assertThat(finding.remedy().mediaType()).isEqualTo("text/x-shellscript");
    }

    @Test
    void aNodeSitsAgainstTheRangeTheDriverIsTestedOn() {
        when(drivers.systems())
                .thenReturn(
                        List.of(new BrokerSystem("rabbitmq", "RabbitMQ", "[3.13.0,5.0.0)", 1, Set.of(), List.of())));
        NodeDescription old = node("rabbit@r1", "3.12.4", true, false);
        NodeDescription ok = node("rabbit@r2", "4.0.2", true, false);
        NodeDescription newer = node("rabbit@r3", "5.1.0", true, false);
        NodeDescription unreadable = node("rabbit@r4", "unreleased", true, false);
        when(gateway.verify(any(), any(), any(), any())).thenReturn(report(Map.of(), old, ok, newer, unreadable));

        Checked checked = registrar.check(connection(List.of("http://r1"), List.of()));

        assertThat(checked.view().nodes())
                .extracting(n -> n.version().support())
                .containsExactly(
                        BrokerVersion.Support.BELOW_MINIMUM,
                        BrokerVersion.Support.SUPPORTED,
                        BrokerVersion.Support.NEWER_THAN_TESTED,
                        BrokerVersion.Support.UNKNOWN);
    }

    @Test
    void aClusterWithoutAVersionIsTheDriversMinimumOrBetter() {
        when(drivers.systems())
                .thenReturn(List.of(new BrokerSystem("rabbitmq", "RabbitMQ", "[3.13.0,)", 1, Set.of(), List.of())));
        NodeDescription unknown = new NodeDescription(
                NodeId.of("rabbit@r1"),
                "rabbit@r1",
                Membership.standalone(Serving.ACTIVE),
                Set.of(NodeFunction.BROKER),
                List.of(),
                Optional.empty(),
                List.of(),
                Attributes.builder().build(),
                Optional.empty());
        when(gateway.verify(any(), any(), any(), any())).thenReturn(report(Map.of(), unknown));

        Checked checked = registrar.check(connection(List.of("http://r1"), List.of()));

        assertThat(checked.view().version().text()).isEqualTo("at least 3.13.0");
        assertThat(checked.view().version().exact()).isFalse();
        assertThat(checked.view().clusterId()).isNull();
        assertThat(checked.view().nodes().getFirst().version()).isNull();
        assertThat(checked.view().nodes().getFirst().address()).isNull();
    }

    // ---- the data plane -------------------------------------------------------------------------------------

    @Test
    void whenNoAmqpEndpointAcceptsTheCheckFailsWithDataUnreachable() {
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(report(Map.of(), node("rabbit@r1", "4.0.2", true, false)));
        amqpFailure = Optional.of("REFUSED");

        Checked checked = registrar.check(connection(List.of("http://r1"), List.of("amqp://r1", "amqp://r2")));

        assertThat(checked.passed()).isFalse();
        assertThat(checked.view().failure().cause()).isEqualTo("DATA_UNREACHABLE");
        assertThat(checked.view().failure().message())
                .contains("r1:5672 (REFUSED)")
                .contains("r2:5672 (REFUSED)");
        assertThat(checked.view().failure().next()).contains("register without AMQP endpoints");
        assertThat(checked.view().nodes()).hasSize(1);
        assertThat(checked.nodes()).isEmpty();
        assertThat(checked.view().endpoints())
                .extracting(e -> e.plane() + " " + e.outcome() + " " + e.cause())
                .containsExactly("MANAGEMENT OK null", "DATA UNREACHABLE REFUSED", "DATA UNREACHABLE REFUSED");
    }

    @Test
    void aPartlyReachedAmqpSetRegistersAfterTheWarning() {
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(report(Map.of(), node("rabbit@r1", "4.0.2", true, false)));
        RabbitMqRegistrar picky = new RabbitMqRegistrar(
                gateway,
                drivers,
                connections,
                settings,
                host -> host.startsWith("r2") ? Optional.of("TIMEOUT") : Optional.empty());

        Checked checked = picky.check(connection(List.of("http://r1"), List.of("amqp://r1", "amqp://r2")));

        assertThat(checked.passed()).isTrue();
        assertThat(checked.view().endpoints())
                .extracting(e -> e.address() + " " + e.outcome())
                .containsExactly("http://r1:15672 OK", "amqp://r1:5672 OK", "amqp://r2:5672 UNREACHABLE");
    }

    @Test
    void aClusterManagedWithoutAmqpEndpointsNeedsNoDataPlane() {
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(report(Map.of(), node("rabbit@r1", "4.0.2", true, false)));
        amqpFailure = Optional.of("REFUSED");

        assertThat(registrar.check(connection(List.of("http://r1"), List.of())).passed())
                .isTrue();
    }

    // ---- failures -------------------------------------------------------------------------------------------

    @Test
    void eachFailureNamesItsCauseAndTheNextAction() {
        assertCause(refused(ErrorReasons.CREDENTIALS_REJECTED, null), "AUTHENTICATION", "user name");
        assertCause(refused(ErrorReasons.NOT_AUTHORIZED, null), "AUTHORIZATION", "set_user_tags ops monitoring");
        assertCause(refused(ErrorReasons.TLS_HANDSHAKE_FAILED, null), "TLS", "TLS bundle");
        assertCause(refused(ErrorReasons.UNSUPPORTED_VERSION, null), "UNSUPPORTED_VERSION", "supported version");
        assertCause(refused(ErrorReasons.ENDPOINT_NOT_FOUND, null), "WRONG_ENDPOINT", "rabbitmq_management");
        assertCause(refused(ErrorReasons.UNEXPECTED_SYSTEM, null), "WRONG_ENDPOINT", "path prefix");
        assertCause(refused(ErrorReasons.UNREACHABLE, new java.net.UnknownHostException("r1")), "DNS", "host name");
        assertCause(
                refused(ErrorReasons.UNREACHABLE, new java.net.ConnectException("Connection refused")),
                "REFUSED",
                "listens there");
        assertCause(
                refused(ErrorReasons.UNREACHABLE, new javax.net.ssl.SSLException("certificate unknown")),
                "TLS",
                "TLS bundle");
        assertCause(
                refused(ErrorReasons.UNREACHABLE, new java.net.SocketTimeoutException("timed out")),
                "TIMEOUT",
                "firewall");
    }

    @Test
    void aHostThatDoesNotResolveIsNotReportedAsARefusal() {
        // The JDK client reports an unresolvable host as a connect failure, and the driver words that as a refusal.
        java.net.ConnectException connect = new java.net.ConnectException();
        connect.initCause(new java.net.UnknownHostException("nope.invalid"));
        BrokerException failure = new BrokerException(
                Status.UNAVAILABLE,
                new ErrorInfo(ErrorReasons.UNREACHABLE, ErrorReasons.DOMAIN, Map.of()),
                Dispatch.NOT_SENT,
                null,
                "Connection refused: nothing is listening at the address.",
                connect);
        doThrow(failure).when(gateway).verify(any(), any(), any(), any());

        Checked checked = registrar.check(connection(List.of("http://nope.invalid"), List.of()));

        assertThat(checked.view().failure().cause()).isEqualTo("DNS");
        assertThat(checked.view().failure().message()).isEqualTo("The host name of the address does not resolve.");
        assertThat(checked.view().endpoints()).singleElement().satisfies(e -> {
            assertThat(e.cause()).isEqualTo("DNS");
            assertThat(e.message()).doesNotContain("refused").contains("does not resolve");
        });
    }

    private void assertCause(BrokerException failure, String cause, String next) {
        doThrow(failure).when(gateway).verify(any(), any(), any(), any());

        Checked checked = registrar.check(connection(List.of("http://r1"), List.of()));

        assertThat(checked.passed()).isFalse();
        assertThat(checked.view().failure().cause()).isEqualTo(cause);
        assertThat(checked.view().failure().next()).contains(next);
        assertThat(checked.view().supported()).isEqualTo(!cause.equals("UNSUPPORTED_VERSION"));
        assertThat(checked.view().endpoints()).singleElement().satisfies(e -> {
            assertThat(e.outcome()).isEqualTo("UNREACHABLE");
            assertThat(e.cause()).isEqualTo(cause);
        });
        assertThat(checked.nodes()).isEmpty();
    }

    @Test
    void aWrongEndpointAndAnAuthorizationFailureSayWhatHappened() {
        doThrow(refused(ErrorReasons.UNEXPECTED_SYSTEM, null)).when(gateway).verify(any(), any(), any(), any());
        assertThat(registrar
                        .check(connection(List.of("http://r1"), List.of()))
                        .view()
                        .failure()
                        .message())
                .startsWith("The address answers, but it is not a RabbitMQ management API.");
        doThrow(refused(ErrorReasons.NOT_AUTHORIZED, null)).when(gateway).verify(any(), any(), any(), any());
        assertThat(registrar
                        .check(connection(List.of("http://r1"), List.of()))
                        .view()
                        .failure()
                        .message())
                .startsWith("The account was accepted but may not read the cluster.");
    }

    @Test
    void everyUrlOfAFailedCheckIsNamed() {
        doThrow(refused(ErrorReasons.UNREACHABLE, new java.net.ConnectException("Connection refused")))
                .when(gateway)
                .verify(any(), any(), any(), any());

        Checked checked = registrar.check(connection(List.of("http://r1", "http://r2"), List.of()));

        assertThat(checked.view().endpoints())
                .extracting(e -> e.address() + " " + e.cause())
                .containsExactly("http://r1:15672 REFUSED", "http://r2:15672 REFUSED");
    }

    // ---- the connection as a whole --------------------------------------------------------------------------

    @Test
    void theConnectionMustBeARabbitMqOneWithAnAccount() {
        var io2 =
                new io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest
                        .UnknownConnection("rabbitmq");
        assertThatThrownBy(() -> RabbitMqRegistrar.validated(io2)).hasMessageContaining("RabbitMQ connection");
        var rabbitMqConnection =
                new RabbitMqConnection(RabbitMqModule.SYSTEM, List.of("http://r1"), null, null, null, null, null);
        assertThatThrownBy(() -> RabbitMqRegistrar.validated(rabbitMqConnection))
                .hasMessageContaining("credentials are required");
        var rabbitMqConnection2 = new RabbitMqConnection(
                RabbitMqModule.SYSTEM,
                List.of("http://r1"),
                null,
                new RabbitMqCredentials(null, "pw", null),
                null,
                null,
                null);
        assertThatThrownBy(() -> RabbitMqRegistrar.validated(rabbitMqConnection2))
                .hasMessageContaining("credentials.username");
        var rabbitMqConnection3 = new RabbitMqConnection(
                RabbitMqModule.SYSTEM,
                List.of("http://r1"),
                null,
                new RabbitMqCredentials("ops", null, null),
                null,
                null,
                null);
        assertThatThrownBy(() -> RabbitMqRegistrar.validated(rabbitMqConnection3))
                .hasMessageContaining("credentials.password");
        var rabbitMqConnection4 = new RabbitMqConnection(
                RabbitMqModule.SYSTEM,
                List.of("http://r1"),
                null,
                new RabbitMqCredentials("ops", null, "t"),
                null,
                null,
                null);
        assertThatThrownBy(() -> RabbitMqRegistrar.validated(rabbitMqConnection4))
                .hasMessageContaining("not both");
        var rabbitMqConnection5 =
                new RabbitMqConnection(RabbitMqModule.SYSTEM, List.of("http://r1"), null, LOGIN, null, null, 0);
        assertThatThrownBy(() -> RabbitMqRegistrar.validated(rabbitMqConnection5))
                .hasMessageContaining("at least 1");
        var rabbitMqConnection6 =
                new RabbitMqConnection(RabbitMqModule.SYSTEM, List.of(), null, LOGIN, null, null, null);
        assertThatThrownBy(() -> RabbitMqRegistrar.validated(rabbitMqConnection6))
                .hasMessageContaining("At least one management URL");
    }

    @Test
    void aTokenIsOfferedAsTheBearerCredential() {
        RabbitMqConnection token = new RabbitMqConnection(
                RabbitMqModule.SYSTEM,
                List.of("http://r1"),
                null,
                new RabbitMqCredentials(null, null, "tok"),
                null,
                null,
                null);
        when(gateway.verify(any(), any(), any(), any()))
                .thenReturn(report(Map.of(), node("rabbit@r1", "4.0.2", true, false)));

        registrar.check(token);

        ArgumentCaptor<io.github.sudoitir.openbroker.CredentialSource> source =
                ArgumentCaptor.forClass(io.github.sudoitir.openbroker.CredentialSource.class);
        verify(gateway).verify(any(), any(), source.capture(), any());
        assertThat(source.getValue().forPlane(Plane.MANAGEMENT)).isInstanceOf(Credentials.BearerToken.class);
    }

    // ---- naming, auditing and storing -----------------------------------------------------------------------

    @Test
    void theClusterIsNamedAfterItsFirstManagementHost() {
        assertThat(registrar.defaultName(connection(List.of("https://Rabbit-1.example:15671/x", "http://r2"), null)))
                .isEqualTo("rabbit-1.example");
    }

    @Test
    void theAccountIsTheUserAndPasswordOrTheTokenOnly() {
        assertThat(registrar.accountOf(connection(List.of("http://r1"), null)))
                .isEqualTo(new io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar.Account("ops", "pw"));
        RabbitMqConnection token = new RabbitMqConnection(
                RabbitMqModule.SYSTEM,
                List.of("http://r1"),
                null,
                new RabbitMqCredentials(null, null, "tok"),
                null,
                null,
                null);
        assertThat(registrar.accountOf(token).username()).isNull();
        assertThat(registrar.accountOf(token).secret()).isEqualTo("tok");
        assertThat(registrar.accountOf(new RabbitMqConnection(
                        RabbitMqModule.SYSTEM, List.of("http://r1"), null, null, null, null, null)))
                .isNull();
    }

    @Test
    void anAuditEntryNamesWhereTheConnectionPointsAndNeverASecret() {
        Map<String, Object> params = registrar.auditParams(connection(List.of("http://r1"), List.of("amqp://r1")));

        assertThat(params)
                .containsEntry("system", "rabbitmq")
                .containsEntry("managementUrls", List.of("http://r1:15672"))
                .containsEntry("amqpEndpoints", List.of("amqp://r1:5672"))
                .containsEntry("eventVhost", "/")
                .containsEntry("watchIntervalSeconds", 10)
                .containsEntry("auth", "PASSWORD")
                .containsEntry("account", "ops");
        assertThat(params.toString()).doesNotContain("pw");
    }

    @Test
    void aNewConnectionIsStoredAndAnExistingOneIsChanged() {
        registrar.store(CLUSTER, connection(List.of("http://r1", "https://r2"), List.of("amqp://r1")));

        ArgumentCaptor<RabbitMqConnectionEntity> saved = ArgumentCaptor.forClass(RabbitMqConnectionEntity.class);
        verify(connections).save(saved.capture());
        assertThat(saved.getValue().getManagementUrls()).isEqualTo("http://r1:15672,https://r2:15671");
        assertThat(saved.getValue().getAmqpEndpoints()).isEqualTo("amqp://r1:5672");
        assertThat(saved.getValue().getAuth()).isEqualTo("PASSWORD");
        assertThat(saved.getValue().getEventVhost()).isEqualTo("/");
        assertThat(saved.getValue().getWatchIntervalSeconds()).isEqualTo(10);

        RabbitMqConnectionEntity existing = stored("http://old:15672", "amqp://old:5672", "PASSWORD");
        when(connections.findById(CLUSTER)).thenReturn(Optional.of(existing));
        RabbitMqConnection token = new RabbitMqConnection(
                RabbitMqModule.SYSTEM,
                List.of("http://r1"),
                List.of(),
                new RabbitMqCredentials(null, null, "tok"),
                null,
                "/events",
                5);
        registrar.store(CLUSTER, token);

        assertThat(existing.getManagementUrls()).isEqualTo("http://r1:15672");
        assertThat(existing.getAmqpEndpoints()).isNull();
        assertThat(existing.getAuth()).isEqualTo("TOKEN");
        assertThat(existing.getEventVhost()).isEqualTo("/events");
        assertThat(existing.getWatchIntervalSeconds()).isEqualTo(5);
    }

    private static RabbitMqConnectionEntity stored(String urls, String amqp, String auth) {
        return new RabbitMqConnectionEntity(CLUSTER, 10, urls, amqp, auth, "/");
    }

    // ---- the detail and an edit -----------------------------------------------------------------------------

    private void storedWith(RabbitMqConnectionEntity entity, CoreConnectionSettings account) {
        when(connections.findById(CLUSTER)).thenReturn(Optional.of(entity));
        when(settings.coreSettings(CLUSTER)).thenReturn(account);
    }

    private static CoreConnectionSettings account(String user, String secret) {
        return new CoreConnectionSettings(CLUSTER, user, secret, "trust", true);
    }

    @Test
    void theDetailNeverCarriesASecret() {
        storedWith(stored("http://r1:15672,http://r2:15672", "amqp://r1:5672", "PASSWORD"), account("ops", "pw"));

        RabbitMqConnectionDetail detail = registrar.detail(CLUSTER);

        assertThat(detail.managementUrls()).containsExactly("http://r1:15672", "http://r2:15672");
        assertThat(detail.amqpEndpoints()).containsExactly("amqp://r1:5672");
        assertThat(detail.username()).isEqualTo("ops");
        assertThat(detail.tokenAuth()).isFalse();
        assertThat(detail.secretStored()).isTrue();
        assertThat(detail.tlsBundle()).isEqualTo("trust");
        assertThat(detail.eventVhost()).isEqualTo("/");
        assertThat(detail.watchIntervalSeconds()).isEqualTo(10);
        assertThat(detail.toString()).doesNotContain("pw");

        storedWith(stored("http://r1:15672", null, "TOKEN"), account(null, "tok"));
        RabbitMqConnectionDetail tokened = registrar.detail(CLUSTER);
        assertThat(tokened.tokenAuth()).isTrue();
        assertThat(tokened.username()).isNull();
        assertThat(tokened.amqpEndpoints()).isEmpty();
    }

    @Test
    void aClusterThatIsNotRegisteredHasNoDetail() {
        when(connections.findById(CLUSTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrar.detail(CLUSTER)).hasMessageContaining("cluster");
    }

    private static RabbitMqConnectionUpdate edit(
            List<String> urls,
            List<String> amqp,
            RabbitMqCredentials credentials,
            String bundle,
            String vhost,
            Integer watch) {
        return new RabbitMqConnectionUpdate(RabbitMqModule.SYSTEM, urls, amqp, credentials, bundle, vhost, watch);
    }

    @Test
    void anEditThatMovesNothingKeepsTheStoredSecret() {
        storedWith(stored("http://r1:15672", "amqp://r1:5672", "PASSWORD"), account("ops", "pw"));

        Resolved resolved = registrar.edited(CLUSTER, edit(null, null, null, null, "/events", 30));

        RabbitMqConnection connection = (RabbitMqConnection) resolved.connection();
        assertThat(connection.credentials()).isEqualTo(new RabbitMqCredentials("ops", "pw", null));
        assertThat(connection.eventVhost()).isEqualTo("/events");
        assertThat(connection.watchIntervalSeconds()).isEqualTo(30);
        assertThat(connection.tlsBundle()).isEqualTo("trust");
        assertThat(resolved.account()).isNull();
    }

    @Test
    void anEditCanClearTheBundleAndTheDataPlane() {
        storedWith(stored("http://r1:15672", "amqp://r1:5672", "PASSWORD"), account("ops", "pw"));

        Resolved resolved = registrar.edited(
                CLUSTER, edit(null, List.of(), new RabbitMqCredentials("ops", "new", null), " ", null, null));

        RabbitMqConnection connection = (RabbitMqConnection) resolved.connection();
        assertThat(connection.amqpEndpoints()).isEmpty();
        assertThat(connection.tlsBundle()).isNull();
        assertThat(resolved.account().secret()).isEqualTo("new");
    }

    @Test
    void aChangedUrlEndpointOrUserNeedsTheSecretAgain() {
        storedWith(stored("http://r1:15672", "amqp://r1:5672", "PASSWORD"), account("ops", "pw"));

        var edit2 = edit(List.of("http://other"), null, null, null, null, null);
        assertThatThrownBy(() -> registrar.edited(CLUSTER, edit2)).hasMessageContaining("Enter the password again");
        var edit3 = edit(null, List.of("amqp://other"), null, null, null, null);
        assertThatThrownBy(() -> registrar.edited(CLUSTER, edit3)).hasMessageContaining("Enter the password again");
        var edit4 = edit(null, null, new RabbitMqCredentials("someone", null, null), null, null, null);
        assertThatThrownBy(() -> registrar.edited(CLUSTER, edit4)).hasMessageContaining("Enter the password again");

        Resolved given = registrar.edited(
                CLUSTER,
                edit(List.of("http://other"), null, new RabbitMqCredentials(null, "pw2", null), null, null, null));
        assertThat(((RabbitMqConnection) given.connection()).managementUrls()).containsExactly("http://other:15672");
        assertThat(given.account().username()).isEqualTo("ops");
        assertThat(given.account().secret()).isEqualTo("pw2");
    }

    @Test
    void switchingToATokenNeedsTheTokenAndAStoredTokenIsKept() {
        storedWith(stored("http://r1:15672", null, "PASSWORD"), account("ops", "pw"));
        Resolved switched = registrar.edited(
                CLUSTER, edit(null, null, new RabbitMqCredentials(null, null, "tok"), null, null, null));
        assertThat(((RabbitMqConnection) switched.connection()).credentials())
                .isEqualTo(new RabbitMqCredentials(null, null, "tok"));
        assertThat(switched.account().username()).isNull();

        storedWith(stored("http://r1:15672", null, "TOKEN"), account(null, "tok"));
        Resolved kept = registrar.edited(CLUSTER, edit(null, null, null, null, null, null));
        assertThat(((RabbitMqConnection) kept.connection()).credentials())
                .isEqualTo(new RabbitMqCredentials(null, null, "tok"));
        assertThat(kept.account()).isNull();
        var edit2 = edit(List.of("http://other"), null, null, null, null, null);
        assertThatThrownBy(() -> registrar.edited(CLUSTER, edit2)).hasMessageContaining("Enter the token again");
    }

    @Test
    void aStoredAccountWithoutASecretNeedsOneEvenWhenNothingMoved() {
        storedWith(stored("http://r1:15672", null, "PASSWORD"), account("ops", null));

        var edit2 = edit(null, null, null, null, null, null);
        assertThatThrownBy(() -> registrar.edited(CLUSTER, edit2)).hasMessageContaining("again");
    }

    @Test
    void anEditOfAnotherSystemChangesNothing() {
        storedWith(stored("http://r1:15672", null, "PASSWORD"), account("ops", "pw"));

        Resolved resolved = registrar.edited(CLUSTER, new UnknownConnectionUpdate("rabbitmq"));

        assertThat(((RabbitMqConnection) resolved.connection()).managementUrls())
                .containsExactly("http://r1:15672");
    }

    @Test
    void aSupportedRangeIsReadWithoutBacktracking() {
        assertThat(RabbitMqRegistrar.VersionRange.parse("[3.13.0, 5.0.0)"))
                .contains(new RabbitMqRegistrar.VersionRange("3.13.0", "5.0.0"));
        assertThat(RabbitMqRegistrar.VersionRange.parse(" (3.13,) "))
                .contains(new RabbitMqRegistrar.VersionRange("3.13", null));
        for (String unreadable : List.of("", "3.13", "[3.13]", "[3.,5)", "[a,5)", "[3,5,7)", "[,5)", "[3..1,5)")) {
            assertThat(RabbitMqRegistrar.VersionRange.parse(unreadable))
                    .as(unreadable)
                    .isEmpty();
        }
        String adversarial = "[" + "1.".repeat(100_000) + "x,)";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(RabbitMqRegistrar.VersionRange.parse(adversarial)).isEmpty();
        });
    }
}
