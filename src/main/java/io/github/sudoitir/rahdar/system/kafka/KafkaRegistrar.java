package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.BrokerSystem;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.ConnectionReport;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeOutcome;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.access.Assessment;
import io.github.sudoitir.openbroker.kafka.KafkaOptions;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions.TlsBundle;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.KafkaCredentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Connection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.ConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AccessFinding;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AccessRemedy;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.Failure;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.KafkaConnectionDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeReach;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeVersion;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.Quorum;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.VersionFound;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers Kafka clusters (ADR-0219): reaches every broker through the driver once, names each one's outcome, and
 * classifies a failure by its cause with the next action. Nothing is stored by a check.
 */
@Component
@RequiredArgsConstructor
class KafkaRegistrar implements ClusterRegistrar {

    /** The attribute the driver puts on every node it describes: the cluster's own id. */
    /** The cause of a check that failed because the bootstrap server answered and the brokers it named did not. */
    static final String ADVERTISED_UNREACHABLE = "ADVERTISED_UNREACHABLE";

    /** How many bootstrap servers a request may carry. */
    static final int MAX_BOOTSTRAP = 32;

    /** How many of them are probed for the advertised-listener hint. */
    private static final int PROBED = 3;

    static final String CLUSTER_ID = "kafka.cluster_id";

