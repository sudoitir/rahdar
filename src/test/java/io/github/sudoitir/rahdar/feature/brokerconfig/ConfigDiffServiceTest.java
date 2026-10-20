package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigReader.NodeConfig;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigDiffView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigKeyView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigNodeView;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException.Kind;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The N-way comparison (ADR-0178): a majority and its outliers across every node, a key missing on
 * a node, an unreachable node left out of every majority, and what is stated rather than diffed.
 * The node reads are stubbed at {@link ConfigReader}; the Jolokia wire is the controller test's.
 */
class ConfigDiffServiceTest {

    private static final String SETTINGS = "[{\"match\":\"#\",\"maxSizeBytes\":-1}]";

    private final JsonMapper mapper = new JsonMapper();
    private final UUID clusterId = UUID.randomUUID();

    private ClusterDirectory directory;
    private QueueSnapshots snapshots;
    private BrokerConnections connections;
    private ConfigReader reader;
    private ConfigDiffService service;
    private final List<ClusterNode> nodes = new ArrayList<>();

    private final ArtemisNodes artemisNodes = mock(ArtemisNodes.class);

    @BeforeEach
    void setUp() {
        directory = mock(ClusterDirectory.class);
        snapshots = mock(QueueSnapshots.class);
        connections = mock(BrokerConnections.class);
        reader = mock(ConfigReader.class);
        service = new ConfigDiffService(
                directory, snapshots, connections, reader, mock(ClusterAccessGuard.class), artemisNodes);
        when(directory.nodes(clusterId)).thenReturn(nodes);
        when(snapshots.forCluster(clusterId)).thenReturn(List.of());
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    private ClusterNode node(String name, String url) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(UUID.randomUUID());
        when(node.getName()).thenReturn(name);
        when(node.getManagementUrl()).thenReturn(url);
        nodes.add(node);
        return node;
    }

    private NodeConfig config(String attributes, String settings, boolean active, int compared, int available) {
        return new NodeConfig(json(attributes), json(settings), json("[]"), json("[]"), active, compared, available);
    }

    private NodeConfig config(String attributes) {
        return config(attributes, SETTINGS, true, 1, 1);
    }

    /** A node that answers with {@code config}. */
    private ClusterNode answering(String name, NodeConfig config) {
        ClusterNode node = node(name, "http://" + name + "/jolokia");
        ArtemisNode client = mock(ArtemisNode.class);
        UUID nodeId = node.getId();
        when(artemisNodes.on(clusterId, nodeId)).thenReturn(client);
        when(reader.read(eq(client), anyList())).thenReturn(config);
        return node;
    }

    private ClusterNode failing(String name, Kind kind, String message) {
        ClusterNode node = node(name, "http://" + name + "/jolokia");
        ArtemisNode client = mock(ArtemisNode.class);
        UUID nodeId = node.getId();
        when(artemisNodes.on(clusterId, nodeId)).thenReturn(client);
        when(reader.read(eq(client), anyList())).thenThrow(new BrokerConnectionException(kind, message));
        return node;
    }

