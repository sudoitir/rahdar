#!/usr/bin/env bash
#
# Seed a Kafka cluster at the scale task 6.2d measures: hundreds of topics and thousands of partitions, hundreds of
# consumer groups (a few active, most empty and far behind, some on the new protocol, a few share groups), lag in the
# millions, hundreds of ACLs and a few transactions left open. Then register the cluster with Rahdar.
#
# It uses the Kafka image's own tools through `docker compose exec`, the way scripts/lib/kafka-seed.sh does, and
# is idempotent: topics exist once, a topic already holding its records is not produced to again, a group is
# set to where it should be rather than made twice, and the registration of the same name is replaced.
#
# Sizes (environment; the defaults are the task's):
#   TOPICS=200             topics, named perf-t-0001 ...
#   PARTITIONS=15          partitions per topic (TOPICS x PARTITIONS is the total; the default is 3000)
#   CONSUMER_GROUPS=300    consumer groups (not GROUPS: bash keeps that name for itself)
#   ACTIVE_GROUPS=10       of them with a live member (classic protocol), each a small JVM
#   SHARE_GROUPS=3         share groups with a live member (needs share.version to finalise)
#   HOT_TOPICS=20          topics given RECORDS_HOT records; the rest RECORDS_COLD
#   RECORDS_HOT=100000     records per hot topic (20 x 100000 = 2 million of lag at the defaults)
#   RECORDS_COLD=200       records per other topic
#   BIG_GROUP_TOPICS=40    topics read by perf-big-group, the large group of the detail measurement
#   WIDE_PARTITIONS=50     partitions of perf-wide, read by perf-wide-group, the group of the reset preview
#   ACL_PRINCIPALS=50      principals given ACLs, 10 each: 500 in all (skipped, with a warning, without an authorizer)
#   HANGING_TRANSACTIONS=3 transactions left open
#   PARALLEL=8             client JVMs at once (each is small; lower it on a small Docker VM)
# Needs, for the registration: RAHDAR, COMPOSE (the docker compose invocation of the stack), ADMIN_PASSWORD and, once
# enrolled, ADMIN_TOTP_SECRET. DRY_RUN=1 prints every Kafka command and registers nothing, to check the plan.
# Bash 3.2: no mapfile, no associative arrays, no ${x,,}.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DRY_RUN=${DRY_RUN:-0}
RAHDAR=${RAHDAR:-http://localhost:8080}
if [ "$DRY_RUN" = 1 ]; then
  COMPOSE=${COMPOSE:-echo docker compose}
  # A dry run runs no command, so it also needs no stack: the plan is the output.
else
  COMPOSE=${COMPOSE:?set COMPOSE to the stack, e.g. "docker compose -p rahdar-demo -f ..."}
fi
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-}
export ADMIN_USER ADMIN_PASSWORD ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
PERF_CLUSTER=${PERF_CLUSTER:-perf-kafka}

TOPICS=${TOPICS:-200}
PARTITIONS=${PARTITIONS:-15}
CONSUMER_GROUPS=${CONSUMER_GROUPS:-300}
ACTIVE_GROUPS=${ACTIVE_GROUPS:-10}
SHARE_GROUPS=${SHARE_GROUPS:-3}
HOT_TOPICS=${HOT_TOPICS:-20}
RECORDS_HOT=${RECORDS_HOT:-100000}
RECORDS_COLD=${RECORDS_COLD:-200}
BIG_GROUP_TOPICS=${BIG_GROUP_TOPICS:-40}
WIDE_PARTITIONS=${WIDE_PARTITIONS:-50}
ACL_PRINCIPALS=${ACL_PRINCIPALS:-50}
HANGING_TRANSACTIONS=${HANGING_TRANSACTIONS:-3}
PARALLEL=${PARALLEL:-8}

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }

# shellcheck source=../lib/kafka-seed.sh
. "$here/../lib/kafka-seed.sh"

