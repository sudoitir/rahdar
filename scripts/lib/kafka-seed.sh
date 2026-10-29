# shellcheck shell=bash
#
# Seeds a Kafka cluster with the states a demo or a screenshot sweep needs, using the Kafka image's own tools.
# Sourced by demo-seed.sh and qa-seed.sh; needs `say`, `api` (lib/signin.sh) and $COMPOSE, the docker compose
# invocation of the stack. Bash 3.2: no mapfile, no associative arrays, no ${x,,}.
#
# The stack has either the demo's three brokers (kafka-1 to kafka-3) or the dev stack's single `kafka`
# (which also answers to kafka-1), and the seed adapts: the replication factor is the number of brokers.

# Sets KAFKA_BOOTSTRAP (as Rahdar reaches the brokers), KAFKA_RF and the services the seed's tools run in.
kafka_init() {
  if $COMPOSE config --services 2>/dev/null | grep -qx kafka-1; then
    KAFKA_BOOTSTRAP=kafka-1:9092,kafka-2:9092,kafka-3:9092
    KAFKA_RF=3
    KAFKA_A=kafka-1 KAFKA_B=kafka-2 KAFKA_C=kafka-3
  else
    KAFKA_BOOTSTRAP=kafka:9092
    KAFKA_RF=1
    KAFKA_A=kafka KAFKA_B=kafka KAFKA_C=kafka
  fi
  # Each client JVM is small: a dozen of them would not fit in a laptop's Docker VM at the broker's heap.
  KAFKA_JVM=(-e 'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m')
}

kafka() { # tool args…, run in the first broker
  local tool=$1; shift
  $COMPOSE exec -T "${KAFKA_JVM[@]}" "$KAFKA_A" "/opt/kafka/bin/$tool" "$@"
}

# Registers the cluster as "events" through Rahdar's API, replacing a previous registration of that name.
kafka_register() {
  local existing servers
  existing=$(api GET "/clusters?size=500" | python3 -c 'import json,sys; print(next((c["id"] for c in json.load(sys.stdin)["data"] if c["name"]=="events"), ""))')
  if [ -n "$existing" ]; then
    echo "removing the previous registration: $existing"
    api DELETE "/clusters/$existing" >/dev/null
  fi
  servers=$(printf '%s' "$KAFKA_BOOTSTRAP" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().split(",")))')
  kafka_cluster=$(api POST /clusters -d "{
    \"system\": \"kafka\",
    \"name\": \"events\",
    \"description\": \"Kafka — topics, consumer groups, a share group and a transaction left open\",
    \"connection\": {\"system\": \"kafka\", \"bootstrapServers\": $servers, \"securityProtocol\": \"PLAINTEXT\"}
  }" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  echo "registered: $kafka_cluster"
}

kafka_produce() { # topic records throughput
  kafka kafka-producer-perf-test.sh --topic "$1" --num-records "$2" --record-size 256 --throughput "$3" \
    --producer-props "bootstrap.servers=$KAFKA_BOOTSTRAP" >/dev/null 2>&1 || true
}

# One transaction left open. The perf test opens a transaction and holds it for an hour; killing the client
# in mid-transaction leaves it ongoing, holding back every reader of payments that reads only committed
# records, until the broker times it out (the producer asks for the broker's 15 minute maximum, or
# KAFKA_TXN_TIMEOUT_MS: the QA stack sets one minute and a stuck coordinator that never aborts it). Run again
# before that. Nothing here is a Java program: it is the image's own tool.
open_hanging_transaction() {
  $COMPOSE exec -T "${KAFKA_JVM[@]}" "$KAFKA_B" sh -c "/opt/kafka/bin/kafka-producer-perf-test.sh --topic payments \
    --num-records 1000000 --record-size 128 --throughput 20 --transactional-id payments-settlement \
    --transaction-duration-ms 3600000 \
    --producer-props bootstrap.servers=$KAFKA_BOOTSTRAP transaction.timeout.ms=${KAFKA_TXN_TIMEOUT_MS:-900000} >/dev/null 2>&1 & \
    pid=\$!; sleep 25; kill -9 \$pid" >/dev/null 2>&1 || echo "  the open transaction could not be started" >&2
}

kafka_topic() { # name partitions [--config key=value …]
  local name=$1 partitions=$2; shift 2
  kafka kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor "$KAFKA_RF" "$@" >/dev/null 2>&1 \
    || echo "  topic $name: not created" >&2
}

