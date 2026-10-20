package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Direction;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Clause, term and literal rules of the dialect that {@link SqlQueryParserTest} does
 * not walk: each accepted spelling produces its AST, each refusal names its reason.
 */
class SqlQueryParserRulesTest {

    private final SqlQueryParser parser = new SqlQueryParser();

    private QueryAst parse(String where) {
        return parser.parse("SELECT * FROM \"q\" WHERE " + where);
    }

    private Predicate where(String where) {
        return parse(where).where();
    }

    private void rejects(String sql, String messagePart) {
        assertThatThrownBy(() -> parser.parse(sql))
                .isInstanceOf(SqlSyntaxException.class)
                .hasMessageContaining(messagePart);
    }

    private void rejectsWhere(String where, String messagePart) {
        rejects("SELECT * FROM \"q\" WHERE " + where, messagePart);
    }

    // ---- clauses -----------------------------------------------------------

    @Test
    void invalidSqlIsReportedAsSuch() {
        rejects("SELEC nonsense", "Not valid SQL");
    }

    @Test
    void fetchAndIntoAreOutsideTheDialect() {
        rejects("SELECT * FROM \"q\" FETCH FIRST 5 ROWS ONLY", "OFFSET or FETCH");
        rejects("SELECT * INTO backup FROM \"q\"", "SELECT INTO");
    }

    @Test
    void aFromThatIsNotAQueueIsRefused() {
        rejects("SELECT * FROM (SELECT * FROM \"q\") t", "FROM must name a subscription");
    }

    @Test
    void aSelectWithoutFromIsRefused() {
        rejects("SELECT 1", "FROM must name a subscription");
    }

    @Test
    void theProjectionNarrowsToCatalogueColumnsOnly() {
        assertThat(parser.parse("SELECT priority, subscription FROM \"q\"").projection())
                .containsExactly(Column.PRIORITY, Column.SUBSCRIPTION);
        assertThat(parser.parse("SELECT * FROM \"q\"").projection()).isEmpty();
        // a property or json path asks for the whole row
        assertThat(parser.parse("SELECT priority, props.tenant FROM \"q\"").projection())
                .isEmpty();
    }

    @Test
    void orderByCarriesDirectionsAndAbsenceIsEmpty() {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" ORDER BY priority DESC, props.a ASC, size");

