#!/usr/bin/env bash
#
# Measure the Kafka screens' APIs and the scrape against the budgets of task 6.2d, on a cluster scripts/perf/
# kafka-load-seed.sh seeded and Rahdar has scraped. Signs in, warms each call up, times N calls of each (curl's own
# total time) and prints p50, p95 and max with a verdict, then the scrape's own numbers over a window.
#
# Budgets (openspec/changes/19e4-kafka/tasks.md, 6.2d):
#   - every Kafka list and detail API: p95 under 500 ms
#   - bounded responses: a list asked for far more than its maximum answers at most that maximum, in under 1 MiB
#   - one batched driver read per scrape cycle and cluster for the nodes and for the partitions, one sweep per tier B
#     cycle for the subscriptions, never one per group or topic (rahdar.kafka.scrape.rounds against rahdar.scrape.cycle)
#   - a scrape pass shorter than its interval (rahdar.scrape.cycle's max against the tier's interval)
#   - no screen issues a broker call per row: the unit tests of the list paths (KafkaGroupsTest, KafkaAclsTest) assert it;
#     here it shows as a list of hundreds of rows answering as fast as a list of ten.
#
# Environment: RAHDAR (default http://localhost:8080), ADMIN_USER, ADMIN_PASSWORD, ADMIN_TOTP_SECRET (as the seed),
# PERF_CLUSTER (default perf-kafka), N (calls per measure, default 30), N_HEAVY (default 10, for the calls that read
# the cluster live), WARMUP (default 3), WINDOW (seconds the scrape is observed, default 120), and the tier intervals
# TIER_A, TIER_B (seconds, default 5 and 15; set them to the installation's settings if they were changed).
# DRY_RUN=1 prints the plan (the calls and the budgets) and signs in to nothing. Bash 3.2.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RAHDAR=${RAHDAR:-http://localhost:8080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-}
export ADMIN_USER ADMIN_PASSWORD ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
PERF_CLUSTER=${PERF_CLUSTER:-perf-kafka}
N=${N:-30}
N_HEAVY=${N_HEAVY:-10}
WARMUP=${WARMUP:-3}
WINDOW=${WINDOW:-120}
TIER_A=${TIER_A:-5}
TIER_B=${TIER_B:-15}
DRY_RUN=${DRY_RUN:-0}
P95_BUDGET_MS=500
MAX_BYTES=1048576
FAILED=0

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }

COOKIES=$(mktemp)
RESULTS=$(mktemp)
trap 'rm -f "$COOKIES" "$RESULTS"' EXIT

if [ "$DRY_RUN" != 1 ]; then
  # shellcheck source=../lib/signin.sh
  . "$here/../lib/signin.sh"
  rahdar_sign_in "" || exit 1
fi

# timed METHOD PATH [BODY] → "milliseconds http-status bytes" for one call
timed() {
  local method=$1 endpoint=$2 body=${3:-}
  local args=(-sS -o /dev/null -w '%{time_total} %{http_code} %{size_download}' -b "$COOKIES" -c "$COOKIES"
    -X "$method" "$RAHDAR/api/v1$endpoint" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)")
  [ -n "$body" ] && args=("${args[@]}" -d "$body")
  curl "${args[@]}" | awk '{ printf "%d %s %s\n", $1 * 1000 + 0.5, $2, $3 }'
}

# percentile P of the numbers on stdin (nearest rank)
percentile() {
  sort -n | awk -v p="$1" '{ a[NR] = $1 } END { i = int((p / 100) * NR + 0.999999); if (i < 1) i = 1; if (i > NR) i = NR; print a[i] }'
}

