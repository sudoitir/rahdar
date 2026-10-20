package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import java.util.regex.Pattern;

/**
 * Evaluates the target-level conjuncts — {@code queue}, {@code address},
 * {@code node} — against a resolved target, so a node the query excludes is never
 * asked at all. This is the cheapest of the three evaluation classes: it runs during
 * planning, before any broker is contacted.
 */
final class TargetPredicateEvaluator {

    private TargetPredicateEvaluator() {}

    static boolean matches(Predicate predicate, Target target) {
        return switch (predicate) {
            case Predicate.And(var parts) -> parts.stream().allMatch(p -> matches(p, target));
            case Predicate.Or(var parts) -> parts.stream().anyMatch(p -> matches(p, target));
            case Predicate.Not(var inner) -> !matches(inner, target);
            case Predicate.Compare compare -> compare(compare, target);
            case Predicate.In in -> in(in, target);
            case Predicate.IsNull(var term, var negated) -> (value(term, target) == null) != negated;
            case Predicate.Like like -> like(like, target);
            case Predicate.Between _ -> true;
            // A target is a queue on a node; nothing about it can be full-text
            // searched, so this predicate excludes no target.
            case Predicate.Match _ -> true;
        };
    }

    private static boolean compare(Predicate.Compare compare, Target target) {
        String left = value(compare.term(), target);
        if (left == null || !(compare.value() instanceof Literal.Str(var right))) {
            return true;
        }
        int order = left.compareTo(right);
        return switch (compare.op()) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case LT -> order < 0;
            case LTE -> order <= 0;
            case GT -> order > 0;
            case GTE -> order >= 0;
        };
    }

    private static boolean in(Predicate.In in, Target target) {
        String left = value(in.term(), target);
        if (left == null) {
            return true;
        }
        boolean found = in.values().stream().anyMatch(v -> v instanceof Literal.Str(var text) && text.equals(left));
        return found != in.negated();
    }

    private static boolean like(Predicate.Like like, Target target) {
        String left = value(like.term(), target);
        if (left == null) {
            return true;
        }
        int flags = like.caseInsensitive() ? Pattern.CASE_INSENSITIVE : 0;
        boolean hit = Pattern.compile(MessagePredicate.likeToRegex(like.pattern(), like.escape()), flags)
                .matcher(left)
                .matches();
        return hit != like.negated();
    }

    private static String value(Term term, Target target) {
        if (!(term instanceof Term.ColumnTerm(var c))) {
            return null;
        }
        if (c == Column.SUBSCRIPTION) {
            return target.subscription();
        }
        if (c == Column.DESTINATION) {
            return target.destination();
        }
        if (c == Column.NODE) {
            return target.nodeName();
        }
        return null;
    }
}