    private ConfigKeyView key(ConfigDiffView view, String section, String key) {
        return view.sections().stream()
                .filter(s -> s.section().equals(section))
                .flatMap(s -> s.keys().stream())
                .filter(k -> k.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void oneOutlierAmongFourNodesNamesTheMajorityAndTheOutlier() {
        answering("a", config("{\"JournalType\":\"ASYNCIO\"}"));
        answering("b", config("{\"JournalType\":\"ASYNCIO\"}"));
        answering("c", config("{\"JournalType\":\"NIO\"}"));
        answering("d", config("{\"JournalType\":\"ASYNCIO\"}"));

        ConfigDiffView view = service.compare(clusterId, null);

        ConfigKeyView type = key(view, "broker", "/JournalType");
        assertThat(view.comparable()).isTrue();
        assertThat(type.state()).isEqualTo("DIFFERENT");
        assertThat(type.stateWord()).isEqualTo("different");
        assertThat(type.classification()).isEqualTo("DRIFT");
        assertThat(type.majority()).isEqualTo("ASYNCIO");
        assertThat(type.outliers()).singleElement().satisfies(o -> {
            assertThat(o.nodeName()).isEqualTo("c");
            assertThat(o.value()).isEqualTo("NIO");
        });
        assertThat(view.summary().driftKeys()).isEqualTo(1);
        assertThat(view.summary().driftNodes()).isEqualTo(1);
    }

    @Test
    void noMajorityListsEveryValueWithItsNodes() {
        answering("a", config("{\"JournalType\":\"ASYNCIO\"}"));
        answering("b", config("{\"JournalType\":\"ASYNCIO\"}"));
        answering("c", config("{\"JournalType\":\"NIO\"}"));
        answering("d", config("{\"JournalType\":\"NIO\"}"));

        ConfigDiffView view = service.compare(clusterId, null);

        ConfigKeyView type = key(view, "broker", "/JournalType");
        assertThat(type.majority()).isNull();
        assertThat(type.outliers()).isEmpty();
        assertThat(type.valueGroups()).hasSize(2);
        assertThat(type.valueGroups().get(0).value()).isEqualTo("ASYNCIO");
        assertThat(type.valueGroups().get(1).nodes()).extracting("nodeName").containsExactly("c", "d");
        // Every node is party to a disagreement with no majority.
        assertThat(view.summary().driftNodes()).isEqualTo(4);
    }

    @Test
    void aKeyMissingOnOneNodeIsReportedMissingThereNotEmpty() {
        answering("a", config("{\"GlobalMaxSize\":512}"));
        answering("b", config("{\"GlobalMaxSize\":512}"));
        answering("c", config("{\"GlobalMaxSize\":512}"));
        answering("d", config("{}"));

        ConfigKeyView max = key(service.compare(clusterId, null), "broker", "/GlobalMaxSize");

        assertThat(max.state()).isEqualTo("MISSING_ON_SOME");
        assertThat(max.majority()).isEqualTo("512");
        assertThat(max.outliers()).singleElement().satisfies(o -> {
            assertThat(o.nodeName()).isEqualTo("d");
            assertThat(o.missing()).isTrue();
            assertThat(o.value()).isNull();
        });
    }

    @Test
    void anUnreachableNodeIsListedWithItsReasonAndLeftOutOfEveryMajority() {
        answering("a", config("{\"JournalType\":\"ASYNCIO\"}"));
        answering("b", config("{\"JournalType\":\"ASYNCIO\"}"));
        answering("c", config("{\"JournalType\":\"ASYNCIO\"}"));
        failing("d", Kind.UNREACHABLE, "Connection refused");

        ConfigDiffView view = service.compare(clusterId, null);

        assertThat(view.comparable()).isTrue();
        ConfigNodeView down = view.nodes().stream()
                .filter(n -> n.nodeName().equals("d"))
                .findFirst()
                .orElseThrow();
        assertThat(down.available()).isFalse();
        assertThat(down.unavailableKind()).isEqualTo("UNREACHABLE");
        assertThat(down.unavailableReason()).isEqualTo("Connection refused");
        ConfigKeyView type = key(view, "broker", "/JournalType");
        assertThat(type.state()).isEqualTo("SAME");
        assertThat(type.values()).extracting("nodeName").containsExactly("a", "b", "c");
        assertThat(view.summary().driftKeys()).isZero();
    }

    @Test
    void fewerThanTwoAnsweringNodesYieldNoComparisonAndEveryReason() {
        answering("a", config("{\"JournalType\":\"ASYNCIO\"}"));
        failing("b", Kind.CREDENTIALS_REJECTED, "401");
        failing("c", Kind.UNREACHABLE, "timed out");

        ConfigDiffView view = service.compare(clusterId, null);

        assertThat(view.comparable()).isFalse();
        assertThat(view.sections()).isEmpty();
        assertThat(view.notes()).singleElement().asString().contains("Fewer than two nodes answered");
        assertThat(view.nodes())
                .extracting(ConfigNodeView::unavailableKind)
                .containsExactly(null, "CREDENTIALS_REJECTED", "UNREACHABLE");
    }

    @Test
    void aNodeWithoutAManagementUrlIsUnavailableWithAReason() {
        answering("a", config("{}"));
        answering("b", config("{}"));
        node("c", null);

        ConfigNodeView c = service.compare(clusterId, null).nodes().get(2);

        assertThat(c.available()).isFalse();
        assertThat(c.unavailableKind()).isEqualTo("NO_MANAGEMENT_URL");
    }

    @Test
    void aPassiveBackupWithAReducedSurfaceContributesOnlyTheKeysItExposes() {
        answering("a", config("{\"JournalType\":\"ASYNCIO\",\"GlobalMaxSize\":512}"));
        answering("b", config("{\"JournalType\":\"ASYNCIO\",\"GlobalMaxSize\":512}"));
        answering("backup", config("{\"JournalType\":\"ASYNCIO\"}", SETTINGS, false, 1, 1));

        ConfigDiffView view = service.compare(clusterId, null);

        ConfigKeyView max = key(view, "broker", "/GlobalMaxSize");
        assertThat(max.state()).isEqualTo("SAME");
        assertThat(max.values()).extracting("nodeName").containsExactly("a", "b");
        assertThat(view.nodes().get(2).reducedSurface()).isTrue();
        assertThat(view.notes()).singleElement().asString().contains("backup is a passive backup");
        // A key it does expose still counts it.
        assertThat(key(view, "broker", "/JournalType").values()).hasSize(3);
    }

    @Test
    void aPassiveBackupThatExposesTheSameSurfaceIsComparedInFull() {
        answering("a", config("{\"JournalType\":\"ASYNCIO\",\"AddressNames\":[\"orders\"]}"));
        answering("backup", config("{\"JournalType\":\"ASYNCIO\",\"AddressNames\":[]}", SETTINGS, false, 1, 1));

        ConfigDiffView view = service.compare(clusterId, null);

        assertThat(view.nodes().get(1).reducedSurface()).isFalse();
        assertThat(view.notes()).isEmpty();
    }

    @Test
    void addressSettingsReturnedInADifferentOrderAreNotDrift() {
        String one = "[{\"match\":\"#\",\"maxSizeBytes\":-1},{\"match\":\"orders.#\",\"maxSizeBytes\":1024}]";
        String other = "[{\"match\":\"orders.#\",\"maxSizeBytes\":1024},{\"match\":\"#\",\"maxSizeBytes\":-1}]";
        answering("a", config("{}", one, true, 2, 2));
        answering("b", config("{}", other, true, 2, 2));
        answering("c", config("{}", one, true, 2, 2));

        ConfigDiffView view = service.compare(clusterId, null);

        assertThat(view.sections().stream()
                        .filter(s -> s.section().equals("addressSettings"))
                        .flatMap(s -> s.keys().stream()))
                .isNotEmpty()
                .allSatisfy(k -> assertThat(k.state()).isEqualTo("SAME"));
        assertThat(view.summary().driftKeys()).isZero();
    }

    @Test
    void theAddressSettingCapIsDisclosedAndEveryNodeIsReadOnce() {
        List<QueueSnapshot> known = IntStream.range(0, 40)
                .mapToObj(i -> {
                    QueueSnapshot snapshot = mock(QueueSnapshot.class);
                    when(snapshot.address()).thenReturn("addr-" + i);
                    return snapshot;
                })
                .toList();
        when(snapshots.forCluster(clusterId)).thenReturn(known);
        answering("a", config("{}", SETTINGS, true, ConfigReader.MATCH_CAP, 41));
        answering("b", config("{}", SETTINGS, true, ConfigReader.MATCH_CAP, 41));

        ConfigDiffView view = service.compare(clusterId, null);

        assertThat(view.matchesCompared()).isEqualTo(ConfigReader.MATCH_CAP);
        assertThat(view.matchesAvailable()).isEqualTo(41);
        assertThat(view.notes()).singleElement().asString().contains("Compared 25 of 41 address settings");
        verify(artemisNodes, times(2)).on(eq(clusterId), any(UUID.class));
        verify(reader, times(2)).read(any(), anyList());
    }

    @Test
    void expectedDifferencesAreCountedAndSetAsideNotCountedAsDrift() {
        answering("a", config("{\"Name\":\"a\",\"JournalType\":\"NIO\"}"));
        answering("b", config("{\"Name\":\"b\",\"JournalType\":\"NIO\"}"));

        ConfigDiffView view = service.compare(clusterId, null);

        assertThat(view.summary().driftKeys()).isZero();
        assertThat(view.summary().expectedKeys()).isEqualTo(1);
        assertThat(key(view, "broker", "/Name").classification()).isEqualTo("EXPECTED");
    }

    @Test
    void aNarrowedComparisonReadsOnlyTheNamedNodes() {
        ClusterNode a = answering("a", config("{}"));
        ClusterNode b = answering("b", config("{}"));
        answering("c", config("{}"));

        ConfigDiffView view = service.compare(clusterId, Set.of(a.getId(), b.getId()));

        assertThat(view.nodes()).extracting(ConfigNodeView::nodeName).containsExactly("a", "b");
        verify(reader, times(2)).read(any(), anyList());
    }

    @Test
    void aNarrowedComparisonNeedsAtLeastTwoKnownNodes() {
        ClusterNode a = answering("a", config("{}"));
        answering("b", config("{}"));

        Set<UUID> onlyOne = Set.of(a.getId());
        Set<UUID> oneUnknown = Set.of(a.getId(), UUID.randomUUID());

        assertThatThrownBy(() -> service.compare(clusterId, onlyOne)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(clusterId, oneUnknown)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theComparisonRequiresTheTopologyReadPermission() {
        ClusterAccessGuard guard = mock(ClusterAccessGuard.class);
        service = new ConfigDiffService(directory, snapshots, connections, reader, guard, artemisNodes);

        service.compare(clusterId, null);

        verify(guard).requireCluster(clusterId, Permissions.CLUSTER_READ);
    }
}
