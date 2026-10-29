## 1. Measure and fix
- [ ] 1.1 Seed the QA stack's Kafka cluster at scale (`scripts/perf/kafka-load-seed.sh`) and run `scripts/perf/kafka-measure.sh`, including an hour of traffic for storage growth and the SSE bytes of one scrape tick (finding C10 of 19e4-kafka). Check: the figures meet the budgets in the specs, recorded in the guide
- [ ] 1.2 Fix every budget miss at its cause. Check: the measurement re-run meets the budgets

## 2. Sweep
- [ ] 2.1 Triage every failing check of the 19e4-kafka sweep rerun: fix the product, or correct the check where it is wrong, recording each. Check: a clean sweep at 1440 and 1920, light and dark, every state

## 3. Design and UX review
- [ ] 3.1 `opus-medium` reviews the design, every Kafka flow and the UI from the captures and the running app against the production-grade bar. Check: a findings list
- [ ] 3.2 Fix every finding; recapture the affected screens. Check: the reviewer's list is closed

## 4. Findings carried from 19e4-kafka
- [ ] 4.1 S2: cap broker_event by rows as well as by time. A start is on branch `claude/19e4b-s2-start` (not in this pull request): housekeeping trims the oldest rows once a store passes its row quota, and broker events start at twenty million. Check: a test that inserts past the cap keeps it
- [ ] 4.2 S3: store metric samples on change plus a heartbeat, so 4000 Kafka subscriptions stay within the storage budget. Check: the 1.1 measurement
- [ ] 4.3 B1: one unreachable Kafka cluster must not stall the scrape of the others (per-cluster deadline). Check: a test with a blackholed cluster
- [ ] 4.4 A10: the scrape reads subscriptions once per tick. Check: a test counting the reads
- [ ] 4.5 E11: per-route tests that an unreadable Kafka resource answers not-found (the authorization spec). Check: the tests pass
- [ ] 4.6 Web: adopt the nullable depths and the browse/SQL shapes from 19e4-kafka where the UI still renders 0 for unknown. Check: unit tests, and the sweep shows "Unknown"

## 5. Finish
- [ ] 5.1 Push; CI green and the Sonar list empty. Check: the PR's checks
