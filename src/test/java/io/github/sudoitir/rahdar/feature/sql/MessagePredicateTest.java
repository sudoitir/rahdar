package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.MessagePredicate.EvalContext;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The residual predicate is evaluated in memory against what the broker returned, so
 * each term kind, each literal kind and the unknown-is-not-selected rule are pinned here.
 */
class MessagePredicateTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final EvalContext CONTEXT = new EvalContext("ORDERS", "orders.addr", "node-a", NOW);

    private final MessagePredicate predicate = new MessagePredicate();

    private static BrowsedMessage message(String body) {
        return new BrowsedMessage(
                42,
                3,
                true,
                4,
                NOW.toEpochMilli(),
                7,
                100,
                "grp",
                "corr",
                "reply",
                "user",
                body,
                BodyEncoding.TEXT,
                Compression.NONE,
                "jms-type",
                false,
                null,
                Map.of("tenant", "Acme"),
                Map.of("intProp", 5L),
                Map.of("longProp", 9L),
                Map.of("doubleProp", 1.5),
                Map.of("flag", true));
    }

    private static Term.ColumnTerm col(Column column) {
        return new Term.ColumnTerm(column);
    }

    private static Predicate.Compare eq(Term term, Literal literal) {
        return new Predicate.Compare(term, Operator.EQ, literal);
    }

    private boolean matches(Predicate p, BrowsedMessage m) {
        return predicate.matches(p, m, CONTEXT);
    }

    @Test
    void aNullPredicateSelectsEverything() {
        assertThat(predicate.compile(null, CONTEXT).test(message("x"))).isTrue();
    }

    @Test
    void aCompiledPredicateAppliesTheTree() {
        var compiled = predicate.compile(eq(new Term.PropertyTerm("tenant"), new Literal.Str("Acme")), CONTEXT);

        assertThat(compiled.test(message("x"))).isTrue();
        assertThat(compiled.test(new BrowsedMessage(
                        1, 0, false, 0, 0, 0, 0, null, null, null, null, null, null, null, null, false, null, Map.of(),
                        Map.of(), Map.of(), Map.of(), Map.of())))
                .isFalse();
    }

    @Test
    void andOrNotCombineTheirParts() {
        var yes = eq(col(Column.QUEUE), new Literal.Str("ORDERS"));
        var no = eq(col(Column.QUEUE), new Literal.Str("OTHER"));

        assertThat(matches(new Predicate.And(List.of(yes, yes)), message("x"))).isTrue();
        assertThat(matches(new Predicate.And(List.of(yes, no)), message("x"))).isFalse();
        assertThat(matches(new Predicate.Or(List.of(no, yes)), message("x"))).isTrue();
        assertThat(matches(new Predicate.Or(List.of(no, no)), message("x"))).isFalse();
        assertThat(matches(new Predicate.Not(no), message("x"))).isTrue();
    }

    @Test
    void matchCannotBeEvaluatedAgainstABrokerMessage() {
        var match = new Predicate.Match("foo");
        var msg = message("x");

        assertThatThrownBy(() -> matches(match, msg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATCH()");
    }

    @ParameterizedTest
    @CsvSource({
        "EQ,5,true",
        "EQ,4,false",
        "NE,4,true",
        "NE,5,false",
        "LT,5,false",
        "LT,6,true",
        "LTE,5,true",
        "LTE,4,false",
        "GT,4,true",
        "GT,5,false",
        "GTE,5,true",
        "GTE,6,false"
    })
    void everyOperatorComparesNumbers(Operator op, double literal, boolean expected) {
        var compare = new Predicate.Compare(new Term.PropertyTerm("intProp"), op, new Literal.Num(literal, true));

        assertThat(matches(compare, message("x"))).isEqualTo(expected);
    }

    @Test
    void anAbsentValueOrIncomparableLiteralIsNotSelected() {
        // absent property
        assertThat(matches(eq(new Term.PropertyTerm("missing"), new Literal.Str("x")), message("x")))
                .isFalse();
        // string against a number literal that is not numeric
        assertThat(matches(eq(new Term.PropertyTerm("tenant"), new Literal.Num(1, true)), message("x")))
                .isFalse();
        // boolean against a string literal
        assertThat(matches(eq(new Term.PropertyTerm("flag"), new Literal.Str("true")), message("x")))
                .isFalse();
        // number against a boolean literal
        assertThat(matches(eq(new Term.PropertyTerm("intProp"), new Literal.Bool(true)), message("x")))
                .isFalse();
    }

    @Test
    void booleanLiteralsCompareToBooleanValues() {
        assertThat(matches(eq(col(Column.DURABLE), new Literal.Bool(true)), message("x")))
                .isTrue();
        assertThat(matches(eq(new Term.PropertyTerm("flag"), new Literal.Bool(false)), message("x")))
                .isFalse();
    }

    @Test
    void numericStringsCompareAsNumbers() {
        var numeric = new Term.JsonTerm("n");

        assertThat(matches(eq(numeric, new Literal.Num(12, true)), message("{\"n\":\"12\"}")))
                .isTrue();
    }

    @Test
    void relativeTimeResolvesAgainstTheBrokerNow() {
        var oneHour = new Literal.RelativeTime(Duration.ofHours(1));
        var recent = new Predicate.Compare(col(Column.TIMESTAMP), Operator.GT, oneHour);

        assertThat(matches(recent, message("x"))).isTrue();
        // a non-numeric value has no ordering against a time
        assertThat(matches(new Predicate.Compare(new Term.PropertyTerm("tenant"), Operator.GT, oneHour), message("x")))
                .isFalse();
    }

    @Test
    void inSelectsMembersAndNegationInvertsButAbsentStaysUnselected() {
        var values = List.<Literal>of(new Literal.Str("Acme"), new Literal.Str("Globex"));
        var tenant = new Term.PropertyTerm("tenant");

        assertThat(matches(new Predicate.In(tenant, values, false), message("x")))
                .isTrue();
        assertThat(matches(new Predicate.In(tenant, values, true), message("x")))
                .isFalse();
        assertThat(matches(new Predicate.In(tenant, List.of(new Literal.Str("Other")), true), message("x")))
                .isTrue();
        assertThat(matches(new Predicate.In(new Term.PropertyTerm("missing"), values, true), message("x")))
                .isFalse();
    }

    @Test
    void isNullTestsAbsence() {
        var missing = new Term.PropertyTerm("missing");
        var tenant = new Term.PropertyTerm("tenant");

        assertThat(matches(new Predicate.IsNull(missing, false), message("x"))).isTrue();
        assertThat(matches(new Predicate.IsNull(missing, true), message("x"))).isFalse();
        assertThat(matches(new Predicate.IsNull(tenant, false), message("x"))).isFalse();
        assertThat(matches(new Predicate.IsNull(tenant, true), message("x"))).isTrue();
    }

    @Test
    void betweenIsInclusiveAndNegatable() {
        var term = new Term.PropertyTerm("intProp");
        var low = new Literal.Num(5, true);
        var high = new Literal.Num(10, true);

        assertThat(matches(new Predicate.Between(term, low, high, false), message("x")))
                .isTrue();
        assertThat(matches(new Predicate.Between(term, new Literal.Num(6, true), high, false), message("x")))
                .isFalse();
        assertThat(matches(new Predicate.Between(term, low, high, true), message("x")))
                .isFalse();
        // absent value
        assertThat(matches(new Predicate.Between(new Term.PropertyTerm("missing"), low, high, false), message("x")))
                .isFalse();
        // literals that cannot be ordered against the value
        assertThat(matches(
                        new Predicate.Between(new Term.PropertyTerm("flag"), new Literal.Str("a"), high, false),
                        message("x")))
                .isFalse();
    }

    @Test
    void likeSupportsWildcardsEscapesCaseAndNegation() {
        var tenant = new Term.PropertyTerm("tenant");

        assertThat(matches(new Predicate.Like(tenant, "Ac%", null, false, false), message("x")))
                .isTrue();
        assertThat(matches(new Predicate.Like(tenant, "A_me", null, false, false), message("x")))
                .isTrue();
        assertThat(matches(new Predicate.Like(tenant, "ac%", null, false, false), message("x")))
                .isFalse();
        assertThat(matches(new Predicate.Like(tenant, "ac%", null, false, true), message("x")))
                .isTrue();
        assertThat(matches(new Predicate.Like(tenant, "Ac%", null, true, false), message("x")))
                .isFalse();
        assertThat(matches(new Predicate.Like(new Term.PropertyTerm("missing"), "%", null, false, false), message("x")))
                .isFalse();
    }

    @Test
    void likeToRegexQuotesMetacharactersAndHonoursEscape() {
        assertThat(like("a.b", null)).accepts("a.b").rejects("axb");
        assertThat(like("50!%", '!')).accepts("50%").rejects("50x");
        assertThat(like("a!_", '!')).accepts("a_");
        // a trailing escape is a literal, not dropped
        assertThat(like("a!", '!')).accepts("a!");
    }

    private static java.util.function.Predicate<String> like(String pattern, Character escape) {
        return Pattern.compile(MessagePredicate.likeToRegex(pattern, escape)).asMatchPredicate();
    }

    @Test
    void columnTermsReadTheMessageAndContext() {
        BrowsedMessage m = message("the-body");

        assertThat(predicate.resolve(col(Column.MESSAGE_ID), m, CONTEXT)).isEqualTo("42");
        assertThat(predicate.resolve(col(Column.QUEUE), m, CONTEXT)).isEqualTo("ORDERS");
        assertThat(predicate.resolve(col(Column.ADDRESS), m, CONTEXT)).isEqualTo("orders.addr");
        assertThat(predicate.resolve(col(Column.NODE), m, CONTEXT)).isEqualTo("node-a");
        assertThat(predicate.resolve(col(Column.PRIORITY), m, CONTEXT)).isEqualTo(4L);
        assertThat(predicate.resolve(col(Column.DURABLE), m, CONTEXT)).isEqualTo(true);
        assertThat(predicate.resolve(col(Column.TIMESTAMP), m, CONTEXT)).isEqualTo(NOW.toEpochMilli());
        assertThat(predicate.resolve(col(Column.EXPIRATION), m, CONTEXT)).isEqualTo(7L);
        assertThat(predicate.resolve(col(Column.SIZE), m, CONTEXT)).isEqualTo(100L);
        assertThat(predicate.resolve(col(Column.JMS_TYPE), m, CONTEXT)).isEqualTo("jms-type");
        assertThat(predicate.resolve(col(Column.CORRELATION_ID), m, CONTEXT)).isEqualTo("corr");
        assertThat(predicate.resolve(col(Column.GROUP_ID), m, CONTEXT)).isEqualTo("grp");
        assertThat(predicate.resolve(col(Column.USER_ID), m, CONTEXT)).isEqualTo("user");
        assertThat(predicate.resolve(col(Column.MESSAGE_TYPE), m, CONTEXT)).isEqualTo(3L);
        assertThat(predicate.resolve(col(Column.REPLY_TO), m, CONTEXT)).isEqualTo("reply");
        assertThat(predicate.resolve(col(Column.BODY), m, CONTEXT)).isEqualTo("the-body");
    }

    @Test
    void observationColumnsHaveNoValueAgainstALiveBroker() {
        for (Column column : List.of(
                Column.OBSERVED_AT,
                Column.LAST_SEEN_AT,
                Column.ORIGIN,
                Column.ORIG_ADDRESS,
                Column.SOURCE_MESSAGE_ID)) {
            assertThat(predicate.resolve(col(column), message("x"), CONTEXT)).isNull();
        }
    }

    @Test
    void propertiesResolveFromEachTypedMap() {
        BrowsedMessage m = message("x");

        assertThat(predicate.resolve(new Term.PropertyTerm("tenant"), m, CONTEXT))
                .isEqualTo("Acme");
        assertThat(predicate.resolve(new Term.PropertyTerm("longProp"), m, CONTEXT))
                .isEqualTo(9L);
        assertThat(predicate.resolve(new Term.PropertyTerm("intProp"), m, CONTEXT))
                .isEqualTo(5L);
        assertThat(predicate.resolve(new Term.PropertyTerm("doubleProp"), m, CONTEXT))
                .isEqualTo(1.5);
        assertThat(predicate.resolve(new Term.PropertyTerm("flag"), m, CONTEXT)).isEqualTo(true);
        assertThat(predicate.resolve(new Term.PropertyTerm("nope"), m, CONTEXT)).isNull();
    }

    @Test
    void jsonPathsWalkDottedKeysAndReturnTextOut() {
        String body = "{\"a\":{\"b\":\"deep\",\"n\":3,\"arr\":[1,2],\"nil\":null}}";

        assertThat(predicate.resolve(new Term.JsonTerm("a.b"), message(body), CONTEXT))
                .isEqualTo("deep");
        assertThat(predicate.resolve(new Term.JsonTerm("a.n"), message(body), CONTEXT))
                .isEqualTo("3");
        assertThat(predicate.resolve(new Term.JsonTerm("a.arr"), message(body), CONTEXT))
                .isEqualTo("[1,2]");
        assertThat(predicate.resolve(new Term.JsonTerm("a.arr.1"), message(body), CONTEXT))
                .isEqualTo("2");
        assertThat(predicate.resolve(new Term.JsonTerm("a.arr.9"), message(body), CONTEXT))
                .isNull();
        assertThat(predicate.resolve(new Term.JsonTerm("a.nil"), message(body), CONTEXT))
                .isNull();
        assertThat(predicate.resolve(new Term.JsonTerm("a.missing"), message(body), CONTEXT))
                .isNull();
        assertThat(predicate.resolve(new Term.JsonTerm("a.b.c.d"), message(body), CONTEXT))
                .isNull();
    }

    @Test
    void jsonPathsOverAnUnusableBodyAreNull() {
        assertThat(predicate.resolve(new Term.JsonTerm("a"), message(null), CONTEXT))
                .isNull();
        assertThat(predicate.resolve(new Term.JsonTerm("a"), message("  "), CONTEXT))
                .isNull();
        assertThat(predicate.resolve(new Term.JsonTerm("a"), message("{not json"), CONTEXT))
                .isNull();
    }

    @Test
    void caseFoldFoldsTheInnerValueAndPropagatesAbsence() {
        var tenant = new Term.PropertyTerm("tenant");

        assertThat(predicate.resolve(new Term.CaseFold(tenant, true), message("x"), CONTEXT))
                .isEqualTo("ACME");
        assertThat(predicate.resolve(new Term.CaseFold(tenant, false), message("x"), CONTEXT))
                .isEqualTo("acme");
        assertThat(predicate.resolve(new Term.CaseFold(new Term.PropertyTerm("nope"), true), message("x"), CONTEXT))
                .isNull();
    }

    @Test
    void matchRankHasNoValueOnABrokerMessage() {
        assertThat(predicate.resolve(new Term.MatchRank(), message("x"), CONTEXT))
                .isNull();
    }
}