# kafka-seed.sh's tools run in a JVM of 128 MB; a dry run shows the command instead of running it.
if [ "$DRY_RUN" = 1 ]; then
  # File descriptor 3 is the terminal, because the seed's helpers send a tool's own output to /dev/null.
  exec 3>&1
  kafka() { printf 'kafka %s\n' "$*" >&3; }
  kafka_produce() { printf 'produce %s %s records\n' "$1" "$2" >&3; }
fi
kafka_init

topic_name() { printf 'perf-t-%04d' "$1"; }

# The records a topic holds: the sum of the end offsets the image's tool prints as topic:partition:offset.
records_in() {
  if [ "$DRY_RUN" = 1 ]; then echo 0; return; fi
  kafka kafka-get-offsets.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$1" 2>/dev/null \
    | awk -F: '{ sum += $3 } END { print sum + 0 }'
}

# Runs a function for 1..N, PARALLEL at a time; a failure of one is reported by the function, not fatal here.
each_parallel() { # function count
  local fn=$1 count=$2 i
  for i in $(seq 1 "$count"); do
    "$fn" "$i" &
    if [ $((i % PARALLEL)) -eq 0 ]; then wait; fi
  done
  wait
}

make_topic() { kafka_topic "$(topic_name "$1")" "$PARTITIONS"; }

produce_topic() { # i
  local topic want have
  topic=$(topic_name "$1")
  want=$RECORDS_COLD
  [ "$1" -le "$HOT_TOPICS" ] && want=$RECORDS_HOT
  have=$(records_in "$topic")
  if [ "$have" -ge "$want" ]; then return; fi
  kafka_produce "$topic" $((want - have)) -1
}

say "topics: $TOPICS x $PARTITIONS partitions ($((TOPICS * PARTITIONS)) in all), plus perf-wide with $WIDE_PARTITIONS"
each_parallel make_topic "$TOPICS"
kafka_topic perf-wide "$WIDE_PARTITIONS"

say "records: $HOT_TOPICS hot topics of $RECORDS_HOT, the rest of $RECORDS_COLD, and perf-wide"
each_parallel produce_topic "$TOPICS"
if [ "$(records_in perf-wide)" -lt "$RECORDS_HOT" ]; then kafka_produce perf-wide "$RECORDS_HOT" -1; fi

# Share groups are a cluster feature that has to be finalised; the seed goes on without them where it cannot be.
share_ok=1
kafka kafka-features.sh --bootstrap-server "$KAFKA_BOOTSTRAP" upgrade --feature share.version=2 >/dev/null 2>&1 \
  || kafka kafka-features.sh --bootstrap-server "$KAFKA_BOOTSTRAP" upgrade --feature share.version=1 >/dev/null 2>&1 \
  || share_ok=0

# A group set to the earliest offset of the topics it reads is empty and as far behind as the topics are long.
behind() { # group topic...
  local group=$1 args="" t
  shift
  for t in "$@"; do args="$args --topic $t"; done
  # shellcheck disable=SC2086
  kafka kafka-consumer-groups.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --group "$group" $args \
    --reset-offsets --to-earliest --execute >/dev/null 2>&1 || echo "  group $group: not set" >&2
}

# A group that is still running: a console consumer that reads on until the stack goes.
member() { # group topic [consumer property...]
  local group=$1 topic=$2
  shift 2
  local state
  state=$(kafka kafka-consumer-groups.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --group "$group" --state 2>/dev/null || true)
  case $state in *Stable*) return ;; esac
  $COMPOSE exec -d "${KAFKA_JVM[@]}" "$KAFKA_B" /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$topic" --group "$group" --from-beginning "$@" >/dev/null 2>&1 || true
}

make_group() { # i: every fifth empty group joins once on the consumer protocol, so both protocols are there
  local i=$1 topic
  topic=$(topic_name $((((i - 1) % HOT_TOPICS) + 1)))
  if [ "$i" -le "$ACTIVE_GROUPS" ]; then
    member "perf-active-$i" "$topic"
  elif [ $((i % 5)) -eq 0 ]; then
    kafka kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$topic" --group "perf-new-$i" \
      --consumer-property group.protocol=consumer --from-beginning --max-messages 1 --timeout-ms 20000 \
      >/dev/null 2>&1 || echo "  group perf-new-$i: not made" >&2
  else
    behind "perf-lag-$i" "$topic"
  fi
}

