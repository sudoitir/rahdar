package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The EXPLAIN strip prints the AST back in the dialect the operator wrote. */
class PredicateTextTest {

    private static final Term SUBSCRIPTION = new Term.ColumnTerm(Column.SUBSCRIPTION);

    @Test
    void comparesRenderEachOperatorAndLiteralKind() {
        assertThat(PredicateText.of(new Predicate.Compare(SUBSCRIPTION, Operator.EQ, new Literal.Str("orders"))))
                .isEqualTo("subscription = 'orders'");
        assertThat(PredicateText.of(new Predicate.Compare(SUBSCRIPTION, Operator.GTE, new Literal.Num(5, true))))
                .isEqualTo("subscription >= 5");
        assertThat(PredicateText.of(new Predicate.Compare(SUBSCRIPTION, Operator.LT, new Literal.Num(2.5, false))))
                .isEqualTo("subscription < 2.5");
        assertThat(PredicateText.of(new Predicate.Compare(SUBSCRIPTION, Operator.NE, new Literal.Bool(true))))
                .isEqualTo("subscription <> true");
    }

    @Test
    void relativeTimeIsNowOrNowMinusAnInterval() {
        assertThat(PredicateText.of(
                        new Predicate.Compare(SUBSCRIPTION, Operator.LT, new Literal.RelativeTime(Duration.ZERO))))
                .isEqualTo("subscription < now()");
        assertThat(PredicateText.of(new Predicate.Compare(
                        SUBSCRIPTION, Operator.GT, new Literal.RelativeTime(Duration.ofHours(2)))))
                .isEqualTo("subscription > now() - interval 'PT2H'");
    }

    @Test
    void andJoinsBareAndOrIsParenthesisedAndNotPrefixes() {
        Predicate a = new Predicate.Compare(SUBSCRIPTION, Operator.EQ, new Literal.Str("a"));
        Predicate b = new Predicate.IsNull(SUBSCRIPTION, false);

        assertThat(PredicateText.of(new Predicate.And(List.of(a, b))))
                .isEqualTo("subscription = 'a' AND subscription IS NULL");
        assertThat(PredicateText.of(new Predicate.Or(List.of(a, b))))
                .isEqualTo("(subscription = 'a' OR subscription IS NULL)");
        assertThat(PredicateText.of(new Predicate.Not(a))).isEqualTo("NOT subscription = 'a'");
    }

    @Test
    void inAndIsNullAndBetweenRenderBothPolarities() {
        List<Literal> values = List.of(new Literal.Str("a"), new Literal.Num(1, true));
        assertThat(PredicateText.of(new Predicate.In(SUBSCRIPTION, values, false)))
                .isEqualTo("subscription IN ('a', 1)");
        assertThat(PredicateText.of(new Predicate.In(SUBSCRIPTION, values, true)))
                .isEqualTo("subscription NOT IN ('a', 1)");

        assertThat(PredicateText.of(new Predicate.IsNull(SUBSCRIPTION, false))).isEqualTo("subscription IS NULL");
        assertThat(PredicateText.of(new Predicate.IsNull(SUBSCRIPTION, true))).isEqualTo("subscription IS NOT NULL");

        Literal low = new Literal.Num(1, true);
        Literal high = new Literal.Num(9, true);
        assertThat(PredicateText.of(new Predicate.Between(SUBSCRIPTION, low, high, false)))
                .isEqualTo("subscription BETWEEN 1 AND 9");
        assertThat(PredicateText.of(new Predicate.Between(SUBSCRIPTION, low, high, true)))
                .isEqualTo("subscription NOT BETWEEN 1 AND 9");
    }

    @Test
    void likeRendersCaseSensitivityAndNegation() {
        assertThat(PredicateText.of(new Predicate.Like(SUBSCRIPTION, "a%", null, false, false)))
                .isEqualTo("subscription LIKE 'a%'");
        assertThat(PredicateText.of(new Predicate.Like(SUBSCRIPTION, "a%", null, false, true)))
                .isEqualTo("subscription ILIKE 'a%'");
        assertThat(PredicateText.of(new Predicate.Like(SUBSCRIPTION, "a%", null, true, false)))
                .isEqualTo("subscription NOT LIKE 'a%'");
        assertThat(PredicateText.of(new Predicate.Like(SUBSCRIPTION, "a%", null, true, true)))
                .isEqualTo("subscription NOT ILIKE 'a%'");
    }

    @Test
    void matchRendersTheSearchTerms() {
        assertThat(PredicateText.of(new Predicate.Match("\"exact phrase\" -no")))
                .isEqualTo("MATCH(body, '\"exact phrase\" -no')");
    }

    @Test
    void termsRenderColumnsPropertiesJsonPathsCaseFoldsAndRank() {
        assertThat(PredicateText.term(SUBSCRIPTION)).isEqualTo("subscription");
        assertThat(PredicateText.term(new Term.PropertyTerm("tenant"))).isEqualTo("props.tenant");
        assertThat(PredicateText.term(new Term.JsonTerm("a.b"))).isEqualTo("body->>'a.b'");
        assertThat(PredicateText.term(new Term.CaseFold(SUBSCRIPTION, true))).isEqualTo("upper(subscription)");
        assertThat(PredicateText.term(new Term.CaseFold(new Term.PropertyTerm("x"), false)))
                .isEqualTo("lower(props.x)");
        assertThat(PredicateText.term(new Term.MatchRank())).isEqualTo("match_rank");
    }
}
