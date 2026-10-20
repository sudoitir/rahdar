package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Direction;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Operator;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Order;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.IntervalExpression;
import net.sf.jsqlparser.expression.JsonExpression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

/**
 * Parses the SQL Console dialect and validates it (ADR-0058 D1/D2).
 *
 * <p>The validation is a <strong>tree walk that rejects by default</strong>. Every
 * node type not handled below is refused, so a construct nobody thought about is a
 * rejection rather than a surprise. A regular expression over the query text would
 * be the usual way to get this wrong — casing, comments and whitespace all defeat
 * it — so the text is never inspected, only the tree.
 *
 * <p>The output is a {@link QueryAst}: catalogue columns, named properties and
 * literals. No fragment of the operator's text survives into it, which is what
 * makes {@link SelectorRenderer} and the index compiler safe by construction.
 */
@Component
public class SqlQueryParser {

    /** {@code FROM broker."X"} / {@code FROM index."X"} — the source qualifier (D3). */
    private static final String SOURCE_BROKER = "broker";

    private static final String SOURCE_INDEX = "index";

    public QueryAst parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlSyntaxException("The query is empty.");
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SqlSyntaxException("Not valid SQL: " + firstLine(e.getMessage()));
        }
        if (!(statement instanceof PlainSelect select)) {
            throw new SqlSyntaxException(
                    "The console runs SELECT only — it cannot change messages. Act on a result row instead.",
                    statement.getClass().getSimpleName().toUpperCase(Locale.ROOT));
        }
        rejectUnsupportedSelectClauses(select);

        FromTarget from = parseFrom(select);
        List<Column> projection = parseProjection(select);
        Predicate where = select.getWhere() == null ? null : predicate(select.getWhere());
        List<Order> orderBy = parseOrderBy(select);
        Integer limit = parseLimit(select);

        return new QueryAst(from.source(), from.subscriptionPattern(), projection, where, orderBy, limit, sql.trim());
    }

    // ---- clause-level rejections ---------------------------------------

    private void rejectUnsupportedSelectClauses(PlainSelect select) {
        if (select.getJoins() != null && !select.getJoins().isEmpty()) {
            throw new SqlSyntaxException(
                    "The dialect has no joins. A query reads one subscription pattern; use a wildcard in FROM to span subscriptions.",
                    "JOIN");
        }
        if (select.getGroupBy() != null) {
            throw new SqlSyntaxException("The dialect has no GROUP BY.", "GROUP BY");
        }
        if (select.getHaving() != null) {
            throw new SqlSyntaxException("The dialect has no HAVING.", "HAVING");
        }
        if (select.getDistinct() != null) {
            throw new SqlSyntaxException("The dialect has no DISTINCT.", "DISTINCT");
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw new SqlSyntaxException("The dialect has no common table expressions.", "WITH");
        }
        if (select.getOffset() != null || select.getFetch() != null) {
            throw new SqlSyntaxException("The dialect has no OFFSET or FETCH — use LIMIT.", "OFFSET");
        }
        if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()) {
            throw new SqlSyntaxException("The console cannot write. SELECT INTO is not part of the dialect.", "INTO");
        }
    }

    // ---- FROM ----------------------------------------------------------

    private record FromTarget(Source source, String subscriptionPattern) {}

    /**
     * Resolves the source qualifier and the subscription pattern.
     *
     * <p>JSqlParser splits a dotted name into schema and table parts <em>even inside
     * double quotes</em>, so {@code "ORDER.IN"} arrives as {@code "ORDER"."IN"}. The
     * fully-qualified name puts it back together; the leading segment is a source
     * only when it is the bare word {@code broker} or {@code index}, which is why
     * those two are written unquoted and a queue actually named {@code broker.x} is
     * still reachable as {@code broker."broker.x"}.
     */
    private FromTarget parseFrom(PlainSelect select) {
        if (!(select.getFromItem() instanceof Table table)) {
            throw new SqlSyntaxException(
                    "FROM must name a subscription or a subscription wildcard, in double quotes.",
                    select.getFromItem() == null ? "FROM" : select.getFromItem().toString());
        }
        List<String> segments =
                new ArrayList<>(Arrays.asList(table.getFullyQualifiedName().split("\\.")));
        Source source = Source.DEFAULT;
        String head = segments.getFirst();
        if (SOURCE_BROKER.equalsIgnoreCase(head) && segments.size() > 1) {
            source = Source.BROKER;
            segments.removeFirst();
        } else if (SOURCE_INDEX.equalsIgnoreCase(head) && segments.size() > 1) {
            source = Source.INDEX;
            segments.removeFirst();
        }
        // The quote spans the segments the parser split on ("ORDER.IN" arrives as
        // "ORDER / IN"), so the name is reassembled first and unquoted after.
        String pattern = String.join(".", segments).replace("\"", "");
        if (pattern.isBlank()) {
            throw new SqlSyntaxException("FROM names no subscription.", "FROM");
        }
        return new FromTarget(source, pattern);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- projection ----------------------------------------------------

    /** An empty projection means {@code *} — every catalogue column the source has. */
    private List<Column> parseProjection(PlainSelect select) {
        List<SelectItem<?>> items = select.getSelectItems();
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Column> columns = new ArrayList<>();
        for (SelectItem<?> item : items) {
            Expression e = item.getExpression();
            if (e instanceof net.sf.jsqlparser.statement.select.AllColumns) {
                return List.of();
            }
            Term term = term(e);
            if (term instanceof Term.ColumnTerm(var column)) {
                columns.add(column);
            } else {
                // props.* and body->>'x' are selected by asking for the whole row;
                // narrowing the projection to them would drop the identity columns a
                // result row is required to carry.
                return List.of();
            }
        }
        return List.copyOf(columns);
    }

    private List<Order> parseOrderBy(PlainSelect select) {
        List<OrderByElement> elements = select.getOrderByElements();
        if (elements == null || elements.isEmpty()) {
            return List.of();
        }
        return elements.stream()
                .map(e -> new Order(term(e.getExpression()), e.isAsc() ? Direction.ASC : Direction.DESC))
                .toList();
    }

    private Integer parseLimit(PlainSelect select) {
        if (select.getLimit() == null || select.getLimit().getRowCount() == null) {
            return null;
        }
        Expression rows = select.getLimit().getRowCount();
        if (rows instanceof LongValue lv) {
            long v = lv.getValue();
            if (v <= 0) {
                throw new SqlSyntaxException("LIMIT must be positive.", Long.toString(v));
            }
            return (int) Math.min(v, Integer.MAX_VALUE);
        }
        throw new SqlSyntaxException("LIMIT must be a whole number.", rows.toString());
    }

    // ---- WHERE ---------------------------------------------------------

    private Predicate predicate(Expression e) {
        return switch (e) {
            case AndExpression and ->
                new Predicate.And(List.of(predicate(and.getLeftExpression()), predicate(and.getRightExpression())));
            case OrExpression or ->
                new Predicate.Or(List.of(predicate(or.getLeftExpression()), predicate(or.getRightExpression())));
            case NotExpression not -> new Predicate.Not(predicate(not.getExpression()));
            case ParenthesedExpressionList<?> list -> parenthesised(list);
            case IsNullExpression isNull -> new Predicate.IsNull(term(isNull.getLeftExpression()), isNull.isNot());
            case LikeExpression like -> like(like);
            case InExpression in -> in(in);
            case Between between -> between(between);
            case ComparisonOperator cmp -> compare(cmp);
            case net.sf.jsqlparser.expression.operators.relational.FullTextSearch fts -> match(fts);
            default ->
                throw new SqlSyntaxException(
                        "That is not something the dialect can evaluate. Open the help for the operators it accepts.",
                        e.toString());
        };
    }

    /**
     * {@code MATCH (body) AGAINST ('terms')} — full-text search over the stored body
     * (ADR-0063).
     *
     * <p>This spelling rather than {@code MATCH(body, 'terms')} for a measured reason:
     * {@code MATCH} is a reserved word in the SQL grammar this parser implements, and
     * the two-argument call cannot be parsed at all — it fails on the comma. The
     * {@code AGAINST} form is the grammar's own, so the dialect gains full-text search
     * without a fork of the parser or a pre-pass over the operator's text.
     */
    private Predicate match(net.sf.jsqlparser.expression.operators.relational.FullTextSearch fts) {
        List<net.sf.jsqlparser.schema.Column> columns =
                fts.getMatchColumns() == null ? List.of() : new ArrayList<>(fts.getMatchColumns());
        if (columns.size() != 1) {
            throw new SqlSyntaxException("MATCH searches one column: MATCH (body) AGAINST ('terms').", fts.toString());
        }
        Term target = columnTerm(columns.getFirst());
        if (!(target instanceof Term.ColumnTerm(var column)) || column != Column.BODY) {
            throw new SqlSyntaxException(
                    "MATCH searches the body column: MATCH (body) AGAINST ('terms').",
                    columns.getFirst().toString());
        }
        if (!(fts.getAgainstValue() instanceof StringValue terms)) {
            throw new SqlSyntaxException(
                    "MATCH's terms are a quoted string: MATCH (body) AGAINST ('order 4471').",
                    String.valueOf(fts.getAgainstValue()));
        }
        if (fts.getSearchModifier() != null) {
            throw new SqlSyntaxException(
                    "The dialect has no search modifiers; the terms themselves carry the syntax"
                            + " — quoted phrases, -exclusion and or.",
                    fts.getSearchModifier());
        }
        return new Predicate.Match(terms.getValue());
    }

    private Predicate parenthesised(ParenthesedExpressionList<?> list) {
        if (list.size() != 1) {
            throw new SqlSyntaxException("A parenthesised group must hold one condition.", list.toString());
        }
        return predicate(list.get(0));
    }

    private Predicate compare(ComparisonOperator cmp) {
        Operator op = operator(cmp);
        Expression left = cmp.getLeftExpression();
        Expression right = cmp.getRightExpression();
        // A literal on the left is the same predicate read backwards; normalising it
        // here means every consumer downstream only ever sees term-op-literal.
        if (isLiteral(left) && !isLiteral(right)) {
            return new Predicate.Compare(term(right), op.mirrored(), literal(left));
        }
        return new Predicate.Compare(term(left), op, literal(right));
    }

    private Operator operator(ComparisonOperator cmp) {
        return switch (cmp) {
            case EqualsTo _ -> Operator.EQ;
            case NotEqualsTo _ -> Operator.NE;
            case MinorThan _ -> Operator.LT;
            case MinorThanEquals _ -> Operator.LTE;
            case GreaterThan _ -> Operator.GT;
            case GreaterThanEquals _ -> Operator.GTE;
            default -> throw new SqlSyntaxException("Unsupported comparison.", cmp.getStringExpression());
        };
    }

    private Predicate like(LikeExpression like) {
        Literal pattern = literal(like.getRightExpression());
        if (!(pattern instanceof Literal.Str(var text))) {
            throw new SqlSyntaxException(
                    "LIKE takes a quoted pattern.", like.getRightExpression().toString());
        }
        Character escape = null;
        if (like.getEscape() != null) {
            String raw = unquoteString(like.getEscape().toString());
            if (raw.length() != 1) {
                throw new SqlSyntaxException("ESCAPE takes a single character.", raw);
            }
            escape = raw.charAt(0);
        }
        boolean caseInsensitive = like.getLikeKeyWord() == LikeExpression.KeyWord.ILIKE;
        return new Predicate.Like(term(like.getLeftExpression()), text, escape, like.isNot(), caseInsensitive);
    }

    private Predicate in(InExpression in) {
        Term term = term(in.getLeftExpression());
        Expression right = in.getRightExpression();
        List<Literal> values = new ArrayList<>();
        if (right instanceof ExpressionList<?> list) {
            for (Expression item : list) {
                values.add(literal(item));
            }
        } else {
            throw new SqlSyntaxException("IN takes a parenthesised list of literals.", String.valueOf(right));
        }
        if (values.isEmpty()) {
            throw new SqlSyntaxException("IN takes at least one value.", in.toString());
        }
        return new Predicate.In(term, List.copyOf(values), in.isNot());
    }

    private Predicate between(Between between) {
        return new Predicate.Between(
                term(between.getLeftExpression()),
                literal(between.getBetweenExpressionStart()),
                literal(between.getBetweenExpressionEnd()),
                between.isNot());
    }

    // ---- terms and literals --------------------------------------------

    private Term term(Expression e) {
        return switch (e) {
            case net.sf.jsqlparser.schema.Column column -> columnTerm(column);
            case JsonExpression json -> jsonTerm(json);
            case Function function -> caseFold(function);
            case ParenthesedExpressionList<?> list when list.size() == 1 -> term(list.getFirst());
            default ->
                throw new SqlSyntaxException(
                        "Not a column the dialect knows. Columns are listed in the console help.", e.toString());
        };
    }

    private Term columnTerm(net.sf.jsqlparser.schema.Column column) {
        String table =
                column.getTable() == null ? null : unquote(column.getTable().getName());
        String name = unquote(column.getColumnName());
        // Not a catalogue column: match_rank is a property of the comparison a
        // MATCH() makes, so it exists only where one does and only in ORDER BY.
        if (table == null && "match_rank".equalsIgnoreCase(name)) {
            return new Term.MatchRank();
        }
        if (table != null && ColumnCatalogue.PROPERTY_PREFIX.equalsIgnoreCase(table)) {
            if (name.isBlank()) {
                throw new SqlSyntaxException("An application property needs a name, as props.<name>.", "props");
            }
            return new Term.PropertyTerm(name);
        }
        if (table != null) {
            throw new SqlSyntaxException(
                    "A column cannot be qualified. Application properties are addressed as props.<name>.",
                    table + "." + name);
        }
        return ColumnCatalogue.find(name).<Term>map(Term.ColumnTerm::new).orElseThrow(() -> unknownColumn(name));
    }

    private SqlSyntaxException unknownColumn(String name) {
        Optional<Column> near = ColumnCatalogue.suggest(name);
        return near.map(c -> new SqlSyntaxException(
                        "There is no column '" + name + "'. Did you mean '" + c.sqlName() + "'?", name, c.sqlName()))
                .orElseGet(() -> new SqlSyntaxException(
                        "There is no column '" + name
                                + "'. The console help lists every column; application properties are props.<name>.",
                        name));
    }

    /** {@code body->>'orderId'} — a JSON path into the body. Only the body has one. */
    private Term jsonTerm(JsonExpression json) {
        Term base = term(json.getExpression());
        if (!(base instanceof Term.ColumnTerm(var column)) || column != Column.BODY) {
            throw new SqlSyntaxException("A JSON path can only be taken from the body column.", json.toString());
        }
        for (String operator : json.getOperators()) {
            if (!"->>".equals(operator)) {
                throw new SqlSyntaxException("A JSON path reads a value with ->>, which returns text.", operator);
            }
        }
        List<String> path = new ArrayList<>();
        json.getIdents().forEach(ident -> path.add(unquoteString(ident.toString())));
        if (path.isEmpty()) {
            throw new SqlSyntaxException("A JSON path needs a key, as body->>'name'.", json.toString());
        }
        return new Term.JsonTerm(String.join(".", path));
    }

    private Term caseFold(Function function) {
        String name = function.getName().toLowerCase(Locale.ROOT);
        if (!ColumnCatalogue.ALLOWED_FUNCTIONS.contains(name)) {
            throw new SqlSyntaxException(
                    "The dialect has no function '" + name + "'. It accepts "
                            + String.join(", ", ColumnCatalogue.ALLOWED_FUNCTIONS) + ".",
                    name);
        }
        if (!"lower".equals(name) && !"upper".equals(name)) {
            throw new SqlSyntaxException("'" + name + "' is not a value here.", name);
        }
        List<Expression> args = arguments(function);
        if (args.size() != 1) {
            throw new SqlSyntaxException(name + "() takes exactly one column.", function.toString());
        }
        return new Term.CaseFold(term(args.getFirst()), "upper".equals(name));
    }

    private List<Expression> arguments(Function function) {
        ExpressionList<?> params = function.getParameters();
        if (params == null) {
            return List.of();
        }
        return new ArrayList<>(params);
    }

    private boolean isLiteral(Expression e) {
        return e instanceof StringValue
                || e instanceof LongValue
                || e instanceof DoubleValue
                || e instanceof BooleanValue
                || e instanceof SignedExpression
                || e instanceof Subtraction
                || (e instanceof Function f && "now".equalsIgnoreCase(f.getName()));
    }

    private Literal literal(Expression e) {
        return switch (e) {
            // getValue() keeps SQL's doubled quotes; the AST holds the real string,
            // so the renderer escapes exactly once on the way back out.
            case StringValue s -> new Literal.Str(s.getNotExcapedValue());
            case LongValue l -> new Literal.Num((double) l.getValue(), true);
            case DoubleValue d -> new Literal.Num(d.getValue(), false);
            case BooleanValue b -> new Literal.Bool(b.getValue());
            case SignedExpression signed -> signedLiteral(signed);
            case ParenthesedExpressionList<?> list when list.size() == 1 -> literal(list.getFirst());
            case Function f -> nowLiteral(f);
            case Subtraction sub -> relativeTime(sub);
            default ->
                throw new SqlSyntaxException(
                        "Expected a literal value — a quoted string, a number, true/false, or now() minus an interval.",
                        e.toString());
        };
    }

    private Literal signedLiteral(SignedExpression signed) {
        Literal inner = literal(signed.getExpression());
        if (inner instanceof Literal.Num(var value, var integral) && signed.getSign() == '-') {
            return new Literal.Num(-value, integral);
        }
        if (signed.getSign() == '+') {
            return inner;
        }
        throw new SqlSyntaxException("A sign can only be applied to a number.", signed.toString());
    }

    private Literal nowLiteral(Function f) {
        if (!"now".equalsIgnoreCase(f.getName())) {
            throw new SqlSyntaxException(
                    "The dialect has no function '" + f.getName() + "' in a value position.", f.getName());
        }
        if (!arguments(f).isEmpty()) {
            throw new SqlSyntaxException("now() takes no arguments.", f.toString());
        }
        return new Literal.RelativeTime(Duration.ZERO);
    }

    /** {@code now() - interval '2 hours'}. */
    private Literal relativeTime(Subtraction sub) {
        if (!(sub.getLeftExpression() instanceof Function f) || !"now".equalsIgnoreCase(f.getName())) {
            throw new SqlSyntaxException("A relative time is written now() - interval '<n> <unit>'.", sub.toString());
        }
        if (!(sub.getRightExpression() instanceof IntervalExpression interval)) {
            throw new SqlSyntaxException(
                    "A relative time subtracts an interval, as now() - interval '2 hours'.", sub.toString());
        }
        return new Literal.RelativeTime(interval(interval));
    }

    private Duration interval(IntervalExpression interval) {
        String raw = unquoteString(String.valueOf(interval.getParameter())).trim();
        String[] parts = raw.split("\\s+");
        String amountText;
        String unit;
        if (parts.length == 2) {
            amountText = parts[0];
            unit = parts[1];
        } else if (parts.length == 1 && interval.getIntervalType() != null) {
            amountText = parts[0];
            unit = interval.getIntervalType();
        } else {
            throw new SqlSyntaxException("An interval is written as '<n> <unit>', e.g. '2 hours'.", raw);
        }
        long amount;
        try {
            amount = Long.parseLong(amountText);
        } catch (NumberFormatException _) {
            throw new SqlSyntaxException("An interval's amount must be a whole number.", amountText);
        }
        if (amount < 0) {
            throw new SqlSyntaxException("An interval cannot be negative.", raw);
        }
        String normalised = unit.toLowerCase(Locale.ROOT);
        if (normalised.endsWith("s")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return switch (normalised) {
            case "second" -> Duration.ofSeconds(amount);
            case "minute" -> Duration.ofMinutes(amount);
            case "hour" -> Duration.ofHours(amount);
            case "day" -> Duration.ofDays(amount);
            case "week" -> Duration.ofDays(amount * 7);
            default -> throw new SqlSyntaxException("An interval's unit is second, minute, hour, day or week.", unit);
        };
    }

    private static String unquoteString(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length() - 1);
        }
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "unparseable";
        }
        return s.lines().findFirst().orElse("unparseable").trim();
    }
}