# measure LABEL CALLS METHOD PATH [BODY]: warm up, time CALLS calls, record "label p50 p95 max bytes verdict"
measure() {
  local label=$1 calls=$2 method=$3 endpoint=$4 body=${5:-}
  if [ "$DRY_RUN" = 1 ]; then
    printf '  %-34s %s %s (%s calls after %s warm-up)\n' "$label" "$method" "$endpoint" "$calls" "$WARMUP"
    return
  fi
  local out times="" status bytes=0 bad=""
  for _ in $(seq 1 "$WARMUP"); do timed "$method" "$endpoint" "$body" >/dev/null; done
  for _ in $(seq 1 "$calls"); do
    out=$(timed "$method" "$endpoint" "$body")
    times="$times ${out%% *}"
    status=$(printf '%s' "$out" | awk '{print $2}')
    bytes=$(printf '%s' "$out" | awk '{print $3}')
    case $status in 2??) ;; *) bad=$status ;; esac
  done
  local p50 p95 max verdict=ok
  # shellcheck disable=SC2086
  p50=$(printf '%s\n' $times | percentile 50)
  p95=$(printf '%s\n' $times | percentile 95)
  max=$(printf '%s\n' $times | percentile 100)
  [ -n "$bad" ] && verdict="HTTP $bad"
  [ -z "$bad" ] && [ "$p95" -ge "$P95_BUDGET_MS" ] && verdict="OVER BUDGET"
  [ "$bytes" -gt "$MAX_BYTES" ] && verdict="UNBOUNDED"
  [ "$verdict" != ok ] && FAILED=1
  printf '%s|%s|%s|%s|%s|%s\n' "$label" "$p50" "$p95" "$max" "$bytes" "$verdict" >>"$RESULTS"
}

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
enc() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

if [ "$DRY_RUN" = 1 ]; then
  C=CLUSTER
else
  C=$(api GET "/clusters?size=500" | json "next((c['id'] for c in d['data'] if c['name']=='$PERF_CLUSTER'), '')")
  [ -n "$C" ] || { echo "no cluster named $PERF_CLUSTER: run scripts/perf/kafka-load-seed.sh" >&2; exit 1; }
fi
K="/clusters/$C/kafka"

say "API latency on $PERF_CLUSTER ($N calls, $N_HEAVY for the calls that read the cluster live)"
measure "estate subscriptions" "$N" GET "/subscriptions?size=100"
measure "cluster subscriptions" "$N" GET "/clusters/$C/subscriptions?size=100"
measure "groups, first page (100)" "$N" GET "$K/groups?limit=100"
measure "groups, largest page (500)" "$N" GET "$K/groups?limit=500"
if [ "$DRY_RUN" = 1 ]; then
  measure "groups, a later cursor page" "$N" GET "$K/groups?limit=100&cursor=CURSOR"
else
  cursor=$(api GET "$K/groups?limit=100" | json "d.get('next') or ''")
  cursor=$(api GET "$K/groups?limit=100&cursor=$(enc "$cursor")" | json "d.get('next') or ''")
  measure "groups, a later cursor page" "$N" GET "$K/groups?limit=100&cursor=$(enc "$cursor")"
fi
measure "large group detail (40 topics)" "$N_HEAVY" GET "$K/groups/perf-big-group"
measure "offset-reset preview (50 partitions)" "$N_HEAVY" POST "$K/groups/perf-wide-group/offsets:preview" '{"to":"earliest"}'
measure "topic config" "$N" GET "$K/topics/perf-t-0001/config"
measure "ACLs list" "$N" GET "$K/acls?limit=500"
measure "transactions" "$N" GET "$K/transactions?limit=500"
measure "brokers" "$N" GET "$K/brokers"
measure "browse a partition (50 records)" "$N" GET \
  "/clusters/$C/resources/kafka.topic/perf-t-0001/messages?partition=0&from=earliest&size=50"
measure "backlog breakdown" "$N_HEAVY" GET \
  "/clusters/$C/subscriptions/kafka.group-topic/$(enc "perf-big-group/perf-t-0001")/backlog"

say "bounded responses: asked for far more than the maximum"
bounded() { # label path count-expression
  [ "$DRY_RUN" = 1 ] && { printf '  %-34s %s\n' "$1" "$2"; return; }
  local n
  n=$(api GET "$2" | json "$3")
  printf '  %-34s %s items' "$1" "$n"
  if [ "$n" -le 500 ]; then echo "  ok (at most 500)"; else echo "  UNBOUNDED"; FAILED=1; fi
}
bounded "groups, limit=100000" "$K/groups?limit=100000" "len(d['items'])"
bounded "ACLs, limit=100000" "$K/acls?limit=100000" "len(d['items'])"
bounded "transactions, limit=100000" "$K/transactions?limit=100000" "len(d['items'])"

if [ "$DRY_RUN" != 1 ]; then
  printf '\n%-40s %8s %8s %8s %10s  %s\n' "call" "p50 ms" "p95 ms" "max ms" "bytes" "verdict"
  awk -F'|' '{ printf "%-40s %8s %8s %8s %10s  %s\n", $1, $2, $3, $4, $5, $6 }' "$RESULTS"
fi

# ---- the scrape ---------------------------------------------------------------------------------------------------

