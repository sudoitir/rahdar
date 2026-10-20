package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The whitelist is the security boundary (ADR-0058 D2), so the rejection cases carry
 * as much weight here as the acceptance ones: anything that parses as SQL but is not
 * in the dialect must be refused, and refused by name.
 */
class SqlQueryParserTest {

    private final SqlQueryParser parser = new SqlQueryParser();

    @Nested
    class Accepts {

        @Test
        void aQuotedDottedQueueNameSurvivesTheParserSplittingIt() {
            // JSqlParser splits "ORDER.IN" into schema "ORDER" and table "IN" even
            // inside quotes; the whole name has to come back out.
            QueryAst ast = parser.parse("SELECT * FROM \"ORDER.IN\"");

            assertThat(ast.subscriptionPattern()).isEqualTo("ORDER.IN");
            assertThat(ast.source()).isEqualTo(Source.DEFAULT);
        }

        @Test
        void aQueueNameWithADollarIsAQueueName() {
            assertThat(parser.parse("SELECT * FROM \"DLQ.$sys\"").subscriptionPattern())
                    .isEqualTo("DLQ.$sys");
        }

        @Test
        void theSourceQualifierChoosesTheBackend() {
            assertThat(parser.parse("SELECT * FROM broker.\"ORDER.IN\"").source())
                    .isEqualTo(Source.BROKER);
            assertThat(parser.parse("SELECT * FROM index.\"ORDER.IN\"").source())
                    .isEqualTo(Source.INDEX);
            assertThat(parser.parse("SELECT * FROM index.\"ORDER.IN\"").subscriptionPattern())
                    .isEqualTo("ORDER.IN");
        }

        @Test
        void aQueueActuallyNamedBrokerIsStillReachable() {
            QueryAst ast = parser.parse("SELECT * FROM broker.\"broker.things\"");

            assertThat(ast.source()).isEqualTo(Source.BROKER);
            assertThat(ast.subscriptionPattern()).isEqualTo("broker.things");
        }

        @Test
        void anArtemisWildcardIsJustPartOfTheName() {
            assertThat(parser.parse("SELECT * FROM \"ORDER.*\"").subscriptionPattern())
                    .isEqualTo("ORDER.*");
            assertThat(parser.parse("SELECT * FROM \"ORDER.#\"").subscriptionPattern())
                    .isEqualTo("ORDER.#");
        }

        @Test
        void aHeaderComparisonBecomesATermOperatorLiteral() {
            QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE priority > 4");

            Predicate.Compare compare = (Predicate.Compare) ast.where();
            assertThat(compare.term()).isEqualTo(new Term.ColumnTerm(Column.PRIORITY));
            assertThat(compare.op()).isEqualTo(Operator.GT);
            assertThat(compare.value()).isEqualTo(new Literal.Num(4, true));
        }

        @Test
        void parenthesesAroundATermOrALiteralAreUnwrapped() {
            QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE (priority) > (4)");

            Predicate.Compare compare = (Predicate.Compare) ast.where();
            assertThat(compare.term()).isEqualTo(new Term.ColumnTerm(Column.PRIORITY));
            assertThat(compare.value()).isEqualTo(new Literal.Num(4, true));
        }

        @Test
        void aLiteralOnTheLeftIsNormalisedNotRejected() {
            QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE 4 < priority");

            Predicate.Compare compare = (Predicate.Compare) ast.where();
            assertThat(compare.term()).isEqualTo(new Term.ColumnTerm(Column.PRIORITY));
            assertThat(compare.op()).isEqualTo(Operator.GT);
        }

        @Test
        void anApplicationPropertyIsAddressedByName() {
            QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE props.tenant = 'acme'");

            assertThat(((Predicate.Compare) ast.where()).term()).isEqualTo(new Term.PropertyTerm("tenant"));
        }

        @Test
        void aJsonPathIsTakenFromTheBody() {
            QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE body->>'orderId' = '4471'");

            assertThat(((Predicate.Compare) ast.where()).term()).isEqualTo(new Term.JsonTerm("orderId"));
        }

        @Test
        void aRelativeTimeBecomesADuration() {
            QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE timestamp > now() - interval '2 hours'");

            Predicate.Compare compare = (Predicate.Compare) ast.where();
            assertThat(compare.value()).isEqualTo(new Literal.RelativeTime(Duration.ofHours(2)));
        }

        @Test
        void ilikeIsRecordedAsCaseInsensitive() {
            Predicate.Like like = (Predicate.Like)
                    parser.parse("SELECT * FROM \"q\" WHERE body ILIKE '%x%'").where();

            assertThat(like.caseInsensitive()).isTrue();
            assertThat(((Predicate.Like) parser.parse("SELECT * FROM \"q\" WHERE body LIKE '%x%'")
                                    .where())
                            .caseInsensitive())
                    .isFalse();
        }

