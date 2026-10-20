package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.GovernContext;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import io.github.sudoitir.rahdar.platform.governance.Location;
import io.github.sudoitir.rahdar.platform.governance.MessageContent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlGovernanceTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final SqlGovernance governance = new SqlGovernance(policy);

    @BeforeEach
    void policy() {
        when(policy.classifies(any(), anyString())).thenReturn(false);
        when(policy.classifies(Location.PROPERTY, "customerEmail")).thenReturn(true);
        when(policy.classifies(Location.HEADER, "correlationId")).thenReturn(true);
        clear(false);
    }

    private void clear(boolean clear) {
        when(policy.mayClear(eq(CLUSTER), anyString())).thenReturn(clear);
    }

    private static QueryAst where(Predicate predicate, List<QueryAst.Order> orderBy) {
        return new QueryAst(QueryAst.Source.DEFAULT, "orders", List.of(), predicate, orderBy, null, "SELECT ...");
    }

    private static Predicate.Compare eqStr(Term term, String value) {
        return new Predicate.Compare(term, Operator.EQ, new Literal.Str(value));
    }

    @Test
    void aPredicateOnAMaskedPropertyIsRefusedNamingTheField() {
        QueryAst ast = where(
                new Predicate.Or(List.of(
                        eqStr(new Term.PropertyTerm("region"), "eu"),
                        new Predicate.Not(
                                eqStr(new Term.CaseFold(new Term.PropertyTerm("customerEmail"), false), "x")))),
                List.of());

        assertThatThrownBy(() -> governance.guardPredicates(CLUSTER, ast))
                .isInstanceOf(GovernanceRefusedException.class)
                .hasMessageContaining("props.customerEmail")
                .satisfies(e ->
                        assertThat(((GovernanceRefusedException) e).field()).isEqualTo("props.customerEmail"));
    }

    @Test
    void orderingByAMaskedHeaderIsRefused() {
        QueryAst ast = where(
                null, List.of(new QueryAst.Order(new Term.ColumnTerm(Column.CORRELATION_ID), QueryAst.Direction.ASC)));

        assertThatThrownBy(() -> governance.guardPredicates(CLUSTER, ast))
                .isInstanceOf(GovernanceRefusedException.class);
    }

    @Test
    void clearAccessMayFilterOnAMaskedField() {
        clear(true);
        QueryAst ast = where(eqStr(new Term.PropertyTerm("customerEmail"), "x"), List.of());

        assertThatCode(() -> governance.guardPredicates(CLUSTER, ast)).doesNotThrowAnyException();
    }

    @Test
    void unclassifiedFieldsAndFullTextSearchAreAllowed() {
        QueryAst ast = where(
                new Predicate.And(List.of(eqStr(new Term.PropertyTerm("region"), "eu"), new Predicate.Match("jane"))),
                List.of());

        assertThatCode(() -> governance.guardPredicates(CLUSTER, ast)).doesNotThrowAnyException();
    }

    @Test
    void aResidualIsEvaluatedAgainstTheMaskedCopyWithTypesKept() {
        BrowsedMessage raw = new BrowsedMessage(
                7,
                3,
                true,
                4,
                1L,
                0L,
                10L,
                "g",
                "corr",
                null,
                null,
                "{\"email\":\"jane@example.com\"}",
                BodyEncoding.TEXT,
                Compression.NONE,
                "application/json",
                false,
                null,
                Map.of("contact", "jane@example.com"),
                Map.of("count", 3L),
                Map.of(),
                Map.of(),
                Map.of("vip", true));
        when(policy.govern(eq(GovernContext.masked(CLUSTER, "orders")), any()))
                .thenReturn(new GovernedMessage(
                        Map.of("groupId", "g", "correlationId", "corr"),
                        Map.of("contact", "[redacted email]", "count", 3L, "vip", true),
                        "{\"email\":\"[redacted email]\"}",
                        List.of(),
                        List.of(),
                        Map.of(),
                        1));

        BrowsedMessage evaluated = governance.forEvaluation(CLUSTER, "orders", raw);

        assertThat(evaluated.body()).doesNotContain("jane@example.com");
        assertThat(evaluated.stringProperties()).containsEntry("contact", "[redacted email]");
        assertThat(evaluated.intProperties()).containsEntry("count", 3L);
        assertThat(evaluated.booleanProperties()).containsEntry("vip", true);
        assertThat(evaluated.messageId()).isEqualTo(7);
    }

    // ---- rows ----------------------------------------------------------

    private static QueryResult.Row row(String address, int messageType) {
        return row(address, messageType, false);
    }

    private static QueryResult.Row row(String address, int messageType, boolean bodyBase64) {
        return new QueryResult.Row(
                UUID.randomUUID(),
                "node-a",
                "orders",
                address,
                42,
                messageType,
                true,
                4,
                1000L,
                2000L,
                64L,
                "jms-type",
                "corr",
                "grp",
                "user",
                "reply",
                "body text",
                true,
                bodyBase64,
                null,
                Map.of("k", "v"),
                QueryAst.Source.INDEX,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                "CAPTURED",
                99L);
    }

    @Test
    void clearAccessIsWhatThePolicyReportsForTheCaller() {
        assertThat(governance.clearAccess(CLUSTER, "orders")).isFalse();
        clear(true);
        assertThat(governance.clearAccess(CLUSTER, "orders")).isTrue();
    }

    @Test
    void contentCarriesTheFourHeadersPropertiesAndWhetherTheBodyIsBinary() {
        var text = SqlGovernance.content(row("orders", 3));
        assertThat(text.headers())
                .containsEntry("correlationId", "corr")
                .containsEntry("groupId", "grp")
                .containsEntry("userId", "user")
                .containsEntry("replyTo", "reply");
        assertThat(text.properties()).containsEntry("k", "v");
        assertThat(text.body()).isEqualTo("body text");
        assertThat(text.base64()).isFalse();

        // Binary is a property of the stored body, not the message type: a bytes message carrying text is text.
        assertThat(SqlGovernance.content(row("orders", 4)).base64()).isFalse();
        assertThat(SqlGovernance.content(row("orders", 4, true)).base64()).isTrue();
    }

    @Test
    void withContentReplacesOnlyTheGovernedFields() {
        QueryResult.Row original = row("orders", 3);
        var replaced = SqlGovernance.withContent(
                original,
                new MessageContent(
                        Map.of("correlationId", "c2", "groupId", "g2", "userId", "u2", "replyTo", "r2"),
                        Map.of("p", 1L),
                        "masked",
                        false,
                        null));

        assertThat(replaced.correlationId()).isEqualTo("c2");
        assertThat(replaced.groupId()).isEqualTo("g2");
        assertThat(replaced.userId()).isEqualTo("u2");
        assertThat(replaced.replyTo()).isEqualTo("r2");
        assertThat(replaced.body()).isEqualTo("masked");
        assertThat(replaced.properties()).containsEntry("p", 1L);
        assertThat(replaced.messageId()).isEqualTo(original.messageId());
        assertThat(replaced.queueName()).isEqualTo("orders");
        assertThat(replaced.bodyTruncated()).isTrue();
        assertThat(replaced.origin()).isEqualTo("CAPTURED");
        assertThat(replaced.sourceMessageId()).isEqualTo(99L);
        assertThat(replaced.lastSeenAt()).isEqualTo(original.lastSeenAt());
    }

    @Test
    void governAndStorageHandTheRowsContentToThePolicyWithTheCallersAccess() {
        GovernedMessage governed = new GovernedMessage(Map.of(), Map.of(), "m", List.of(), List.of(), Map.of(), 1);
        when(policy.govern(any(), any())).thenReturn(governed);
        when(policy.governForStorage(eq(CLUSTER), eq("orders"), any())).thenReturn(governed);

        assertThat(governance.govern(CLUSTER, true, row("orders", 3))).isSameAs(governed);
        assertThat(governance.forStorage(CLUSTER, row("orders", 3))).isSameAs(governed);
        assertThat(governance.forStorage(CLUSTER, "orders", SqlGovernance.content(row("orders", 3))))
                .isSameAs(governed);

        verify(policy).govern(eq(new GovernContext(CLUSTER, "orders", true)), any());
    }

    // ---- masked fields -------------------------------------------------

    @Test
    void everyPredicateShapeIsSearchedForAMaskedTerm() {
        Term masked = new Term.PropertyTerm("customerEmail");
        var literal = new Literal.Str("x");

        assertThat(governance.maskedField(where(new Predicate.In(masked, List.of(literal), false), List.of())))
                .isEqualTo("props.customerEmail");
        assertThat(governance.maskedField(where(new Predicate.IsNull(masked, false), List.of())))
                .isEqualTo("props.customerEmail");
        assertThat(governance.maskedField(where(new Predicate.Like(masked, "a%", null, false, false), List.of())))
                .isEqualTo("props.customerEmail");
        assertThat(governance.maskedField(where(new Predicate.Between(masked, literal, literal, false), List.of())))
                .isEqualTo("props.customerEmail");
        assertThat(governance.maskedField(where(new Predicate.And(List.of(eqStr(masked, "x"))), List.of())))
                .isEqualTo("props.customerEmail");
    }

    @Test
    void jsonPathsAndHeaderColumnsAreCheckedAgainstTheirOwnLocations() {
        when(policy.classifies(Location.BODY, "card.number")).thenReturn(true);

        assertThat(governance.maskedField(where(eqStr(new Term.JsonTerm("card.number"), "x"), List.of())))
                .isEqualTo("body->>'card.number'");
        assertThat(governance.maskedField(where(eqStr(new Term.JsonTerm("other"), "x"), List.of())))
                .isNull();
        assertThat(governance.maskedField(where(eqStr(new Term.ColumnTerm(Column.CORRELATION_ID), "x"), List.of())))
                .isEqualTo(Column.CORRELATION_ID.sqlName());
        assertThat(governance.maskedField(where(eqStr(new Term.ColumnTerm(Column.GROUP_ID), "x"), List.of())))
                .as("a header column the policy does not classify")
                .isNull();
        assertThat(governance.maskedField(where(eqStr(new Term.ColumnTerm(Column.QUEUE), "x"), List.of())))
                .as("a column that is not a header at all")
                .isNull();
        assertThat(governance.maskedField(
                        where(null, List.of(new QueryAst.Order(new Term.MatchRank(), QueryAst.Direction.DESC)))))
                .isNull();
    }

    @Test
    void aQueryWithNoPredicateAndNoOrderingNamesNoField() {
        QueryAst ast = new QueryAst(QueryAst.Source.DEFAULT, "q", List.of(), null, null, null, "SELECT");

        assertThat(governance.maskedField(ast)).isNull();
    }

    // ---- at-rest notice ------------------------------------------------

    private static QueryPlan plan(QueryAst ast, QueryAst.Source source) {
        return new QueryPlan(ast, source, List.of(), null, false, List.of(), List.of(), 10, 100, false, List.of());
    }

    @Test
    void anIndexQueryOnAMaskedFieldGainsAMaskedAtRestNotice() {
        QueryAst ast = where(eqStr(new Term.PropertyTerm("customerEmail"), "x"), List.of());
        QueryPlan plan = plan(ast, QueryAst.Source.INDEX);

        QueryPlan noticed = governance.withAtRestNotice(plan);

        assertThat(noticed.notices()).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(QueryPlan.Notice.Kind.MASKED_AT_REST);
            assertThat(n.detail()).contains("props.customerEmail");
        });
        assertThat(noticed.ast()).isSameAs(ast);
        assertThat(noticed.effectiveLimit()).isEqualTo(100);
    }

    @Test
    void otherPlansAreReturnedUntouched() {
        QueryAst masked = where(eqStr(new Term.PropertyTerm("customerEmail"), "x"), List.of());
        QueryPlan broker = plan(masked, QueryAst.Source.BROKER);
        assertThat(governance.withAtRestNotice(broker)).isSameAs(broker);

        QueryPlan clean = plan(where(eqStr(new Term.PropertyTerm("region"), "eu"), List.of()), QueryAst.Source.INDEX);
        assertThat(governance.withAtRestNotice(clean)).isSameAs(clean);
    }

    // ---- evaluation copy -----------------------------------------------

    @Test
    void evaluationKeepsLongDoubleAndTextTypesAndSkipsNulls() {
        BrowsedMessage raw = new BrowsedMessage(
                1,
                3,
                true,
                4,
                1L,
                0L,
                10L,
                "g",
                "corr",
                "rt",
                "usr",
                "b",
                BodyEncoding.TEXT,
                Compression.NONE,
                null,
                false,
                null,
                Map.of(),
                Map.of(),
                Map.of("big", 5L),
                Map.of("ratio", 0.5),
                Map.of());
        var properties = new HashMap<String, Object>();
        properties.put("big", 5L);
        properties.put("ratio", 0.5);
        properties.put("other", 12L);
        properties.put("gone", null);
        properties.put("label", "text");
        when(policy.govern(eq(GovernContext.masked(CLUSTER, "orders")), any()))
                .thenReturn(new GovernedMessage(
                        Map.of("replyTo", "rt", "userId", "usr"), properties, "b", List.of(), List.of(), Map.of(), 1));

        BrowsedMessage evaluated = governance.forEvaluation(CLUSTER, "orders", raw);

        assertThat(evaluated.longProperties()).containsEntry("big", 5L);
        assertThat(evaluated.doubleProperties()).containsEntry("ratio", 0.5);
        assertThat(evaluated.stringProperties())
                .containsEntry("label", "text")
                .containsEntry("other", "12")
                .doesNotContainKey("gone");
        assertThat(evaluated.replyTo()).isEqualTo("rt");
        assertThat(evaluated.userId()).isEqualTo("usr");
    }
}