say "groups: $CONSUMER_GROUPS ($ACTIVE_GROUPS active, every fifth on the consumer protocol, the rest empty and behind)"
each_parallel make_group "$CONSUMER_GROUPS"

big=""
for i in $(seq 1 "$BIG_GROUP_TOPICS"); do big="$big $(topic_name "$i")"; done
# shellcheck disable=SC2086
behind perf-big-group $big
behind perf-wide-group perf-wide

if [ "$share_ok" = 1 ] && [ "$SHARE_GROUPS" -gt 0 ]; then
  say "share groups: $SHARE_GROUPS"
  for i in $(seq 1 "$SHARE_GROUPS"); do
    $COMPOSE exec -d "${KAFKA_JVM[@]}" "$KAFKA_C" /opt/kafka/bin/kafka-console-share-consumer.sh \
      --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$(topic_name "$i")" --group "perf-share-$i" >/dev/null 2>&1 \
      || echo "  no share consumer in this image" >&2
  done
else
  echo "  share.version could not be finalised: no share groups" >&2
fi

say "ACLs: $ACL_PRINCIPALS principals x 10 = $((ACL_PRINCIPALS * 10))"
acl_failed=0
for i in $(seq 1 "$ACL_PRINCIPALS"); do
  args=""
  for t in $(seq 1 5); do args="$args --topic $(topic_name $((((i + t) % TOPICS) + 1)))"; done
  # shellcheck disable=SC2086
  kafka kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --add --allow-principal "User:perf-svc-$i" \
    --operation Read --operation Describe $args >/dev/null 2>&1 || { acl_failed=1; break; }
done
[ "$acl_failed" = 1 ] && echo "  the cluster has no authorizer: no ACLs" >&2

say "transactions left open: $HANGING_TRANSACTIONS"
hang() { # i
  $COMPOSE exec -T "${KAFKA_JVM[@]}" "$KAFKA_B" sh -c "/opt/kafka/bin/kafka-producer-perf-test.sh \
    --topic $(topic_name 1) --num-records 1000000 --record-size 128 --throughput 20 --transactional-id perf-tx-$1 \
    --transaction-duration-ms 3600000 --producer-props bootstrap.servers=$KAFKA_BOOTSTRAP transaction.timeout.ms=900000 \
    >/dev/null 2>&1 & pid=\$!; sleep 25; kill -9 \$pid" >/dev/null 2>&1 || echo "  transaction perf-tx-$1: not left open" >&2
}
each_parallel hang "$HANGING_TRANSACTIONS"

if [ "$DRY_RUN" = 1 ]; then
  say "dry run: nothing was registered"
  exit 0
fi

say "registering $PERF_CLUSTER with Rahdar"
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT
# shellcheck source=../lib/signin.sh
. "$here/../lib/signin.sh"
rahdar_sign_in "" || exit 1
existing=$(api GET "/clusters?size=500" | python3 -c 'import json,sys; print(next((c["id"] for c in json.load(sys.stdin)["data"] if c["name"]=="'"$PERF_CLUSTER"'"), ""))')
[ -n "$existing" ] && api DELETE "/clusters/$existing" >/dev/null
servers=$(printf '%s' "$KAFKA_BOOTSTRAP" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().split(",")))')
cluster=$(api POST /clusters -d "{\"system\":\"kafka\",\"name\":\"$PERF_CLUSTER\",\"description\":\"Performance at scale (task 6.2d)\",\"connection\":{\"system\":\"kafka\",\"bootstrapServers\":$servers,\"securityProtocol\":\"PLAINTEXT\"}}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
say "seeded. $PERF_CLUSTER is $cluster. Wait one scrape tier B interval, then: scripts/perf/kafka-measure.sh"