        @Test
        void inAndIsNullAndBetweenAllParse() {
            assertThat(parser.parse("SELECT * FROM \"q\" WHERE props.t IN ('a','b')")
                            .where())
                    .isInstanceOf(Predicate.In.class);
            assertThat(parser.parse("SELECT * FROM \"q\" WHERE correlationId IS NOT NULL")
                            .where())
                    .isInstanceOf(Predicate.IsNull.class);
            assertThat(parser.parse("SELECT * FROM \"q\" WHERE priority BETWEEN 1 AND 4")
                            .where())
                    .isInstanceOf(Predicate.Between.class);
        }

        @Test
        void orderByAndLimitAreCarried() {
            QueryAst ast = parser.parse("SELECT * FROM \"q\" ORDER BY timestamp DESC LIMIT 500");

            assertThat(ast.limit()).isEqualTo(500);
            assertThat(ast.orderBy()).hasSize(1);
            assertThat(ast.orderBy().getFirst().direction()).isEqualTo(QueryAst.Direction.DESC);
        }
    }

    @Nested
    class Rejects {

        @Test
        void aStatementThatIsNotASelect() {
            assertThatThrownBy(() -> parser.parse("DELETE FROM \"q\""))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("SELECT only");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "UPDATE \"q\" SET priority = 1",
                    "INSERT INTO \"q\" VALUES (1)",
                    "DROP TABLE \"q\"",
                    "TRUNCATE TABLE \"q\""
                })
        void everyMutatingStatementShape(String sql) {
            assertThatThrownBy(() -> parser.parse(sql)).isInstanceOf(SqlSyntaxException.class);
        }

        @Test
        void aJoinNamesTheConstruct() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"a\" JOIN \"b\" ON \"a\".x = \"b\".x"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("no joins");
        }

        @Test
        void aSubquery() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"q\" WHERE priority IN (SELECT 1)"))
                    .isInstanceOf(SqlSyntaxException.class);
        }

        @Test
        void aSetOperation() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"a\" UNION SELECT * FROM \"b\""))
                    .isInstanceOf(SqlSyntaxException.class);
        }

        @Test
        void aCommonTableExpression() {
            assertThatThrownBy(() -> parser.parse("WITH x AS (SELECT 1) SELECT * FROM \"q\""))
                    .isInstanceOf(SqlSyntaxException.class);
        }

        @Test
        void groupByAndHavingAndDistinct() {
            assertThatThrownBy(() -> parser.parse("SELECT priority FROM \"q\" GROUP BY priority"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("GROUP BY");
            assertThatThrownBy(() -> parser.parse("SELECT DISTINCT priority FROM \"q\""))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("DISTINCT");
        }

        @Test
        void anUnknownFunction() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"q\" WHERE pg_sleep(10) = 1"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("no function");
        }

        @Test
        void anUnknownColumnSuggestsTheNearMatch() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"q\" WHERE prioritty > 4"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("Did you mean 'priority'");

            SqlSyntaxException thrown = catchSyntax("SELECT * FROM \"q\" WHERE prioritty > 4");
            assertThat(thrown.suggestion()).isEqualTo("priority");
            assertThat(thrown.offending()).isEqualTo("prioritty");
        }

        @Test
        void anUnknownColumnWithNoNearMatchDoesNotGuess() {
            SqlSyntaxException thrown = catchSyntax("SELECT * FROM \"q\" WHERE wibble = 1");

            assertThat(thrown.suggestion()).isNull();
            assertThat(thrown.getMessage()).contains("props.<name>");
        }

        @Test
        void aQualifiedColumnThatIsNotAProperty() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"q\" WHERE other.thing = 1"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("props.<name>");
        }

        @Test
        void aJsonPathOffSomethingOtherThanTheBody() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"q\" WHERE correlationId->>'a' = 'b'"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("body");
        }

        @Test
        void anIntervalWithAnUnknownUnit() {
            assertThatThrownBy(
                            () -> parser.parse("SELECT * FROM \"q\" WHERE timestamp > now() - interval '2 fortnights'"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("second, minute, hour, day or week");
        }

        @Test
        void anEmptyQuery() {
            assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(SqlSyntaxException.class);
        }

        @Test
        void aNonPositiveLimit() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"q\" LIMIT 0"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        void offsetIsNotInTheDialect() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM \"q\" LIMIT 10 OFFSET 5"))
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("OFFSET");
        }

        private SqlSyntaxException catchSyntax(String sql) {
            try {
                parser.parse(sql);
            } catch (SqlSyntaxException e) {
                return e;
            }
            throw new AssertionError("expected a rejection for: " + sql);
        }
    }
}