        assertThat(ast.orderBy()).hasSize(3);
        assertThat(ast.orderBy().get(0).direction()).isEqualTo(Direction.DESC);
        assertThat(ast.orderBy().get(1).term()).isEqualTo(new Term.PropertyTerm("a"));
        assertThat(ast.orderBy().get(2).direction()).isEqualTo(Direction.ASC);
        assertThat(parser.parse("SELECT * FROM \"q\"").orderBy()).isEmpty();
        assertThat(parser.parse("SELECT * FROM \"q\"").limit()).isNull();
    }

    @Test
    void orderByMatchRankIsATermOnlyWhenUnqualified() {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" ORDER BY match_rank DESC");

        assertThat(ast.orderBy().getFirst().term()).isEqualTo(new Term.MatchRank());
    }

    @Test
    void aLimitMustBeAPositiveWholeNumberAndIsCapped() {
        assertThat(parser.parse("SELECT * FROM \"q\" LIMIT 9999999999").limit()).isEqualTo(Integer.MAX_VALUE);
        rejects("SELECT * FROM \"q\" LIMIT 1.5", "LIMIT must be a whole number");
        rejects("SELECT * FROM \"q\" LIMIT ALL", "LIMIT must be a whole number");
    }

    // ---- predicates --------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"=,EQ", "<>,NE", "!=,NE", "<,LT", "<=,LTE", ">,GT", ">=,GTE"})
    void everyComparisonOperatorMapsToItsEnum(String sql, Operator expected) {
        Predicate p = where("priority " + sql + " 4");

        assertThat(p)
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.PRIORITY), expected, new Literal.Num(4, true)));
    }

    @Test
    void aLiteralOnTheLeftIsMirrored() {
        assertThat(where("4 < priority"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.PRIORITY), Operator.GT, new Literal.Num(4, true)));
    }

    @Test
    void notAndParenthesesAndOrNest() {
        Predicate p = where("NOT (priority = 1 OR (size > 2 AND durable = true))");

        assertThat(p).isInstanceOf(Predicate.Not.class);
        Predicate inner = ((Predicate.Not) p).inner();
        assertThat(inner).isInstanceOf(Predicate.Or.class);
        assertThat(((Predicate.Or) inner).parts().get(1)).isInstanceOf(Predicate.And.class);
    }

    @Test
    void isNotNullAndNotInAndNotBetweenAndNotLikeAreNegated() {
        assertThat(where("props.a IS NOT NULL")).isEqualTo(new Predicate.IsNull(new Term.PropertyTerm("a"), true));
        assertThat(where("props.a NOT IN ('x')"))
                .isEqualTo(new Predicate.In(new Term.PropertyTerm("a"), List.of(new Literal.Str("x")), true));
        assertThat(where("priority NOT BETWEEN 1 AND 3"))
                .isEqualTo(new Predicate.Between(
                        new Term.ColumnTerm(Column.PRIORITY),
                        new Literal.Num(1, true),
                        new Literal.Num(3, true),
                        true));
        assertThat(where("props.a NOT LIKE 'x%' ESCAPE '!'"))
                .isEqualTo(new Predicate.Like(new Term.PropertyTerm("a"), "x%", '!', true, false));
    }

    @Test
    void likeRefusesANonStringPatternAndAMultiCharacterEscape() {
        rejectsWhere("props.a LIKE 5", "LIKE takes a quoted pattern");
        rejectsWhere("props.a LIKE 'x' ESCAPE 'ab'", "ESCAPE takes a single character");
    }

    @Test
    void inRefusesASubqueryAndAnEmptyOrNonLiteralList() {
        rejectsWhere("props.a IN (SELECT 1)", "IN takes a parenthesised list");
        rejectsWhere("props.a IN (priority)", "Expected a literal value");
    }

    @Test
    void matchAgainstTakesOneBodyColumnAndAQuotedString() {
        assertThat(where("MATCH (body) AGAINST ('order 4471')")).isEqualTo(new Predicate.Match("order 4471"));
        rejectsWhere("MATCH (body, subscription) AGAINST ('x')", "MATCH searches one column");
        rejectsWhere("MATCH (subscription) AGAINST ('x')", "MATCH searches the body column");
        rejectsWhere("MATCH (props) AGAINST ('x')", "There is no column 'props'");
        rejectsWhere("MATCH (body) AGAINST (5)", "quoted string");
        rejectsWhere("MATCH (body) AGAINST ('x' IN BOOLEAN MODE)", "no search modifiers");
    }

    @Test
    void anExpressionThatIsNotAPredicateIsRefused() {
        rejectsWhere("priority", "not something the dialect can evaluate");
        rejectsWhere("priority + 1", "not something the dialect can evaluate");
    }

    @Test
    void aParenthesisedGroupOfSeveralExpressionsIsRefused() {
        rejectsWhere("(priority = 1, size = 2)", "one condition");
    }

    // ---- terms -------------------------------------------------------------

    @Test
    void columnNamesAreCaseInsensitiveAndQuotedNamesAreUnquoted() {
        assertThat(where("\"priority\" = 1"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.PRIORITY), Operator.EQ, new Literal.Num(1, true)));
        assertThat(where("PRIORITY = 1")).isNotNull();
    }

    @Test
    void anEmptyPropertyNameIsRefused() {
        rejectsWhere("props.\"\" = 'x'", "needs a name");
    }

    @Test
    void jsonPathsTakeTextOutAndDottedKeys() {
        assertThat(where("body->>'a.b' = 'x'"))
                .isEqualTo(new Predicate.Compare(new Term.JsonTerm("a.b"), Operator.EQ, new Literal.Str("x")));
        rejectsWhere("body->'a' = 'x'", "->>");
    }

    @Test
    void caseFoldFunctionsAreLowerAndUpperOnly() {
        assertThat(where("lower(props.a) = 'x'"))
                .isEqualTo(new Predicate.Compare(
                        new Term.CaseFold(new Term.PropertyTerm("a"), false), Operator.EQ, new Literal.Str("x")));
        assertThat(where("UPPER(subscription) = 'X'")).isInstanceOf(Predicate.Compare.class);
        rejectsWhere("now() = 'x'", "is not a value here");
        rejectsWhere("lower(props.a, props.b) = 'x'", "exactly one column");
        rejectsWhere("lower() = 'x'", "exactly one column");
    }

    // ---- literals ----------------------------------------------------------

    @Test
    void literalsCoverNumbersBooleansSignsAndDoubledQuotes() {
        assertThat(where("priority = 1.5"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.PRIORITY), Operator.EQ, new Literal.Num(1.5, false)));
        assertThat(where("priority = -3"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.PRIORITY), Operator.EQ, new Literal.Num(-3, true)));
        assertThat(where("priority = +3"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.PRIORITY), Operator.EQ, new Literal.Num(3, true)));
        assertThat(where("durable = false"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.DURABLE), Operator.EQ, new Literal.Bool(false)));
        assertThat(where("props.a = 'it''s'"))
                .isEqualTo(new Predicate.Compare(new Term.PropertyTerm("a"), Operator.EQ, new Literal.Str("it's")));
        assertThat(where("priority = (4)")).isNotNull();
    }

    @Test
    void aSignOnANonNumberIsRefused() {
        rejectsWhere("priority = -'x'", "A sign can only be applied to a number");
    }

    @Test
    void aLiteralPositionThatHoldsAColumnIsRefused() {
        rejectsWhere("priority = size", "Expected a literal value");
    }

    @Test
    void nowIsARelativeTimeOfZeroAndTakesNoArguments() {
        assertThat(where("timestamp < now()"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.TIMESTAMP), Operator.LT, new Literal.RelativeTime(Duration.ZERO)));
        rejectsWhere("timestamp < now(1)", "now() takes no arguments");
        rejectsWhere("timestamp < lower('x')", "no function 'lower' in a value position");
    }

    @Test
    void aRelativeTimeNeedsNowMinusAnInterval() {
        rejectsWhere("timestamp > size - interval '1 hour'", "now() - interval");
        rejectsWhere("timestamp > now() - 5", "subtracts an interval");
    }

    @ParameterizedTest
    @CsvSource({"1 second,PT1S", "30 seconds,PT30S", "5 minute,PT5M", "2 hours,PT2H", "3 days,PT72H", "2 weeks,PT336H"})
    void intervalUnitsResolveToDurations(String interval, Duration expected) {
        assertThat(where("timestamp > now() - interval '" + interval + "'"))
                .isEqualTo(new Predicate.Compare(
                        new Term.ColumnTerm(Column.TIMESTAMP), Operator.GT, new Literal.RelativeTime(expected)));
    }

    @Test
    void anIntervalWithABadShapeAmountOrSignIsRefused() {
        rejectsWhere("timestamp > now() - interval '1 2 hours'", "written as '<n> <unit>'");
        rejectsWhere("timestamp > now() - interval 'x hours'", "whole number");
        rejectsWhere("timestamp > now() - interval '-1 hours'", "cannot be negative");
    }
}