# metric NAME [FILTER [FILTER]] → the sum of the samples of the Prometheus metric whose line contains both filters (the
# labels of a line are in alphabetical order, so each is given apart: 'call="nodes"' 'cluster="..."')
METRICS_URL=${METRICS_URL:-$RAHDAR/actuator/prometheus}
metrics_text() {
  if [ -n "${METRICS_TOKEN:-}" ]; then
    curl -sS -H "Authorization: Bearer $METRICS_TOKEN" "$METRICS_URL"
  else
    curl -sS -b "$COOKIES" "$METRICS_URL"
  fi
}
metric() {
  metrics_text | awk -v name="$1" -v f1="${2:-}" -v f2="${3:-}" '
    $1 ~ ("^" name "(\\{| |$)") && index($0, f1) && index($0, f2) { sum += $NF }
    END { printf "%.6f\n", sum + 0 }'
}

say "the scrape over $WINDOW s (rahdar.scrape.cycle, rahdar.kafka.scrape.rounds)"
if [ "$DRY_RUN" = 1 ]; then
  echo "  reads actuator/prometheus before and after the window and prints, per tier, the cycles, the longest pass and"
  echo "  the interval, and per call the driver reads per cycle against the budget of one."
  exit 0
fi
cl="cluster=\"$C\""
a0=$(metric rahdar_scrape_cycle_seconds_count 'tier="A"')
b0=$(metric rahdar_scrape_cycle_seconds_count 'tier="B"')
n0=$(metric rahdar_kafka_scrape_rounds_total "$cl" 'call="nodes"')
p0=$(metric rahdar_kafka_scrape_rounds_total "$cl" 'call="partitions"')
s0=$(metric rahdar_kafka_scrape_rounds_total "$cl" 'call="subscriptions"')
sleep "$WINDOW"
a1=$(metric rahdar_scrape_cycle_seconds_count 'tier="A"')
b1=$(metric rahdar_scrape_cycle_seconds_count 'tier="B"')
n1=$(metric rahdar_kafka_scrape_rounds_total "$cl" 'call="nodes"')
p1=$(metric rahdar_kafka_scrape_rounds_total "$cl" 'call="partitions"')
s1=$(metric rahdar_kafka_scrape_rounds_total "$cl" 'call="subscriptions"')
maxA=$(metric rahdar_scrape_cycle_seconds_max 'tier="A"')
maxB=$(metric rahdar_scrape_cycle_seconds_max 'tier="B"')

verdict() { awk -v v="$1" -v budget="$2" 'BEGIN { print (v <= budget + 1e-9) ? "ok" : "OVER BUDGET" }'; }
per() { awk -v d="$1" -v c="$2" 'BEGIN { if (c <= 0) print "n/a"; else printf "%.2f\n", d / c }'; }

printf '\n%-44s %12s %10s  %s\n' "scrape" "measured" "budget" "verdict"
cyclesA=$(awk -v a="$a1" -v b="$a0" 'BEGIN { print a - b }')
cyclesB=$(awk -v a="$b1" -v b="$b0" 'BEGIN { print a - b }')
for row in "nodes reads per tier A cycle|$(per "$(awk -v a="$n1" -v b="$n0" 'BEGIN{print a-b}')" "$cyclesA")|1" \
  "partitions reads per tier A cycle|$(per "$(awk -v a="$p1" -v b="$p0" 'BEGIN{print a-b}')" "$cyclesA")|1" \
  "subscription sweeps per tier B cycle|$(per "$(awk -v a="$s1" -v b="$s0" 'BEGIN{print a-b}')" "$cyclesB")|1" \
  "longest tier A pass (s)|$maxA|$TIER_A" \
  "longest tier B pass (s)|$maxB|$TIER_B"; do
  name=${row%%|*}; rest=${row#*|}; measured=${rest%%|*}; budget=${rest##*|}
  if [ "$measured" = "n/a" ]; then v="NO CYCLES"; FAILED=1; else v=$(verdict "$measured" "$budget"); fi
  [ "$v" != ok ] && FAILED=1
  printf '%-44s %12s %10s  %s\n' "$name" "$measured" "$budget" "$v"
done
echo "(tier A cycles in the window: $cyclesA, tier B: $cyclesB; a pass covers every cluster of this replica)"

[ "$FAILED" = 0 ] && say "every budget met" || say "a budget was not met: see above"
exit "$FAILED"
