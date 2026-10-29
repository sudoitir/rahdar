## 1. Measure and fix

- [ ] 1.1 Seed the isolated stack's Kafka cluster at scale (`scripts/perf/kafka-load-seed.sh`) after the row cap and the heartbeat writer are in, and run `scripts/perf/kafka-measure.sh`, including an hour of traffic for storage growth and the SSE bytes of one scrape tick. Check: the figures meet the budgets in the specs and are recorded in `docs/sizing.md` and `site/src/guide/kafka.md`
- [ ] 1.2 Fix every budget the run still misses, at its cause, and re-measure only that budget. Check: the re-run meets it

## 2. Sweep

- [ ] 2.1 Triage every failing check of the sweep over the estate, registration, every Kafka screen and the shared screens on Kafka (1440, 1920, and 1280, light and dark, 200% zoom, every state including one unreachable broker). Fix the product, or correct the check where it is wrong, and record each under `qa/`. Check: a clean sweep

## 3. Design and UX review

- [ ] 3.1 Review the design, every Kafka flow (registration, offset reset, truncate, ACL change, transaction abort, hanging transactions, browse, bulk purge) and the UI from the captures and the running app. Check: a findings list
- [ ] 3.2 Fix every finding that is not a new Kafka feature; record a new feature for its own change. Recapture the affected screens. Check: the list is closed

## 4. Findings carried from 19e4-kafka

- [ ] 4.1 Cap broker events by rows as well as by time, by taking `56a57bb9`: housekeeping trims the oldest rows once a store passes its row quota, and broker events start at twenty million. Check: a test that inserts past the cap keeps it
- [ ] 4.2 Store metric samples at a 60-second heartbeat, whether or not the value changed, and never faster (ADR-0220). Raise the chart bucket floor to that heartbeat. Widen rate and slow-consumer windows to two heartbeats when that is longer than two tier-B intervals. Check: a changed value inside the heartbeat writes nothing, a quiet series writes once per heartbeat, and a flat acknowledgement rate is zero rather than absent
- [ ] 4.3 One unreachable Kafka cluster must not stall the scrape of the others. The side-by-side read is on main; add a test whose blackholed cluster sticks on the subscription read. Check: that test passes
- [ ] 4.4 The scrape reads subscriptions once per tick. Check: a test counting the reads
- [ ] 4.5 An existing Kafka topic or subscription the caller may not read answers not-found; a readable one the caller may not change answers 403. Check: the per-route tests pass
- [ ] 4.6 Where a depth was not reported, the UI says it is unavailable and does not show 0, including the message-index subscription grid. Check: unit tests

## 5. Known flow defects

- [ ] 5.1 The purge dialog states a real skip count, never NaN, and says that no record is deleted. Check: a unit test
- [ ] 5.2 A bulk purge moves every group the preview said it would act on. Check: a test of the run
- [ ] 5.3 Browse shows the group position, and does not render it as undefined. Check: a unit test
- [ ] 5.4 Aborting a transaction does not crash the page. Check: a unit test
- [ ] 5.5 Hanging-transaction copy describes an open-past-timeout transaction when the review finds the current copy misleading. Check: the review finding is closed, or recorded as already accurate

## 6. Finish

- [ ] 6.1 Push; CI green and the Sonar list empty. Archive this change on the branch. Check: the PR's checks
