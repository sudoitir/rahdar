package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Target-level conjuncts run during planning; a conjunct that says nothing about a
 * target must never exclude it.
 */
class TargetPredicateEvaluatorTest {

    private static final Target TARGET =
            new Target(UUID.randomUUID(), "node-a", "ORDERS", "orders.addr", "ANYCAST", 3L, Instant.EPOCH, true);

    private static Term.ColumnTerm col(Column column) {
        return new Term.ColumnTerm(column);
    }

    private static boolean matches(Predicate p) {
        return TargetPredicateEvaluator.matches(p, TARGET);
    }

    @ParameterizedTest
    @CsvSource({
        "EQ,ORDERS,true",
        "EQ,OTHER,false",
        "NE,OTHER,true",
        "NE,ORDERS,false",
        "LT,ZZZ,true",
        "LT,AAA,false",
        "LTE,ORDERS,true",
        "LTE,AAA,false",
        "GT,AAA,true",
        "GT,ORDERS,false",
        "GTE,ORDERS,true",
        "GTE,ZZZ,false"
    })
    void everyOperatorComparesTheQueueName(Operator op, String literal, boolean expected) {
        assertThat(matches(new Predicate.Compare(col(Column.SUBSCRIPTION), op, new Literal.Str(literal))))
                .isEqualTo(expected);
    }

    @Test
    void addressAndNodeColumnsReadTheTarget() {
        assertThat(matches(new Predicate.Compare(col(Column.DESTINATION), Operator.EQ, new Literal.Str("orders.addr"))))
                .isTrue();
        assertThat(matches(new Predicate.Compare(col(Column.NODE), Operator.EQ, new Literal.Str("node-b"))))
                .isFalse();
    }

    @Test
    void aComparisonThatSaysNothingAboutTheTargetExcludesNothing() {
        // not a target column
        assertThat(matches(new Predicate.Compare(col(Column.PRIORITY), Operator.EQ, new Literal.Str("x"))))
                .isTrue();
        // not a column term at all
        assertThat(matches(new Predicate.Compare(new Term.PropertyTerm("p"), Operator.EQ, new Literal.Str("x"))))
                .isTrue();
        // not a string literal
        assertThat(matches(new Predicate.Compare(col(Column.SUBSCRIPTION), Operator.EQ, new Literal.Num(1, true))))
                .isTrue();
    }

    @Test
    void inMatchesStringMembersAndNegates() {
        var values = List.<Literal>of(new Literal.Num(1, true), new Literal.Str("ORDERS"));

        assertThat(matches(new Predicate.In(col(Column.SUBSCRIPTION), values, false)))
                .isTrue();
        assertThat(matches(new Predicate.In(col(Column.SUBSCRIPTION), values, true)))
                .isFalse();
        assertThat(matches(new Predicate.In(col(Column.SUBSCRIPTION), List.of(new Literal.Str("X")), false)))
                .isFalse();
        assertThat(matches(new Predicate.In(col(Column.PRIORITY), values, false)))
                .isTrue();
    }

    @Test
    void isNullOnATargetColumnIsNeverTrueAndOnOtherTermsAlwaysIs() {
        assertThat(matches(new Predicate.IsNull(col(Column.SUBSCRIPTION), false)))
                .isFalse();
        assertThat(matches(new Predicate.IsNull(col(Column.SUBSCRIPTION), true)))
                .isTrue();
        assertThat(matches(new Predicate.IsNull(col(Column.BODY), false))).isTrue();
    }

    @Test
    void likeMatchesWithCaseAndNegation() {
        assertThat(matches(new Predicate.Like(col(Column.SUBSCRIPTION), "ORD%", null, false, false)))
                .isTrue();
        assertThat(matches(new Predicate.Like(col(Column.SUBSCRIPTION), "ord%", null, false, false)))
                .isFalse();
        assertThat(matches(new Predicate.Like(col(Column.SUBSCRIPTION), "ord%", null, false, true)))
                .isTrue();
        assertThat(matches(new Predicate.Like(col(Column.SUBSCRIPTION), "ORD%", null, true, false)))
                .isFalse();
        assertThat(matches(new Predicate.Like(col(Column.BODY), "x", null, false, false)))
                .isTrue();
    }

    @Test
    void booleanCombinatorsAndNonTargetPredicates() {
        var yes = new Predicate.Compare(col(Column.SUBSCRIPTION), Operator.EQ, new Literal.Str("ORDERS"));
        var no = new Predicate.Compare(col(Column.SUBSCRIPTION), Operator.EQ, new Literal.Str("X"));

        assertThat(matches(new Predicate.And(List.of(yes, no)))).isFalse();
        assertThat(matches(new Predicate.And(List.of(yes, yes)))).isTrue();
        assertThat(matches(new Predicate.Or(List.of(no, yes)))).isTrue();
        assertThat(matches(new Predicate.Or(List.of(no, no)))).isFalse();
        assertThat(matches(new Predicate.Not(no))).isTrue();
        assertThat(matches(new Predicate.Between(
                        col(Column.SUBSCRIPTION), new Literal.Str("a"), new Literal.Str("b"), false)))
                .isTrue();
        assertThat(matches(new Predicate.Match("foo"))).isTrue();
    }
}
