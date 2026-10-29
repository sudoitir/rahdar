## 1. Measure and fix
- [ ] 1.1 Seed the QA stack's Kafka cluster at scale (`scripts/perf/kafka-load-seed.sh`) and run `scripts/perf/kafka-measure.sh`, including an hour of traffic for storage growth and the SSE bytes of one scrape tick (finding C10 of 19e4-kafka). Check: the figures meet the budgets in the specs, recorded in the guide
- [ ] 1.2 Fix every budget miss at its cause. Check: the measurement re-run meets the budgets

## 2. Sweep
- [ ] 2.1 Triage every failing check of the 19e4-kafka sweep rerun: fix the product, or correct the check where it is wrong, recording each. Check: a clean sweep at 1440 and 1920, light and dark, every state

## 3. Design and UX review
- [ ] 3.1 `opus-medium` reviews the design, every Kafka flow and the UI from the captures and the running app against the production-grade bar. Check: a findings list
- [ ] 3.2 Fix every finding; recapture the affected screens. Check: the reviewer's list is closed

## 5. Finish
- [ ] 5.1 Push; CI green and the Sonar list empty. Check: the PR's checks