    private static final String CLUSTER_VERSION = "kafka.cluster_version";
    private static final String CLUSTER_MODE = "kafka.cluster_mode";
    private static final String QUORUM_CAPABILITY = "kafka.quorum";
    private static final String ENDPOINT = "kafka.endpoint";
    private static final String REACH_FAILURE = "kafka.reach_failure";
    private static final String QUORUM_VOTER = "kafka.quorum_voter";
    private static final String QUORUM_LEADER = "kafka.quorum_leader";
    private static final int DEFAULT_PORT = 9092;
    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");
    private static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)");
    private static final Pattern RANGE_START = Pattern.compile("^[\\[(]\\s*(\\d+)(?:\\.(\\d+))?");
    private static final Pattern RANGE_END = Pattern.compile(",\\s*(\\d+)(?:\\.(\\d+))?[^,]*[)\\]]\\s*$");
    private static final Pattern LOWER_BOUND = Pattern.compile("^[\\[(]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Duration TOKEN_VALIDITY = Duration.ofDays(1);

    private static final Set<String> PROTOCOLS =
            Set.of(KafkaOptions.PLAINTEXT, KafkaOptions.SSL, KafkaOptions.SASL_PLAINTEXT, KafkaOptions.SASL_SSL);
    private static final Set<String> MECHANISMS = Set.of(
            KafkaOptions.PLAIN, KafkaOptions.SCRAM_SHA_256, KafkaOptions.SCRAM_SHA_512, KafkaOptions.OAUTHBEARER);

    private final BrokerGateway gateway;
    private final BrokerDrivers drivers;
    private final KafkaConnectionRepository connections;
    private final ConnectionSettingsSource settings;
    private final BootstrapProbe bootstrapProbe;

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public Checked check(Connection connection) {
        KafkaConnection kafka = validated(connection);
        List<String> bootstrap = bootstrapOf(kafka);
        ClusterSpec spec = new ClusterSpec(
                List.of(),
                bootstrap.stream()
                        .map(server -> new Endpoint(Plane.DATA, KafkaOptions.PROTOCOL, server))
                        .toList(),
                options(kafka));
        Optional<TlsBundle> bundle =
                Optional.ofNullable(blankToNull(kafka.tlsBundle())).map(name -> new TlsBundle(name, true));
        try {
            ConnectionReport report = gateway.verify(KafkaModule.SYSTEM, spec, credentials(kafka), bundle);
            return found(report);
        } catch (BrokerException e) {
            return new Checked(failed(e, bootstrap), List.of());
        }
    }

    @Override
    public Account accountOf(Connection connection) {
        KafkaCredentials credentials = ((KafkaConnection) connection).credentials();
        if (credentials == null) {
            return null;
        }
        return KafkaOptions.OAUTHBEARER.equals(((KafkaConnection) connection).saslMechanism())
                ? new Account(null, credentials.token())
                : new Account(credentials.username(), credentials.password());
    }

    @Override
    public String defaultName(Connection connection) {
        String first = bootstrapOf(validated(connection)).getFirst();
        return first.substring(0, first.lastIndexOf(':'));
    }

    @Override
    public Map<String, Object> auditParams(Connection connection) {
        KafkaConnection kafka = (KafkaConnection) connection;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("system", KafkaModule.SYSTEM);
        params.put("bootstrapServers", bootstrapOf(kafka));
        params.put("securityProtocol", kafka.securityProtocol());
        params.put("saslMechanism", kafka.saslMechanism());
        params.put("clientId", kafka.clientId());
        params.put("tlsBundle", kafka.tlsBundle());
        params.put(
                "account",
                kafka.credentials() == null ? null : kafka.credentials().username());
        return params;
    }

    @Override
    @Transactional
    public void store(UUID clusterId, Connection connection) {
        KafkaConnection kafka = (KafkaConnection) connection;
        String servers = String.join(",", bootstrapOf(kafka));
        String clientId = blankToNull(kafka.clientId());
        connections
                .findById(clusterId)
                .ifPresentOrElse(
                        existing -> existing.change(servers, kafka.securityProtocol(), kafka.saslMechanism(), clientId),
                        () -> connections.save(new KafkaConnectionEntity(
                                clusterId, servers, kafka.securityProtocol(), kafka.saslMechanism(), clientId)));
    }

    @Override
    @Transactional(readOnly = true)
    public KafkaConnectionDetail detail(UUID clusterId) {
        KafkaConnectionEntity stored =
                connections.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        CoreConnectionSettings account = settings.coreSettings(clusterId);
        return new KafkaConnectionDetail(
                Arrays.asList(stored.getBootstrapServers().split(",")),
                stored.getSecurityProtocol(),
                stored.getSaslMechanism(),
                account.username(),
                account.password() != null,
                account.tlsBundle(),
                stored.getClientId());
    }

    @Override
    @Transactional(readOnly = true)
    public Resolved edited(UUID clusterId, ConnectionUpdate requested) {
        KafkaConnectionEntity stored =
                connections.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        CoreConnectionSettings account = settings.coreSettings(clusterId);
        KafkaConnectionUpdate edit = requested instanceof KafkaConnectionUpdate kafka
                ? kafka
                : new KafkaConnectionUpdate(KafkaModule.SYSTEM, null, null, null, null, null, null);

        List<String> servers = edit.bootstrapServers() != null
                ? parse(edit.bootstrapServers())
                : Arrays.asList(stored.getBootstrapServers().split(","));
        String protocol = edit.securityProtocol() != null ? edit.securityProtocol() : stored.getSecurityProtocol();
        String mechanism = edit.securityProtocol() != null || edit.saslMechanism() != null
                ? edit.saslMechanism()
                : stored.getSaslMechanism();
        String bundle = edit.tlsBundle() == null ? account.tlsBundle() : blankToNull(edit.tlsBundle());
        String clientId = edit.clientId() == null ? stored.getClientId() : blankToNull(edit.clientId());

        boolean sasl = isSasl(protocol);
        boolean token = KafkaOptions.OAUTHBEARER.equals(mechanism);
        KafkaCredentials given = edit.credentials();
        String storedUser = account.username();
        String user = token ? null : given != null && given.username() != null ? given.username() : storedUser;
        String suppliedSecret = given == null ? null : token ? given.token() : given.password();
        boolean destinationChanged =
                !servers.equals(Arrays.asList(stored.getBootstrapServers().split(",")))
                        || !protocol.equals(stored.getSecurityProtocol())
                        || !Objects.equals(user, storedUser);

        KafkaCredentials resolvedCredentials = null;
        Account toStore = null;
        if (!sasl) {
            toStore = new Account(null, null);
        } else if (blankToNull(suppliedSecret) != null) {
            resolvedCredentials = token
                    ? new KafkaCredentials(null, null, suppliedSecret)
                    : new KafkaCredentials(user, suppliedSecret, null);
            toStore = new Account(token ? null : user, suppliedSecret);
        } else if (destinationChanged || !Objects.equals(mechanism, stored.getSaslMechanism())) {
            throw new IllegalArgumentException(
                    "Enter the " + (token ? "token" : "password")
                            + " again: the stored one is never sent to a server, protocol, mechanism or account the cluster did not already use.");
        } else {
            resolvedCredentials = token
                    ? new KafkaCredentials(null, null, account.password())
                    : new KafkaCredentials(user, account.password(), null);
        }
        KafkaConnection resolved = new KafkaConnection(
                KafkaModule.SYSTEM, servers, protocol, mechanism, resolvedCredentials, bundle, clientId);
        return new Resolved(validated(resolved), toStore);
    }

    // ---- reading the driver's report ----------------------------------------------------------------------------

    private Checked found(ConnectionReport report) {
        Partial<List<NodeDescription>> nodes = report.nodes();
        List<NodeReach> rows = new ArrayList<>();
        List<DiscoveredNode> reached = new ArrayList<>();
        String clusterId = null;
        String leader = null;
        int voters = 0;
        Optional<String> version = Optional.empty();
        String mode = null;
        Set<NodeId> described = new LinkedHashSet<>();
        for (NodeDescription node : nodes.value()) {
            described.add(node.node());
            NodeOutcome outcome = nodes.outcomes().getOrDefault(node.node(), NodeOutcome.APPLIED);
            BrokerException failure = nodes.failures().get(node.node());
            boolean ok = outcome == NodeOutcome.APPLIED && failure == null;
            List<String> functions =
                    node.functions().stream().map(Enum::name).sorted().toList();
            rows.add(new NodeReach(
                    node.nativeId(),
                    addressOf(node),
                    functions,
                    ok ? "OK" : "UNREACHABLE",
                    ok ? null : causeOf(failure),
                    ok ? null : failure == null ? "The node did not answer." : failure.getMessage(),
                    nodeVersion(node.attributes()
                            .raw(CLUSTER_VERSION)
                            .map(String::valueOf)
                            .or(node::version)),
                    null));
            if (ok) {
                reached.add(new DiscoveredNode(node.nativeId(), nameOf(node), dataEndpoints(node)));
            }
            if (clusterId == null) {
                clusterId =
                        node.attributes().raw(CLUSTER_ID).map(String::valueOf).orElse(null);
            }
            if (version.isEmpty()) {
                version = node.attributes()
                        .raw(CLUSTER_VERSION)
                        .map(String::valueOf)
                        .or(node::version);
            }
            if (mode == null) {
                mode = node.attributes().raw(CLUSTER_MODE).map(String::valueOf).orElse(null);
            }
            if (isTrue(node, QUORUM_VOTER)) {
                voters++;
                if (isTrue(node, QUORUM_LEADER)) {
                    leader = node.nativeId();
                }
            }
        }
        for (Map.Entry<NodeId, BrokerException> failure : nodes.failures().entrySet()) {
            if (!described.contains(failure.getKey())) {
                rows.add(new NodeReach(
                        failure.getKey().value(),
                        failure.getValue().info().metadata().get(ENDPOINT),
                        List.of(),
                        "UNREACHABLE",
                        causeOf(failure.getValue()),
                        failure.getValue().getMessage(),
                        null,
                        null));
            }
        }
        boolean kraft = mode != null ? "kraft".equalsIgnoreCase(mode) : voters > 0;
        String quorumReason = report.access().assessments().entrySet().stream()
                .filter(e -> e.getKey().id().equals(QUORUM_CAPABILITY)
                        && e.getValue().status() != Assessment.Status.AVAILABLE)
                .map(e -> e.getValue().reason())
                .findFirst()
                .orElse("The cluster reports no controller quorum, as a ZooKeeper-mode cluster does;"
                        + " its controllers cannot be read.");
        Quorum quorum = kraft && voters > 0
                ? new Quorum(true, null, leader, voters)
                : new Quorum(false, quorumReason, null, null);
        String versionText = version.orElseGet(() -> "at least " + lowerBound());
        VersionFound versionFound = new VersionFound(
                versionText, kraft ? "KRaft" : "ZooKeeper", version.isPresent() && !versionText.startsWith("at least"));
        List<AccessFinding> access = report.access().assessments().entrySet().stream()
                .map(e -> finding(e.getKey().id(), e.getValue()))
                .sorted(Comparator.comparing(AccessFinding::capability))
                .toList();
        ConnectionCheck view = new ConnectionCheck(
                KafkaModule.SYSTEM,
                versionFound,
                true,
                clusterId,
                null,
                rows,
                quorum,
                access,
                null,
                null,
                null,
                Map.of());
        return new Checked(view, reached);
    }

    private static AccessFinding finding(String capability, Assessment assessment) {
        return new AccessFinding(
                capability,
                assessment.status().name(),
                assessment.reason(),
                assessment.remedy().map(r -> r.snippet()).orElse(null),
                assessment
                        .remedy()
                        .map(r -> new AccessRemedy(r.text(), r.snippet(), r.mediaType()))
                        .orElse(null));
    }

    private static boolean isTrue(NodeDescription node, String attribute) {
        return node.attributes()
                .raw(attribute)
                .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                .orElse(false);
    }

    private static String addressOf(NodeDescription node) {
        return node.endpoints().stream()
                .filter(e -> e.plane() == Plane.DATA)
                .findFirst()
                .or(() -> node.endpoints().stream().findFirst())
                .map(Endpoint::address)
                .orElse(null);
    }

    static List<Endpoint> dataEndpoints(NodeDescription node) {
        return node.endpoints().stream().filter(e -> e.plane() == Plane.DATA).toList();
    }

    static String nameOf(NodeDescription node) {
        return (node.functions().contains(NodeFunction.BROKER) ? "broker-" : "controller-") + node.nativeId();
    }

    /** A release, as the cluster says it ("3.9" or "at least 3.0"), against the range the driver is tested on. */
    private NodeVersion nodeVersion(Optional<String> text) {
        return text.map(t -> new NodeVersion(t, support(t))).orElse(null);
    }

    private BrokerVersion.Support support(String text) {
        Matcher release = RELEASE.matcher(text);
        if (!release.find()) {
            return BrokerVersion.Support.UNKNOWN;
        }
        int[] found = {Integer.parseInt(release.group(1)), Integer.parseInt(release.group(2))};
        String range = rangeOfDriver();
        Matcher lower = RANGE_START.matcher(range);
        if (lower.find() && compare(found, lower) < 0) {
            return BrokerVersion.Support.BELOW_MINIMUM;
        }
        Matcher upper = RANGE_END.matcher(range);
        return upper.find() && compare(found, upper) >= 0
                ? BrokerVersion.Support.NEWER_THAN_TESTED
                : BrokerVersion.Support.SUPPORTED;
    }

    private static int compare(int[] version, Matcher bound) {
        int major = Integer.parseInt(bound.group(1));
        int minor = bound.group(2) == null ? 0 : Integer.parseInt(bound.group(2));
        return version[0] != major ? Integer.compare(version[0], major) : Integer.compare(version[1], minor);
    }

    private String rangeOfDriver() {
        return drivers.systems().stream()
                .filter(s -> s.id().equals(KafkaModule.SYSTEM))
                .map(BrokerSystem::supportedVersions)
                .findFirst()
                .orElse("");
    }

    private String lowerBound() {
        String range = drivers.systems().stream()
                .filter(s -> s.id().equals(KafkaModule.SYSTEM))
                .map(BrokerSystem::supportedVersions)
                .findFirst()
                .orElse("");
        Matcher m = LOWER_BOUND.matcher(range);
        return m.find() ? m.group(1) : "the supported minimum";
    }

    /**
     * Whether any of the first few bootstrap servers accepts a connection, probed together under one deadline, so a
     * long list of dead entries cannot hold the check for the sum of their waits.
     */
    private boolean anyAccepts(List<String> bootstrap) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Boolean>> probes = bootstrap.stream()
                    .limit(PROBED)
                    .map(server -> pool.submit(() -> bootstrapProbe.accepts(server)))
                    .toList();
            long deadline =
                    System.nanoTime() + BootstrapProbe.WAIT.plusSeconds(1).toNanos();
            for (Future<Boolean> probe : probes) {
                try {
                    if (probe.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        return true;
                    }
                } catch (TimeoutException | ExecutionException _) {
                    // This server did not answer in time; the others may.
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- failures -----------------------------------------------------------------------------------------------

    private ConnectionCheck failed(BrokerException e, List<String> bootstrap) {
        String reached = causeOf(e);
        boolean advertised = (reached.equals("TIMEOUT") || reached.equals("REFUSED")) && anyAccepts(bootstrap);
        // The bootstrap server answered and the brokers it named did not: a cause of its own, not a timeout.
        String cause = advertised ? ADVERTISED_UNREACHABLE : reached;
        String next = advertised
                ? "The bootstrap server answered, but the brokers advertise addresses Rahdar cannot reach. Set"
                        + " advertised.listeners to an address reachable from Rahdar (not localhost inside a container)."
                : switch (cause) {
                    case "DNS" -> "Check the bootstrap server's host name resolves from Rahdar.";
                    case "REFUSED" ->
                        "Check the host and port, that the broker listens there and that no firewall blocks it.";
                    case "TIMEOUT" ->
                        "Check the host and port are reachable from Rahdar and that a firewall is not dropping the traffic.";
                    case "TLS" ->
                        "Check the TLS bundle trusts the broker's certificate and that the host name matches it.";
                    case "AUTHENTICATION" -> "Check the user name, password or token and the SASL mechanism.";
                    case "AUTHORIZATION" -> "Grant the account permission to describe the cluster.";
                    default -> "Register a cluster that runs a supported version.";
                };
        String message = advertised
                ? "Brokers advertise addresses Rahdar cannot reach. " + e.getMessage()
                : "AUTHORIZATION".equals(cause)
                        ? "The account was accepted but may not describe the cluster. " + e.getMessage()
                        : e.getMessage();
        return new ConnectionCheck(
                KafkaModule.SYSTEM,
                null,
                !"UNSUPPORTED_VERSION".equals(cause),
                null,
                null,
                List.of(),
                null,
                List.of(),
                new Failure(cause, message, next),
                null,
                null,
                Map.of());
    }

    /** What went wrong reaching a node, from the driver's reason and, for an unreachable one, the cause chain. */
    static String causeOf(BrokerException e) {
        if (e == null) {
            return "TIMEOUT";
        }
        String reach = e.info().metadata().get(REACH_FAILURE);
        if (reach != null) {
            return reach.toUpperCase(Locale.ROOT);
        }
        switch (e.reason()) {
            case ErrorReasons.TLS_HANDSHAKE_FAILED:
                return "TLS";
            case ErrorReasons.CREDENTIALS_REJECTED:
                return "AUTHENTICATION";
            case ErrorReasons.NOT_AUTHORIZED:
                return "AUTHORIZATION";
            case ErrorReasons.UNSUPPORTED_VERSION:
                return "UNSUPPORTED_VERSION";
            default:
                break;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            String text = (t.getClass().getName() + " " + t.getMessage()).toLowerCase(Locale.ROOT);
            if (text.contains("unknownhost") || text.contains("unresolved") || text.contains("resolve")) {
                return "DNS";
            }
            if (text.contains("refused")) {
                return "REFUSED";
            }
            if (text.contains("ssl") || text.contains("certificate")) {
                return "TLS";
            }
        }
        return "TIMEOUT";
    }

    // ---- the connection -----------------------------------------------------------------------------------------

    private static Map<String, String> options(KafkaConnection kafka) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(KafkaOptions.SECURITY_PROTOCOL, kafka.securityProtocol());
        if (kafka.saslMechanism() != null) {
            options.put(KafkaOptions.SASL_MECHANISM, kafka.saslMechanism());
        }
        if (blankToNull(kafka.clientId()) != null) {
            options.put(KafkaOptions.CLIENT_ID, kafka.clientId());
        }
        return options;
    }

    private static CredentialSource credentials(KafkaConnection kafka) {
        KafkaCredentials given = kafka.credentials();
        Credentials credentials;
        if (given == null) {
            credentials = new Credentials.None();
        } else if (KafkaOptions.OAUTHBEARER.equals(kafka.saslMechanism())) {
            credentials = Credentials.bearerToken(given.token(), Instant.now().plus(TOKEN_VALIDITY));
        } else {
            credentials = Credentials.usernamePassword(given.username(), given.password());
        }
        return plane -> credentials;
    }

    private static boolean isSasl(String protocol) {
        return KafkaOptions.SASL_PLAINTEXT.equals(protocol) || KafkaOptions.SASL_SSL.equals(protocol);
    }

    /** The connection, checked as a whole (ADR-0219): protocol, mechanism, credentials and servers agree. */
    static KafkaConnection validated(Connection connection) {
        if (!(connection instanceof KafkaConnection kafka)) {
            throw new IllegalArgumentException("A Kafka cluster is registered with a Kafka connection.");
        }
        if (!PROTOCOLS.contains(kafka.securityProtocol())) {
            throw new IllegalArgumentException("securityProtocol must be one of " + new TreeSet<>(PROTOCOLS) + ".");
        }
        boolean sasl = isSasl(kafka.securityProtocol());
        if (sasl != (kafka.saslMechanism() != null)) {
            throw new IllegalArgumentException(
                    sasl
                            ? "saslMechanism is required when the security protocol is " + kafka.securityProtocol()
                                    + "."
                            : "saslMechanism is only for SASL_PLAINTEXT and SASL_SSL.");
        }
        if (sasl && !MECHANISMS.contains(kafka.saslMechanism())) {
            throw new IllegalArgumentException("saslMechanism must be one of " + new TreeSet<>(MECHANISMS) + ".");
        }
        KafkaCredentials credentials = kafka.credentials();
        if (!sasl && credentials != null) {
            throw new IllegalArgumentException("An account is only for SASL_PLAINTEXT and SASL_SSL.");
        }
        if (sasl) {
            requireCredentials(kafka.saslMechanism(), credentials);
        }
        parse(kafka.bootstrapServers());
        return kafka;
    }

    private static void requireCredentials(String mechanism, KafkaCredentials credentials) {
        if (KafkaOptions.OAUTHBEARER.equals(mechanism)) {
            if (credentials == null || blankToNull(credentials.token()) == null) {
                throw new IllegalArgumentException("credentials.token is required for OAUTHBEARER.");
            }
            return;
        }
        boolean named = credentials != null && blankToNull(credentials.username()) != null;
        boolean secret = credentials != null && blankToNull(credentials.password()) != null;
        if (!named) {
            throw new IllegalArgumentException("credentials.username is required with a password.");
        }
        if (!secret) {
            throw new IllegalArgumentException("credentials.password is required with a user name.");
        }
    }

    private static List<String> bootstrapOf(KafkaConnection kafka) {
        return parse(kafka.bootstrapServers());
    }

    /**
     * Every token of every entry, comma, space or new line separated, as {@code host:port} with the port defaulting
     * to 9092, without duplicates. A scheme or an account is refused.
     */
    static List<String> parse(List<String> entries) {
        Set<String> servers = new LinkedHashSet<>();
        for (String entry : entries) {
            for (String token : SEPARATORS.split(entry.trim())) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.contains("://") || token.contains("@")) {
                    throw new IllegalArgumentException(
                            "'" + token + "' must be host or host:port, without a scheme or an account.");
                }
                servers.add(withPort(token));
            }
        }
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("At least one bootstrap server is required.");
        }
        if (servers.size() > MAX_BOOTSTRAP) {
            throw new IllegalArgumentException(
                    "At most " + MAX_BOOTSTRAP + " bootstrap servers, not " + servers.size() + ".");
        }
        return List.copyOf(servers);
    }

    private static String withPort(String token) {
        int colon = token.lastIndexOf(':');
        if (colon < 0 || token.startsWith("[") && token.indexOf(']') > colon) {
            return token + ":" + DEFAULT_PORT;
        }
        String host = token.substring(0, colon);
        try {
            int port = Integer.parseInt(token.substring(colon + 1));
            if (host.isEmpty() || port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return host + ":" + port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "'" + token + "' must be host or host:port with a port from 1 to 65535.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
