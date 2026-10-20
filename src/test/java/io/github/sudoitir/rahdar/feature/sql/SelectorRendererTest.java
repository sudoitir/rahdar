package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Evaluation;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The renderer is where Rahdar's text reaches the broker, and the splitter is what
 * decides whether a predicate gets there at all. The cases that matter are the ones
 * where a predicate must <em>not</em> be pushed down — those fail silently, by
 * returning a plausible but wrong set of messages.
 */
class SelectorRendererTest {

    private final SqlQueryParser parser = new SqlQueryParser();
    private final SelectorRenderer renderer = new SelectorRenderer();
    private final PredicateSplitter splitter = new PredicateSplitter(renderer);

    private String selectorFor(String where) {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE " + where);
        return renderer.render(splitter.split(ast.where()).pushdown(), Instant.ofEpochMilli(1_000_000L));
    }

    private Evaluation classify(String where) {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE " + where);
        return splitter.classify(ast.where());
    }

    @Test
    void aHeaderComparisonRendersToItsSelectorIdentifier() {
        assertThat(selectorFor("priority > 4")).isEqualTo("JMSPriority > 4");
        assertThat(selectorFor("correlationId = 'abc'")).isEqualTo("JMSCorrelationID = 'abc'");
        assertThat(selectorFor("size >= 1024")).isEqualTo("AMQSize >= 1024");
    }

    @Test
    void aQuoteInALiteralIsDoubledNotEscaped() {
        // JMS has no backslash escape; doubling is the only way, and getting it wrong
        // is how a queue name with an apostrophe changes what a selector means.
        assertThat(selectorFor("props.tenant = 'O''Brien'")).isEqualTo("tenant = 'O''Brien'");
    }

    @Test
    void durabilityIsMappedToArtemisSpelling() {
        assertThat(selectorFor("durable = true")).isEqualTo("AMQDurable = 'DURABLE'");
        assertThat(selectorFor("durable = false")).isEqualTo("AMQDurable = 'NON_DURABLE'");
    }

    @Test
    void anOrderingComparisonOnDurabilityIsNotPushedDown() {
        // There is no ordering of DURABLE and NON_DURABLE. Rendering one would be a
        // guess, so the predicate is demoted to a scan and still applied.
        assertThat(classify("durable > false")).isEqualTo(Evaluation.SCAN);
    }

    @Test
    void inAndBetweenAndIsNullRender() {
        assertThat(selectorFor("props.t IN ('a','b')")).isEqualTo("t IN ('a', 'b')");
        assertThat(selectorFor("priority BETWEEN 1 AND 4")).isEqualTo("JMSPriority BETWEEN 1 AND 4");
        assertThat(selectorFor("correlationId IS NOT NULL")).isEqualTo("JMSCorrelationID IS NOT NULL");
    }

    @Test
    void aRelativeWindowIsResolvedAgainstTheBrokerClock() {
        // now() is the broker's now, not Rahdar's (ADR-0053).
        assertThat(selectorFor("timestamp > now() - interval '1 second'")).isEqualTo("AMQTimestamp > 999000");
    }

    @Test
    void aBodyPredicateNeverReachesTheSelector() {
        assertThat(classify("body LIKE '%x%'")).isEqualTo(Evaluation.SCAN);
        assertThat(classify("body->>'orderId' = '1'")).isEqualTo(Evaluation.SCAN);
        assertThat(selectorFor("body LIKE '%x%'")).isNull();
    }

    @Test
    void ilikeIsAScanRatherThanAnApproximation() {
        assertThat(classify("props.t ILIKE 'a%'")).isEqualTo(Evaluation.SCAN);
        assertThat(classify("lower(props.t) = 'a'")).isEqualTo(Evaluation.SCAN);
    }

    @Test
    void aPropertyNameASelectorCannotSpellIsAScan() {
        // A selector has no quoting for identifiers, so a name with a dash cannot be
        // pushed down. Escaping it would produce a selector that means something else.
        assertThat(classify("props.\"tenant-id\" = 'a'")).isEqualTo(Evaluation.SCAN);
    }

    @Test
    void aDisjunctionWithAResidualLeafIsNotPartiallyPushedDown() {
        // The silent-wrong-answer case: pushing down priority > 4 would hide the
        // messages the body clause was supposed to find.
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE priority > 4 OR body LIKE '%x%'");
        PredicateSplitter.Split split = splitter.split(ast.where());

        assertThat(split.pushdown()).isNull();
        assertThat(split.scan()).isNotNull();
        assertThat(split.requiresScan()).isTrue();
    }

    @Test
    void aConjunctionSplitsPerSideSoTheCheapHalfStillPushesDown() {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE priority > 4 AND body LIKE '%x%'");
        PredicateSplitter.Split split = splitter.split(ast.where());

        assertThat(renderer.render(split.pushdown(), Instant.EPOCH)).isEqualTo("JMSPriority > 4");
        assertThat(split.requiresScan()).isTrue();
    }

    @Test
    void aQueueOrNodePredicateFiltersTargetsRatherThanMessages() {
        QueryAst ast = parser.parse("SELECT * FROM \"ORDER.*\" WHERE node = 'broker-1' AND priority > 4");
        PredicateSplitter.Split split = splitter.split(ast.where());

        assertThat(split.target()).isNotNull();
        assertThat(renderer.render(split.pushdown(), Instant.EPOCH)).isEqualTo("JMSPriority > 4");
        assertThat(split.requiresScan()).isFalse();
    }

    // ---- the AST rendered directly, for the shapes the parser does not produce ----

