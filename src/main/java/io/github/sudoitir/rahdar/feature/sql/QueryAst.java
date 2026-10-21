package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import java.time.Duration;
import java.util.List;

/**
 * The validated internal form of a query (ADR-0058). Everything downstream — the
 * planner, the selector renderer, the residual predicate, the index compiler —
 * consumes this and never a JSqlParser type, so the parser stays swappable and
 * nothing unvalidated can leak past {@link SqlQueryParser}.
 *
 * <p>Every {@link Term} here is a catalogue column, a named application property or
 * a JSON path into the body. There is no free-text term: by the time a query is a
 * {@code QueryAst} it has been checked against the whitelist.
 */
public record QueryAst(
        Source source,
        String subscriptionPattern,
        List<Column> projection,
        Predicate where,
        List<Order> orderBy,
        Integer limit,
        String text) {

    /** Which backend answers. {@link #DEFAULT} lets the planner choose (ADR-0058 D3). */
    public enum Source {
        DEFAULT,
        BROKER,
        INDEX
    }

    public enum Direction {
        ASC,
        DESC
    }

    public record Order(Term term, Direction direction) {}

    // ---- terms ---------------------------------------------------------

    /** Something a predicate compares. */
    public sealed interface Term {

        /** A catalogue column. */
        record ColumnTerm(Column column) implements Term {}

        /** An application property, addressed as {@code props.<name>}. */
        record PropertyTerm(String name) implements Term {}

        /** A JSON path into the body, from {@code body->>'path'}. Always a scan. */
        record JsonTerm(String path) implements Term {}

        /** {@code lower(x)} / {@code upper(x)}. Always a scan — no selector equivalent. */
        record CaseFold(Term inner, boolean upper) implements Term {}

        /**
         * {@code match_rank} — how well a row matched the query's {@code MATCH()},
         * from {@code ts_rank_cd}. Orderable and nothing else: it is a property of the
         * comparison, not of the message, so it has no value to compare against and no
         * meaning without a {@code MATCH()} in the same query.
         */
        record MatchRank() implements Term {}
    }

    // ---- literals ------------------------------------------------------

    public sealed interface Literal {
        record Str(String value) implements Literal {}

        record Num(double value, boolean integral) implements Literal {}

        record Bool(boolean value) implements Literal {}

        /**
         * {@code now() - interval '2 hours'}. Resolved to an instant per target node
         * at plan time, through the measured clock offset (ADR-0053), so a skewed
         * broker still matches its own recent messages.
         */
        record RelativeTime(Duration before) implements Literal {}
    }

    // ---- predicates ----------------------------------------------------

    public enum Operator {
        EQ("="),
        NE("<>"),
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">=");

        private final String sql;

        Operator(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        /** The operator that means the same thing with the operands the other way round. */
        public Operator mirrored() {
            return switch (this) {
                case EQ -> EQ;
                case NE -> NE;
                case LT -> GT;
                case LTE -> GTE;
                case GT -> LT;
                case GTE -> LTE;
            };
        }
    }

    public sealed interface Predicate {
        record And(List<Predicate> parts) implements Predicate {}

        record Or(List<Predicate> parts) implements Predicate {}

        record Not(Predicate inner) implements Predicate {}

        record Compare(Term term, Operator op, Literal value) implements Predicate {}

        record In(Term term, List<Literal> values, boolean negated) implements Predicate {}

        record IsNull(Term term, boolean negated) implements Predicate {}

        /**
         * {@code LIKE} with the SQL wildcards. {@code caseInsensitive} marks
         * {@code ILIKE}, which has no selector equivalent and is therefore always a
         * scan — never approximated (ADR-0058 D4).
         */
        record Like(Term term, String pattern, Character escape, boolean negated, boolean caseInsensitive)
                implements Predicate {}

        record Between(Term term, Literal low, Literal high, boolean negated) implements Predicate {}

        /**
         * {@code MATCH(body, 'terms')} — full-text search over the stored body
         * (ADR-0063). Index-only, and deliberately not expressible against a live
         * broker: there is no selector for it and no way to approximate one that would
         * not silently mean something different.
         *
         * <p>{@code terms} is a {@code websearch_to_tsquery} string, so quoted phrases,
         * {@code -exclusion} and {@code or} work the way an operator expects from a
         * search box, and a syntax error in it is a no-match rather than an error.
         */
        record Match(String terms) implements Predicate {}
    }
}
