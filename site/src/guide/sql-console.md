---
title: SQL Console
description: Query the messages across an Artemis cluster in SQL — including the body, which a JMS selector cannot see — with the cost of the query shown before it runs.
---

# SQL Console

![The SQL Console: a query across the subscriptions under ORDERS.*, its predicate pushed down to the brokers, and the rows found with each node's answer, one of them refused](/img/sql.png)

The question an operator actually arrives with is *"where did order 4471 go?"*.
Artemis cannot answer it. Its only server-side filter is a JMS selector, which
sees message headers and application properties — **not the body** — and applies
to one queue on one node at a time.

The SQL Console answers it, across every queue in the cluster, in one query.

```sql
SELECT * FROM "ORDER.*"
WHERE body->>'orderId' = '4471'
LIMIT 50
```

## A real dialect, a small subset

`SELECT` only. One `FROM`. `WHERE`, `ORDER BY`, `LIMIT`. No join, no subquery, no
union, no CTE — and no mutation is expressible at all.

It is real SQL syntax on purpose, so the editor's off-the-shelf grammar,
highlighting and completion work unmodified. Queries are parsed to an AST and
validated against a fixed column catalogue, node type by node type, rejecting by
default. Nothing is validated by pattern-matching the query text; a regular
expression asserting the absence of `DROP` is how this is usually got wrong.

Queue names are always double-quoted — `ORDER.IN` is a reserved word and a dot —
and wildcards are Artemis', not SQL's: `*` matches one dot-delimited level, `#`
matches many.

## Where the query reads

The schema qualifier picks the backend, in the query text itself, so a query
pasted into a ticket still says where it read from.

| | |
|---|---|
| `FROM broker."Q"` | The live brokers. Current truth. |
| `FROM index."Q"` | The historical index — still holds messages that have since been consumed, and only covers queues you subscribed. |
| `FROM "Q"` | The index when every queue it names is captured on its node, the live brokers otherwise — a queue that is only sampled is read live. Capture does not backfill, so a message already on the queue when capture began is not in the index; the result says where the index begins. The plan says which it picked. |

## What a predicate costs — before you run it

This is the part worth knowing. Every top-level `AND` conjunct is classified:

| Class | Meaning |
|---|---|
| **Target** | Chooses which queues are read. Costs nothing. |
| **Pushdown** | Becomes a JMS selector, so the broker filters and Rahdar never sees the non-matches. Costs nothing. |
| **Scan** | Rahdar examines every message the broker returned. |

Headers and application properties push down. The body never can — nothing but
Rahdar can read it. The **cost line** under the editor tells you which your query
is **before it runs**, in one sentence of words and numbers, and over the configured
cost ceiling the query is refused with the estimate, the ceiling and a hint for
narrowing it. It is refused rather than truncated: a truncated result is
indistinguishable from a complete one at a glance, and an operator mid-incident
reads it as "not there".

| The line says | It means |
|---|---|
| **Broker-filtered** | The brokers filter. Rahdar examines at most the row limit of messages from the queues. |
| **Scan** | Rahdar reads and examines about *n* messages on the queues across the nodes. Narrow it with a header predicate. |
| **Index** | The query reads Rahdar's index, up to the row limit. No broker is read. |
| **No cost** | No queue matches the `FROM` pattern, so nothing is read. |
| **Estimating** | The text has changed since the plan was asked for. |
| **Unavailable** | The estimate cannot be made, and the line says why: the editor is empty, you may not read messages here, the query has a syntax error, or the plan failed. An estimate that is not available is never shown as zero. |

The cost line is not announced as you type. The editor and **Run** are described by it,
so a screen reader reads it when either takes focus.

::: tip One trap worth stating
A predicate that is free on its own stops being free inside an `OR` with a body
predicate. Pushing down one side of an `OR` would narrow the set the other side
gets to see — so a disjunction containing a scan is scanned as a whole. This is
the one mistake in this area that produces a silently *wrong* answer rather than
a slow one.
:::

## Writing, running and cancelling

The editor sits above its results in one workspace. Drag the separator between them,
or focus it and press **↑** or **↓** (5% a step, 10% with **Shift**), or **Home** and
**End** for the smallest and largest editor. The smallest editor still shows three lines and
the cost line under them. Rahdar remembers the split in this browser.

**Run** and **Cancel** sit side by side in the Query toolbar above the workspace, so they stay
in view however small the editor is. **Ctrl .** (**⌘ .** on a Mac)
cancels from the editor or from anywhere on the page. Cancelling closes the stream, which
releases the query on the server and stops every broker read. The rows that had arrived
stay on screen, and the console says the query was cancelled and that they are not the
whole answer. Cancelling a live tail simply stops it. **Escape** never cancels: it closes
completion, then collapses the selection, and then moves focus out of the editor to the
Query toolbar.

A syntax error is marked in the editor on the offending token, with the reason beside it
and in the cost line. **F8** steps to it, and **Ctrl Shift M** lists the diagnostics.
A query that is refused or fails reads as an error with its cause and the next step, and
a refusal carries the estimate and the ceiling it was refused against.

Results use the same table as every other view. Its **Columns** menu shows or hides a
column and moves it earlier or later with the **Move earlier** and **Move later** buttons,
and remembers the choice in this browser. The message, which identifies a row, stays first.

## Columns

Selector-eligible (free): `priority`, `durable`, `timestamp`, `expiration`,
`size`, `jmsType`, `correlationId`, `groupId`, `userId`, and any application
property as `props.<name>`.

Target (free): `subscription`, `destination`, `node`.

Scan: `body`, `messageId`, `messageType`, `replyTo`, and — on the index only —
`observedAt`, `lastSeenAt`, `origin`, `orig_destination` and `sourceMessageId`.

Over the index, `MATCH (body) AGAINST ('terms')` is full-text search over stored
bodies, served from a GIN index rather than scanned, and `ORDER BY match_rank`
ranks by how well each row matched. Quoted phrases, `-exclusion` and `or` work as
they do in a search box. Binary bodies are not full-text indexed; a bytes
message whose body is text is searched like any other.

A JSON field inside the body is `body->>'orderId'`. The only functions the
dialect accepts are `now()`, `lower()`, `upper()`, plus `interval` in a relative
time. Relative time is normalised through each node's measured clock offset, so a
broker with a skewed clock still answers correctly.

## Worked examples

```sql
-- Cheapest possible: no predicate, the broker returns its head pages.
SELECT * FROM "ORDER.IN" ORDER BY timestamp DESC LIMIT 100;

-- Both predicates become a selector across a wildcard. Still free.
SELECT * FROM "ORDER.*" WHERE priority > 4 AND durable = true LIMIT 200;

-- Application properties are selector-eligible too.
SELECT * FROM "ORDER.IN" WHERE props.tenant = 'acme' LIMIT 200;

-- A scan. Narrow it with a header predicate first.
SELECT * FROM "ORDER.IN" WHERE body LIKE '%4471%' LIMIT 50;

-- Full-text over stored bodies, ranked. Index only.
SELECT * FROM index."ORDER.*"
WHERE MATCH (body) AGAINST ('"order 4471" -cancelled')
ORDER BY match_rank DESC LIMIT 50;

-- A time window, quantized and skew-corrected.
SELECT * FROM "ORDER.*"
WHERE timestamp > now() - interval '2 hours'
ORDER BY timestamp DESC LIMIT 500;
```

## The optional index

The message index is **opt-in per queue**, retention-bounded, and disposable. It
exists so a question can be asked about a message that has already been consumed
— which the brokers, correctly, no longer know anything about. It is treated as
retained payload for the purposes of access control and deletion, and dropping
it costs history, never truth.

It fills in one of two ways, and they make different claims:

| Mode | What a row means |
|---|---|
| **Sampled** | A poll of this queue saw this message. A message that arrived and was consumed between two polls was never recorded. |
| **Captured** | The address routed this message. Whether anything consumed it in between makes no difference. |

Capture changes routing on your brokers, so it is a deliberate act with its own
permission and its own page: [Message capture](/guide/message-capture).

## The live tail

The tail is a **poll**, never a consume. It cannot mutate anything and it does
not compete with your consumers. It says so permanently in the UI, because a
sampled tail that reads as a complete capture is a way to conclude a message was
never sent.

A tail over captured queues makes the stronger claim, and says a different thing:
every message the address routed appears, whether or not it was consumed
immediately. The two are never worded the same way — one node without a tap and
it is a sampled tail again, with the gap named.

## Acting on a result

You cannot. Deliberately. Acting on a result row routes back through the ordinary
message operations, which already carry the dry run, the bulk cap and the audit
record — a second path to a destructive verb would be a second place to get the
safety contract wrong.

## Decisions

- [ADR-0058](/reference/adr/0058-sql-console-query-model) — the dialect, AST validation, the predicate split
- [ADR-0059](/reference/adr/0059-message-index-is-opt-in-and-disposable) — the index
- [ADR-0060](/reference/adr/0060-sampled-tail-is-not-a-capture) — the sampled tail
- [ADR-0062](/reference/adr/0062-message-capture-is-a-divert-into-a-ring-bounded-queue) — capture
- [ADR-0063](/reference/adr/0063-postgres-full-text-over-the-message-index) — full-text search
- [ADR-0064](/reference/adr/0064-query-execution-is-post-then-stream) — how a query is executed