    private static final Term PRIORITY = new Term.ColumnTerm(Column.PRIORITY);
    private static final Term TENANT = new Term.PropertyTerm("tenant");

    private String render(Predicate predicate) {
        return renderer.render(predicate, Instant.ofEpochMilli(10_000L));
    }

    @Test
    void aNullPredicateRendersNothing() {
        assertThat(renderer.render(null, Instant.EPOCH)).isNull();
    }

    @Test
    void anEmptyGroupRendersAsAnEmptyParenthesis() {
        assertThat(render(new Predicate.And(List.of()))).isEqualTo("()");
        assertThat(renderer.render(new Predicate.Not(new Predicate.And(List.of())), Instant.EPOCH))
                .isEqualTo("(NOT ())");
    }

    @Test
    void andOrAndNotAreParenthesised() {
        Predicate a = new Predicate.Compare(PRIORITY, Operator.GT, new Literal.Num(4, true));
        Predicate b = new Predicate.Compare(TENANT, Operator.EQ, new Literal.Str("acme"));

        assertThat(render(new Predicate.And(List.of(a, b)))).isEqualTo("(JMSPriority > 4 AND tenant = 'acme')");
        assertThat(render(new Predicate.Or(List.of(a, b)))).isEqualTo("(JMSPriority > 4 OR tenant = 'acme')");
        assertThat(render(new Predicate.Not(a))).isEqualTo("(NOT JMSPriority > 4)");
    }

    @Test
    void literalsRenderPerType() {
        assertThat(render(new Predicate.Compare(TENANT, Operator.EQ, new Literal.Num(1.5, false))))
                .isEqualTo("tenant = 1.5");
        assertThat(render(new Predicate.Compare(TENANT, Operator.NE, new Literal.Num(7, true))))
                .isEqualTo("tenant <> 7");
        assertThat(render(new Predicate.Compare(TENANT, Operator.EQ, new Literal.Bool(true))))
                .isEqualTo("tenant = TRUE");
        assertThat(render(new Predicate.Compare(TENANT, Operator.LT, new Literal.RelativeTime(Duration.ofSeconds(4)))))
                .isEqualTo("tenant < 6000");
    }

    @Test
    void negatedFormsRender() {
        assertThat(render(new Predicate.In(TENANT, List.of(new Literal.Str("a")), true)))
                .isEqualTo("tenant NOT IN ('a')");
        assertThat(render(new Predicate.IsNull(TENANT, false))).isEqualTo("tenant IS NULL");
        assertThat(render(new Predicate.Between(PRIORITY, new Literal.Num(1, true), new Literal.Num(3, true), true)))
                .isEqualTo("JMSPriority NOT BETWEEN 1 AND 3");
    }

    @Test
    void likeRendersEscapeAndNegation() {
        assertThat(render(new Predicate.Like(TENANT, "a\\_%", '\\', false, false)))
                .isEqualTo("tenant LIKE 'a\\_%' ESCAPE '\\'");
        assertThat(render(new Predicate.Like(TENANT, "it's%", null, true, false)))
                .isEqualTo("tenant NOT LIKE 'it''s%'");
    }

    @Test
    void durabilityNotEqualIsRendered() {
        Term durable = new Term.ColumnTerm(Column.DURABLE);

        assertThat(render(new Predicate.Compare(durable, Operator.NE, new Literal.Bool(true))))
                .isEqualTo("AMQDurable <> 'DURABLE'");
    }

    @Test
    void durabilityAgainstANonBooleanIsUnrenderable() {
        Predicate p = new Predicate.Compare(new Term.ColumnTerm(Column.DURABLE), Operator.EQ, new Literal.Str("x"));

        assertThatThrownBy(() -> render(p))
                .isInstanceOf(SelectorRenderer.UnrenderableSelectorException.class)
                .hasMessageContaining("true or false");
        assertThat(renderer.isRenderable(p)).isFalse();
    }

    @Test
    void unrenderableShapesAreReportedNotApproximated() {
        Predicate ilike = new Predicate.Like(TENANT, "a%", null, false, true);
        Predicate match = new Predicate.Match("word");
        Predicate onBody = new Predicate.IsNull(new Term.JsonTerm("$.a"), false);
        Predicate folded = new Predicate.IsNull(new Term.CaseFold(TENANT, false), false);
        Predicate rank = new Predicate.IsNull(new Term.MatchRank(), false);
        Predicate noSelector = new Predicate.IsNull(new Term.ColumnTerm(Column.SUBSCRIPTION), false);
        Predicate ordering =
                new Predicate.Compare(new Term.ColumnTerm(Column.DURABLE), Operator.GT, new Literal.Bool(true));

        assertThatThrownBy(() -> render(ilike)).hasMessageContaining("case-insensitive");
        assertThatThrownBy(() -> render(match)).hasMessageContaining("MATCH()");
        assertThatThrownBy(() -> render(onBody)).hasMessageContaining("read the body");
        assertThatThrownBy(() -> render(folded)).hasMessageContaining("fold case");
        assertThatThrownBy(() -> render(rank)).hasMessageContaining("match_rank");
        assertThatThrownBy(() -> render(noSelector)).hasMessageContaining("no selector identifier for subscription");
        assertThatThrownBy(() -> render(ordering)).hasMessageContaining("= or <>");
        assertThat(List.of(ilike, match, onBody, folded, rank, noSelector, ordering))
                .noneMatch(renderer::isRenderable);
    }

    @Test
    void aRenderablePredicateIsReportedRenderable() {
        assertThat(renderer.isRenderable(new Predicate.Compare(PRIORITY, Operator.EQ, new Literal.Num(1, true))))
                .isTrue();
    }
}
