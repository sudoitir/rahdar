# Design and flow review

Reviewed 2026-10-30 against the running console (cluster `events`) and the screens' source. Desktop only.
A finding is **fixed**, **follow-up** (a new Kafka feature, not built here), or **accurate** (looked at, left as it is).

## Registration

The Kafka form asks for bootstrap servers, shows each parsed entry, then the security protocol and only the
credentials that protocol uses. Register stays disabled until a check of these exact inputs has passed, and the
reason is in the bar. A scheme on a server, a missing SASL member, an unreachable broker, a failed check and a
cluster that is already registered each say what happened and what to do. **Accurate.**

The parsed-server pills were inserted under the field when the first server was typed, which pushed the rest
of the form down (layout shift about 0.012, over the sweep's 0.01). The row is now reserved before any server
is typed. **Fixed.**

## Offset reset

The dialog names the group, previews each partition (where it is, where it would move, records skipped or read
again), and will not arm while members, a rebalance, a stale preview or a partial reset block it. The blocked
reason says which. **Accurate.**

## Truncate

The preview says how many records the cut removes, or that they could not be counted, and lists every group
that would lose records it has not read. A compacted topic is the system's refusal and the confirmation stays
disabled. The hold names the cut. **Accurate.**

## ACL change

Additions and removals are collected, then reviewed. The review shows access that would be lost, in words, and
a removal is confirmed by holding. A cluster with no authoriser says so instead of showing an empty list.
**Accurate.**

## Transaction abort

The dialog reads the effect before it can be armed, names the partitions the abort unblocks, and says the
producer is fenced and the next write fails. A missing `blocked` or `released` list does not throw. A change
since the preview (412) or a refusal (409) keeps the dialog open with the reason. **Accurate.**

## Hanging transactions

The page header separates the two situations. A transaction past its timeout is flagged on the list: the
coordinator aborts it by itself, and until it does it blocks the partitions beside it. **Hanging transactions**
below looks only when asked, and only for an open transaction the coordinator does not know, which no timeout
will end. The two sentences define the word for the section they are in. The guide said they were one thing;
it now says which sentence is which. The screen was not rewritten. **Accurate.**

## Browse

A group browse whose broker reported a position says where the group stands, with that offset. Live, on
2026-10-30, `billing` on `orders` returned `subscriptionPosition` 499 and the page read "The group stands at
offset 499 on partition 0". The word undefined was not on the page. A position the response omits is the
sentence "The group's position on this partition is unavailable.", covered by `MessagesView.partitioned.test.tsx`.
It is not rendered as 0. **Accurate.**

## Bulk purge

The preview states the records the groups will skip, that no record is deleted, and that records an open
transaction still holds stay. An uncounted skip refuses that group and leaves the hold disarmed. The move is
the one the preview counted. **Accurate.**

## Follow-ups

None. Nothing in this review needs a Kafka capability the product does not already have.