# Share groups, topics, access rules, records, the consumer groups and the open transaction.
kafka_seed_data() {
  say "preparing Kafka: share groups, topics, access rules"
  # Share groups are a cluster feature that has to be finalised; 2 adds dead-lettering to 1, so ask for the
  # higher and fall back. A cluster that supports neither simply has no share group below.
  kafka kafka-features.sh --bootstrap-server "$KAFKA_BOOTSTRAP" upgrade --feature share.version=2 >/dev/null 2>&1 \
    || kafka kafka-features.sh --bootstrap-server "$KAFKA_BOOTSTRAP" upgrade --feature share.version=1 >/dev/null 2>&1 \
    || echo "  share.version could not be finalised: no share group will be seeded" >&2

  kafka_topic orders 6
  kafka_topic payments 3
  kafka_topic inventory 3
  kafka_topic audit-log 1 --config retention.ms=86400000
  # The compacted one: keyed state, where only the last value of a key survives compaction.
  kafka_topic customer-state 3 --config cleanup.policy=compact --config min.cleanable.dirty.ratio=0.1 --config segment.ms=60000
  kafka_topic orders.dlq 1

  # Who may do what: a reader of orders, and a writer that is turned away from payments.
  kafka kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --add --allow-principal User:billing-service \
    --operation Read --operation Describe --topic orders --group billing >/dev/null 2>&1 || true
  kafka kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --add --allow-principal User:order-service \
    --operation Write --topic orders >/dev/null 2>&1 || true
  kafka kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --add --deny-principal User:contractor \
    --operation Write --topic payments >/dev/null 2>&1 || true

  say "producing Kafka records, keyed state and tombstones"
  kafka_produce orders 3000 600
  kafka_produce payments 1500 600
  kafka_produce inventory 800 400
  # Keys written more than once, so compaction has something to remove; a key with no value is a tombstone.
  # The null marker is a console-producer option of recent releases: where it is missing the tombstones are skipped.
  local round
  for round in 1 2 3; do
    printf 'c1:{"tier":"bronze","round":%s}\nc2:{"tier":"silver","round":%s}\nc3:{"tier":"gold","round":%s}\nc4:{"tier":"gold","round":%s}\n' \
      "$round" "$round" "$round" "$round" \
      | kafka kafka-console-producer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic customer-state \
          --property parse.key=true --property key.separator=: >/dev/null 2>&1 || true
  done
  printf 'c4:NULL\n' | kafka kafka-console-producer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic customer-state \
    --property parse.key=true --property key.separator=: --property null.marker=NULL >/dev/null 2>&1 || true

  say "starting the consumer groups"
  # billing keeps up and stays connected, so it has members and a lag near zero.
  $COMPOSE exec -d "${KAFKA_JVM[@]}" "$KAFKA_B" /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic orders --group billing --from-beginning >/dev/null 2>&1 || true
  # analytics read two hundred records and left: an empty group far behind, which is the one to reset.
  kafka kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic orders --group analytics \
    --from-beginning --max-messages 200 --timeout-ms 30000 >/dev/null 2>&1 || true
  # reporting read everything on payments once and left, so it is empty with a lag that grows with the traffic.
  kafka kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic payments --group reporting \
    --from-beginning --timeout-ms 15000 >/dev/null 2>&1 || true
  # state-reader reads the compacted topic with its tombstone, so a subscription exists to browse it by.
  kafka kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic customer-state --group state-reader \
    --from-beginning --timeout-ms 15000 >/dev/null 2>&1 || true
  # A share group: its members share the records of orders without owning partitions. Skipped where the image
  # has no share consumer or the feature could not be finalised.
  $COMPOSE exec -d "${KAFKA_JVM[@]}" "$KAFKA_C" /opt/kafka/bin/kafka-console-share-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic orders --group fulfilment >/dev/null 2>&1 || echo "  no share consumer in this image: no share group" >&2

  say "leaving one transaction open"
  open_hanging_transaction
  hanging_at=$(date +%s)
}

# What only a sweep wants: names and lag built to break a layout. A 200-character group and topic, and a group
# that is a million records behind.
kafka_seed_long() {
  local group topic
  group="qa.$(printf 'consumer-group-segment-%02d.' $(seq 1 8))terminal"
  topic="qa.$(printf 'topic-segment-%02d.' $(seq 1 8))terminal"
  say "building long Kafka names"
  kafka_topic "$topic" 12
  kafka_produce "$topic" 5000 1000
  kafka kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$topic" --group "$group" \
    --from-beginning --max-messages 50 --timeout-ms 20000 >/dev/null 2>&1 || true
}
