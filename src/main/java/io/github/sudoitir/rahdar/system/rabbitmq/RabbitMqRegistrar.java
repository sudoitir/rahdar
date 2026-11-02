package io.github.sudoitir.rahdar.system.rabbitmq;

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
import io.github.sudoitir.openbroker.access.Remedy;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions.TlsBundle;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistrar;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RabbitMqCredentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Connection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.RabbitMqConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.ConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.RabbitMqConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AccessFinding;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.AccessRemedy;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.EndpointReach;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.Failure;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeReach;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeVersion;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RabbitMqConnectionDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.VersionFound;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionEntity;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers RabbitMQ clusters (ADR-0219): reaches every management URL and AMQP endpoint, names each one's outcome
 * and each node's state, and classifies a failure by its cause with the next action. Nothing is stored by a check.
 */
@Component
@RequiredArgsConstructor
class RabbitMqRegistrar implements ClusterRegistrar {

    /** The cause of a check where a management address answers but is not a RabbitMQ management API. */
    static final String WRONG_ENDPOINT = "WRONG_ENDPOINT";

    /** The cause of a check where AMQP endpoints were given and none accepts a connection. */
    static final String DATA_UNREACHABLE = "DATA_UNREACHABLE";

    private static final String TIMEOUT = "TIMEOUT";
    private static final String DNS = "DNS";
    private static final String REFUSED = "REFUSED";
    private static final String UNREACHABLE = "UNREACHABLE";
    private static final String AUTHORIZATION = "AUTHORIZATION";
    private static final String OK = "OK";
    private static final String UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    private static final Duration TOKEN_VALIDITY = Duration.ofDays(1);
    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    private final BrokerGateway gateway;
    private final BrokerDrivers drivers;
    private final RabbitMqConnectionRepository connections;
    private final ConnectionSettingsSource settings;
    private final AmqpProbe amqpProbe;

    @Override
    public String system() {
        return RabbitMqModule.SYSTEM;
    }

    // ---- the check ----------------------------------------------------------------------------------------------

    @Override
    public Checked check(Connection connection) {
        RabbitMqConnection rabbit = validated(connection);
        List<String> urls = ManagementUrls.parse(rabbit.managementUrls());
        List<String> amqp = AmqpEndpoints.parse(rabbit.amqpEndpoints());
        ClusterSpec spec = spec(rabbit, urls, amqp);
        Optional<TlsBundle> bundle =
                Optional.ofNullable(blankToNull(rabbit.tlsBundle())).map(name -> new TlsBundle(name, true));
        CredentialSource credentials = credentials(rabbit);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<ConnectionReport> whole =
                    pool.submit(() -> gateway.verify(RabbitMqModule.SYSTEM, spec, credentials, bundle));
            List<Future<Optional<BrokerException>>> each = urls.size() == 1
                    ? List.of()
                    : urls.stream()
                            .map(url -> pool.submit(() -> verifyAlone(url, rabbit, credentials, bundle)))
                            .toList();
            List<Future<Optional<String>>> probes = amqp.stream()
                    .map(endpoint -> pool.submit(() -> amqpProbe.unreachable(AmqpEndpoints.hostAndPort(endpoint))))
                    .toList();

            Outcome outcome = outcomeOf(whole);
            List<EndpointReach> reaches = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                Optional<BrokerException> failure =
                        each.isEmpty() ? Optional.ofNullable(outcome.failure()) : get(each.get(i), Optional.empty());
                reaches.add(managementReach(urls.get(i), failure.orElse(null)));
            }
            long deadline =
                    System.nanoTime() + AmqpProbe.CONNECT_WAIT.plusSeconds(1).toNanos();
            int accepted = 0;
            for (int i = 0; i < amqp.size(); i++) {
                Optional<String> cause = get(probes.get(i), Optional.of(TIMEOUT), deadline);
                accepted += cause.isEmpty() ? 1 : 0;
                reaches.add(dataReach(amqp.get(i), cause.orElse(null)));
            }
            if (outcome.failure() != null) {
                return new Checked(failed(outcome.failure(), rabbit, reaches), List.of());
            }
            return found(outcome.report(), reaches, !amqp.isEmpty() && accepted == 0);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The check was interrupted.");
        } finally {
            pool.shutdownNow();
        }
    }

    private record Outcome(ConnectionReport report, BrokerException failure) {}

    private static Outcome outcomeOf(Future<ConnectionReport> whole) throws InterruptedException {
        try {
            return new Outcome(whole.get(), null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BrokerException failure) {
                return new Outcome(null, failure);
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static <T> T get(Future<T> future, T otherwise) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException _) {
            return otherwise;
        }
    }

    private static <T> T get(Future<T> future, T otherwise, long deadline) throws InterruptedException {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException _) {
            return otherwise;
        }
    }

    /** Whether the one management URL answers on its own: the failure, or empty when it does. */
    private Optional<BrokerException> verifyAlone(
            String url, RabbitMqConnection rabbit, CredentialSource credentials, Optional<TlsBundle> bundle) {
        ClusterSpec alone = RabbitMqConnectionOptions.spec(
                List.of(), List.of(url), List.of(), watchSeconds(rabbit), eventVhost(rabbit));
        try {
            gateway.verify(RabbitMqModule.SYSTEM, alone, credentials, bundle);
            return Optional.empty();
        } catch (BrokerException e) {
            return Optional.of(e);
        }
    }

    private static EndpointReach managementReach(String url, BrokerException failure) {
        return failure == null
                ? new EndpointReach(Plane.MANAGEMENT.name(), url, OK, null, null)
                : new EndpointReach(Plane.MANAGEMENT.name(), url, UNREACHABLE, causeOf(failure), wordsOf(failure));
    }

    private static EndpointReach dataReach(String endpoint, String cause) {
        return cause == null
                ? new EndpointReach(Plane.DATA.name(), endpoint, OK, null, null)
                : new EndpointReach(
                        Plane.DATA.name(),
                        endpoint,
                        UNREACHABLE,
                        cause,
                        "Nothing accepted a connection at " + AmqpEndpoints.hostAndPort(endpoint) + ".");
    }

    // ---- reading the driver's report ----------------------------------------------------------------------------

    private Checked found(ConnectionReport report, List<EndpointReach> endpoints, boolean dataUnreachable) {
        Partial<List<NodeDescription>> nodes = report.nodes();
        List<NodeReach> rows = new ArrayList<>();
        List<DiscoveredNode> reached = new ArrayList<>();
        String clusterName = null;
        String lowest = null;
        for (NodeDescription node : nodes.value()) {
            NodeOutcome outcome = nodes.outcomes().getOrDefault(node.node(), NodeOutcome.APPLIED);
            BrokerException failure = nodes.failures().get(node.node());
            boolean running = outcome == NodeOutcome.APPLIED && failure == null && isRunning(node);
            rows.add(reach(node, running, failure));
            if (running) {
                reached.add(new DiscoveredNode(node.nativeId(), node.nativeId(), dataEndpoints(node)));
            }
            if (clusterName == null) {
                clusterName = node.membership().group().orElse(null);
            }
            lowest = lower(lowest, node.version().orElse(null));
        }
        for (Map.Entry<NodeId, BrokerException> failure : nodes.failures().entrySet()) {
            if (nodes.value().stream().noneMatch(node -> node.node().equals(failure.getKey()))) {
                rows.add(new NodeReach(
                        failure.getKey().value(),
                        null,
                        List.of(),
                        UNREACHABLE,
                        causeOf(failure.getValue()),
                        wordsOf(failure.getValue()),
                        null,
                        null));
            }
        }
        VersionFound version = new VersionFound(
                lowest == null ? "at least " + lowerBound() : lowest,
                nodes.value().size() > 1 ? "Cluster of " + nodes.value().size() + " nodes" : "Single node",
                lowest != null);
        List<AccessFinding> access = report.access().assessments().entrySet().stream()
                .map(e -> finding(e.getKey().id(), e.getValue()))
                .sorted(Comparator.comparing(AccessFinding::capability))
                .toList();
        Failure failure = dataUnreachable ? dataUnreachableFailure(endpoints) : null;
        ConnectionCheck view = new ConnectionCheck(
                RabbitMqModule.SYSTEM,
                version,
                true,
                clusterName,
                null,
                rows,
                null,
                access,
                failure,
                null,
                null,
                Map.of(),
                endpoints);
        return new Checked(view, failure == null ? reached : List.of());
    }

    private static Failure dataUnreachableFailure(List<EndpointReach> endpoints) {
        String detail = endpoints.stream()
                .filter(e -> Plane.DATA.name().equals(e.plane()))
                .map(e -> AmqpEndpoints.hostAndPort(e.address()) + " (" + e.cause() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return new Failure(
                DATA_UNREACHABLE,
                "The management API answered, but no AMQP endpoint accepts a connection: " + detail + ".",
                "Browse, send and receive need an AMQP endpoint Rahdar can reach. Check the host and port and that no"
                        + " firewall blocks them, or register without AMQP endpoints to manage the cluster only.");
    }

    private static boolean isRunning(NodeDescription node) {
        return node.conditions().stream()
                .noneMatch(c -> ConditionTypes.SERVING.equals(c.type()) && c.status() == ConditionStatus.FALSE);
    }

    private NodeReach reach(NodeDescription node, boolean running, BrokerException failure) {
        String message;
        if (failure != null) {
            message = wordsOf(failure);
        } else if (!running) {
            message = "The node is not running.";
        } else {
            message = warnings(node).orElse(null);
        }
        return new NodeReach(
                node.nativeId(),
                node.endpoints().stream()
                        .filter(e -> e.plane() == Plane.DATA)
                        .findFirst()
                        .map(Endpoint::address)
                        .orElse(null),
                node.functions().stream().map(Enum::name).sorted().toList(),
                running ? OK : UNREACHABLE,
                failure == null ? null : causeOf(failure),
                message,
                node.version().map(text -> new NodeVersion(text, support(text))).orElse(null),
                null);
    }

    /** The alarms and network partitions a running node reports, in the driver's words; empty when it has none. */
    private static Optional<String> warnings(NodeDescription node) {
        return node.conditions().stream()
                .filter(c -> c.status() == ConditionStatus.TRUE)
                .filter(c -> ConditionTypes.FULL.equals(c.type()) || ConditionTypes.OUT_OF_SYNC.equals(c.type()))
                .map(Condition::message)
                .reduce((a, b) -> a + " " + b);
    }

    static List<Endpoint> dataEndpoints(NodeDescription node) {
        return node.endpoints().stream().filter(e -> e.plane() == Plane.DATA).toList();
    }

    private static AccessFinding finding(String capability, Assessment assessment) {
        return new AccessFinding(
                capability,
                assessment.status().name(),
                assessment.reason(),
                assessment.remedy().map(Remedy::snippet).orElse(null),
                assessment
                        .remedy()
                        .map(r -> new AccessRemedy(r.text(), r.snippet(), r.mediaType()))
                        .orElse(null));
    }

    // ---- versions -----------------------------------------------------------------------------------------------

    /** The lower of two releases, as numbers: {@code 3.13.7} before {@code 4.0.2}; null counts as unknown. */
    private static String lower(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || compare(numbers(candidate), numbers(current)) < 0 ? candidate : current;
    }

    private static int[] numbers(String text) {
        return NUMBERS.matcher(text)
                .results()
                .limit(3)
                .mapToInt(m -> Integer.parseInt(m.group()))
                .toArray();
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private BrokerVersion.Support support(String text) {
        int[] found = numbers(text);
        Optional<VersionRange> parsed = VersionRange.parse(rangeOfDriver());
        if (found.length == 0 || parsed.isEmpty()) {
            return BrokerVersion.Support.UNKNOWN;
        }
        VersionRange range = parsed.get();
        if (compare(found, numbers(range.lower())) < 0) {
            return BrokerVersion.Support.BELOW_MINIMUM;
        }
        return range.upper() != null && compare(found, numbers(range.upper())) >= 0
                ? BrokerVersion.Support.NEWER_THAN_TESTED
                : BrokerVersion.Support.SUPPORTED;
    }

    private String rangeOfDriver() {
        return drivers.systems().stream()
                .filter(s -> s.id().equals(RabbitMqModule.SYSTEM))
                .map(BrokerSystem::supportedVersions)
                .findFirst()
                .orElse("");
    }

    private String lowerBound() {
        return VersionRange.parse(rangeOfDriver()).map(VersionRange::lower).orElse("the supported minimum");
    }

    /**
     * A driver's supported versions as an interval such as {@code [3.13, 5.0)}, read without a regular expression so
     * no input can make the read backtrack. The upper bound is absent for an open interval.
     */
    record VersionRange(String lower, String upper) {

        static Optional<VersionRange> parse(String text) {
            String trimmed = text.strip();
            if (trimmed.length() < 3
                    || "[(".indexOf(trimmed.charAt(0)) < 0
                    || "])".indexOf(trimmed.charAt(trimmed.length() - 1)) < 0) {
                return Optional.empty();
            }
            String inner = trimmed.substring(1, trimmed.length() - 1);
            int comma = inner.indexOf(',');
            if (comma < 0 || inner.indexOf(',', comma + 1) >= 0) {
                return Optional.empty();
            }
            String lower = inner.substring(0, comma).strip();
            String upper = inner.substring(comma + 1).strip();
            if (!isVersion(lower) || !upper.isEmpty() && !isVersion(upper)) {
                return Optional.empty();
            }
            return Optional.of(new VersionRange(lower, upper.isEmpty() ? null : upper));
        }

        /** Digits separated by single dots: {@code 3}, {@code 3.13}, {@code 4.0.2}. */
        private static boolean isVersion(String text) {
            boolean digitSeen = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= '0' && c <= '9') {
                    digitSeen = true;
                } else if (c == '.' && digitSeen && i < text.length() - 1) {
                    digitSeen = false;
                } else {
                    return false;
                }
            }
            return digitSeen;
        }
    }

    // ---- failures -----------------------------------------------------------------------------------------------

    private ConnectionCheck failed(BrokerException e, RabbitMqConnection rabbit, List<EndpointReach> endpoints) {
        String cause = causeOf(e);
        String account =
                rabbit.credentials() == null ? null : rabbit.credentials().username();
        return new ConnectionCheck(
                RabbitMqModule.SYSTEM,
                null,
                !UNSUPPORTED_VERSION.equals(cause),
                null,
                null,
                List.of(),
                null,
                List.of(),
                new Failure(cause, failureMessage(cause, Objects.toString(wordsOf(e), "")), nextStep(cause, account)),
                null,
                null,
                Map.of(),
                endpoints);
    }

    private static String failureMessage(String cause, String detail) {
        return switch (cause) {
            case AUTHORIZATION -> "The account was accepted but may not read the cluster. " + detail;
            case WRONG_ENDPOINT -> "The address answers, but it is not a RabbitMQ management API. " + detail;
            default -> detail;
        };
    }

    private static String nextStep(String cause, String account) {
        return switch (cause) {
            case DNS -> "Check the management URL's host name resolves from Rahdar.";
            case REFUSED ->
                "Check the host and port, that the management plugin listens there and that no firewall blocks it.";
            case TIMEOUT ->
                "Check the host and port are reachable from Rahdar and that a firewall is not dropping the traffic.";
            case "TLS" -> "Check the TLS bundle trusts the certificate and that the host name matches it.";
            case "AUTHENTICATION" -> "Check the user name and password, or the token.";
            case AUTHORIZATION ->
                "Give the account the monitoring tag so it can read the cluster: rabbitmqctl set_user_tags "
                        + (account == null ? "<user>" : account) + " monitoring";
            case WRONG_ENDPOINT ->
                "Enable the management plugin with rabbitmq-plugins enable rabbitmq_management, or add the path prefix"
                        + " the API is served under to the URL.";
            default -> "Register a cluster that runs a supported version.";
        };
    }

    /**
     * The failure's message. The driver words a connect failure as a refusal even when the host name never resolved
     * (the JDK client reports an unresolvable host as a connect failure), so a name that does not resolve says so.
     */
    private static String wordsOf(BrokerException e) {
        return DNS.equals(causeOf(e)) ? "The host name of the address does not resolve." : e.getMessage();
    }

    /** What went wrong reaching the cluster, from the driver's reason and, for an unreachable one, the cause chain. */
    static String causeOf(BrokerException e) {
        switch (e.reason()) {
            case ErrorReasons.TLS_HANDSHAKE_FAILED:
                return "TLS";
            case ErrorReasons.CREDENTIALS_REJECTED:
                return "AUTHENTICATION";
            case ErrorReasons.NOT_AUTHORIZED:
                return AUTHORIZATION;
            case ErrorReasons.ENDPOINT_NOT_FOUND:
            case ErrorReasons.UNEXPECTED_SYSTEM:
                return WRONG_ENDPOINT;
            case ErrorReasons.UNSUPPORTED_VERSION:
                return UNSUPPORTED_VERSION;
            default:
                break;
        }
        // A host name that does not resolve outranks a refusal anywhere in the chain: the driver words it as a refused
        // connection, and the JDK client wraps the resolution failure in a connect failure.
        for (Throwable t = e; t != null; t = t.getCause()) {
            String text = (t.getClass().getName() + " " + t.getMessage()).toLowerCase(Locale.ROOT);
            if (text.contains("unknownhost") || text.contains("unresolved") || text.contains("resolve")) {
                return DNS;
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            String text = (t.getClass().getName() + " " + t.getMessage()).toLowerCase(Locale.ROOT);
            if (text.contains("refused")) {
                return REFUSED;
            }
            if (text.contains("ssl") || text.contains("certificate")) {
                return "TLS";
            }
        }
        return TIMEOUT;
    }

    // ---- the connection -----------------------------------------------------------------------------------------

    private static ClusterSpec spec(RabbitMqConnection rabbit, List<String> urls, List<String> amqp) {
        return RabbitMqConnectionOptions.spec(List.of(), urls, amqp, watchSeconds(rabbit), eventVhost(rabbit));
    }

    private static int watchSeconds(RabbitMqConnection rabbit) {
        return rabbit.watchIntervalSeconds() == null
                ? RabbitMqConnectionOptions.DEFAULT_WATCH_SECONDS
                : rabbit.watchIntervalSeconds();
    }

    private static String eventVhost(RabbitMqConnection rabbit) {
        return Optional.ofNullable(blankToNull(rabbit.eventVhost()))
                .orElse(RabbitMqConnectionOptions.DEFAULT_EVENT_VHOST);
    }

    private static String usernameOf(RabbitMqCredentials given, String storedUser) {
        return given != null && given.username() != null ? given.username() : storedUser;
    }

    private static String suppliedSecret(RabbitMqCredentials given, boolean token) {
        if (given == null) {
            return null;
        }
        return blankToNull(token ? given.token() : given.password());
    }

    private static boolean hasPassword(RabbitMqCredentials credentials) {
        return blankToNull(credentials.password()) != null;
    }

    private static boolean isToken(RabbitMqCredentials credentials) {
        return credentials != null && blankToNull(credentials.token()) != null;
    }

    private static CredentialSource credentials(RabbitMqConnection rabbit) {
        RabbitMqCredentials given = rabbit.credentials();
        Credentials credentials = isToken(given)
                ? Credentials.bearerToken(given.token(), Instant.now().plus(TOKEN_VALIDITY))
                : Credentials.usernamePassword(given.username(), given.password());
        return plane -> credentials;
    }

    /** The connection, checked as a whole (ADR-0219): URLs, endpoints and the account agree. */
    static RabbitMqConnection validated(Connection connection) {
        if (!(connection instanceof RabbitMqConnection rabbit)) {
            throw new IllegalArgumentException("A RabbitMQ cluster is registered with a RabbitMQ connection.");
        }
        ManagementUrls.parse(rabbit.managementUrls());
        AmqpEndpoints.parse(rabbit.amqpEndpoints());
        RabbitMqCredentials credentials = rabbit.credentials();
        if (credentials == null) {
            throw new IllegalArgumentException("credentials are required: a user name and password, or a token.");
        }
        boolean token = isToken(credentials);
        boolean password = hasPassword(credentials);
        if (token && (password || blankToNull(credentials.username()) != null)) {
            throw new IllegalArgumentException("Give a token or a user name and password, not both.");
        }
        if (!token) {
            if (blankToNull(credentials.username()) == null) {
                throw new IllegalArgumentException("credentials.username is required with a password.");
            }
            if (!password) {
                throw new IllegalArgumentException("credentials.password is required with a user name.");
            }
        }
        if (rabbit.watchIntervalSeconds() != null && rabbit.watchIntervalSeconds() < 1) {
            throw new IllegalArgumentException("watchIntervalSeconds is at least 1.");
        }
        return rabbit;
    }

    // ---- what is stored -----------------------------------------------------------------------------------------

    @Override
    public Account accountOf(Connection connection) {
        RabbitMqCredentials credentials = ((RabbitMqConnection) connection).credentials();
        if (credentials == null) {
            return null;
        }
        return isToken(credentials)
                ? new Account(null, credentials.token())
                : new Account(credentials.username(), credentials.password());
    }

    @Override
    public String defaultName(Connection connection) {
        return ManagementUrls.host(
                ManagementUrls.parse(validated(connection).managementUrls()).getFirst());
    }

    @Override
    public Map<String, Object> auditParams(Connection connection) {
        RabbitMqConnection rabbit = (RabbitMqConnection) connection;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("system", RabbitMqModule.SYSTEM);
        params.put("managementUrls", ManagementUrls.parse(rabbit.managementUrls()));
        params.put("amqpEndpoints", AmqpEndpoints.parse(rabbit.amqpEndpoints()));
        params.put("tlsBundle", rabbit.tlsBundle());
        params.put("eventVhost", eventVhost(rabbit));
        params.put("watchIntervalSeconds", watchSeconds(rabbit));
        params.put("auth", authOf(rabbit.credentials()));
        params.put(
                "account",
                rabbit.credentials() == null || isToken(rabbit.credentials())
                        ? null
                        : rabbit.credentials().username());
        return params;
    }

    private static String authOf(RabbitMqCredentials credentials) {
        return isToken(credentials) ? RabbitMqConnectionEntity.TOKEN : RabbitMqConnectionEntity.PASSWORD;
    }

    @Override
    @Transactional
    public void store(UUID clusterId, Connection connection) {
        RabbitMqConnection rabbit = (RabbitMqConnection) connection;
        String urls = String.join(",", ManagementUrls.parse(rabbit.managementUrls()));
        List<String> amqp = AmqpEndpoints.parse(rabbit.amqpEndpoints());
        String endpoints = amqp.isEmpty() ? null : String.join(",", amqp);
        String auth = authOf(rabbit.credentials());
        connections
                .findById(clusterId)
                .ifPresentOrElse(
                        existing -> existing.change(watchSeconds(rabbit), urls, endpoints, auth, eventVhost(rabbit)),
                        () -> connections.save(new RabbitMqConnectionEntity(
                                clusterId, watchSeconds(rabbit), urls, endpoints, auth, eventVhost(rabbit))));
    }

    @Override
    @Transactional(readOnly = true)
    public RabbitMqConnectionDetail detail(UUID clusterId) {
        RabbitMqConnectionEntity stored = stored(clusterId);
        CoreConnectionSettings account = settings.coreSettings(clusterId);
        boolean token = RabbitMqConnectionEntity.TOKEN.equals(stored.getAuth());
        return new RabbitMqConnectionDetail(
                split(stored.getManagementUrls()),
                split(stored.getAmqpEndpoints()),
                token ? null : account.username(),
                token,
                account.password() != null,
                account.tlsBundle(),
                stored.getEventVhost(),
                stored.getWatchIntervalSeconds());
    }

    @Override
    @Transactional(readOnly = true)
    public Resolved edited(UUID clusterId, ConnectionUpdate requested) {
        RabbitMqConnectionEntity stored = stored(clusterId);
        CoreConnectionSettings account = settings.coreSettings(clusterId);
        RabbitMqConnectionUpdate edit = requested instanceof RabbitMqConnectionUpdate rabbit
                ? rabbit
                : new RabbitMqConnectionUpdate(RabbitMqModule.SYSTEM, null, null, null, null, null, null);

        List<String> urls = edit.managementUrls() != null
                ? ManagementUrls.parse(edit.managementUrls())
                : split(stored.getManagementUrls());
        List<String> amqp = edit.amqpEndpoints() != null
                ? AmqpEndpoints.parse(edit.amqpEndpoints())
                : split(stored.getAmqpEndpoints());
        RabbitMqCredentials given = edit.credentials();
        boolean storedToken = RabbitMqConnectionEntity.TOKEN.equals(stored.getAuth());
        boolean token = tokenAfter(given, storedToken);
        String storedUser = storedToken ? null : account.username();
        String user = token ? null : usernameOf(given, storedUser);
        String supplied = suppliedSecret(given, token);
        boolean destinationChanged = !urls.equals(split(stored.getManagementUrls()))
                || !amqp.equals(split(stored.getAmqpEndpoints()))
                || !Objects.equals(user, storedUser)
                || token != storedToken;
        if (supplied == null && (destinationChanged || account.password() == null)) {
            throw secretAgain(token);
        }
        String secret = supplied != null ? supplied : account.password();
        RabbitMqCredentials credentials =
                token ? new RabbitMqCredentials(null, null, secret) : new RabbitMqCredentials(user, secret, null);
        String bundle = edit.tlsBundle() == null ? account.tlsBundle() : blankToNull(edit.tlsBundle());
        RabbitMqConnection connection = new RabbitMqConnection(
                RabbitMqModule.SYSTEM,
                urls,
                amqp,
                credentials,
                bundle,
                edit.eventVhost() == null ? stored.getEventVhost() : blankToNull(edit.eventVhost()),
                edit.watchIntervalSeconds() == null ? stored.getWatchIntervalSeconds() : edit.watchIntervalSeconds());
        return new Resolved(validated(connection), supplied == null ? null : new Account(user, supplied));
    }

    /** Whether the edited connection signs in with a token: the one given, else the stored kind unless a password is given. */
    private static boolean tokenAfter(RabbitMqCredentials given, boolean storedToken) {
        if (given == null) {
            return storedToken;
        }
        return isToken(given) || storedToken && !hasPassword(given);
    }

    private static IllegalArgumentException secretAgain(boolean token) {
        return new IllegalArgumentException("Enter the " + (token ? "token" : "password")
                + " again: the stored one is never sent to a URL, endpoint or account the cluster did not"
                + " already use.");
    }

    private RabbitMqConnectionEntity stored(UUID clusterId) {
        return connections.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
    }

    private static List<String> split(String joined) {
        return joined == null ? List.of() : Arrays.asList(joined.split(","));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
