#!/usr/bin/env bash
#
# Fill the demo stack with traffic that looks like a working estate.
#
# Everything here goes through the product's own surfaces: the cluster is
# registered over Rahdar's REST API, and the messages are produced and consumed
# by the Artemis CLI that already ships inside the broker image. Nothing writes to
# `metric_sample`, `queue_snapshot` or any other table directly — a chart of
# invented samples tells you nothing about whether the product works, and it is
# the one thing a screenshot must not contain.
#
# The shape it builds, on purpose:
#   - several business-looking addresses at different rates;
#   - one address whose consumer is deliberately absent, so depth climbs and the
#     ingress/egress lines diverge — the picture an operator actually comes for;
#   - a real dead-letter backlog, produced by messages that were rejected;
#   - one stopped node, so the topology has a genuine warning rather than a staged
#     one.
#
# Metrics need real time to exist: tier B samples every 15s, so a credible 15m
# chart needs about that long. TRAFFIC_MINUTES controls it.
set -euo pipefail

RAHDAR=${RAHDAR:-http://localhost:8080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the password just dev-up printed}
# Once the admin has two-step verification (the first run enrols it), the secret the seed printed.
ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
TRAFFIC_MINUTES=${TRAFFIC_MINUTES:-20}
COMPOSE=${COMPOSE:?set COMPOSE to the docker compose invocation for the demo stack}

JAR=/var/lib/artemis-instance/bin/artemis
# The demo's clients run in the broker containers, one JVM each; without this they would take the
# broker's heap setting, and a dozen of them would not fit in a laptop's Docker VM.
CLIENT_JVM=(-e 'JAVA_ARGS_APPEND=-Xms16m -Xmx64m -XX:+UseSerialGC')
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

# shellcheck source=lib/signin.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/signin.sh"

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }

say "waiting for Rahdar to answer"
# `just demo` may have just recreated the container; the compose healthcheck gates
# the brokers, not Rahdar, so without this the first login races the startup and
# fails with a connection reset.
for _ in $(seq 1 60); do
  curl -fsS "$RAHDAR/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done

say "signing in to Rahdar"
# A fresh stack's admin password is one-time and its ADMIN role requires two-step verification: the sign-in
# changes the password to one the operator chose (never one invented here), enrols an authenticator app and
# prints its secret. Later runs need ADMIN_PASSWORD and ADMIN_TOTP_SECRET.
rahdar_sign_in "${NEW_ADMIN_PASSWORD:-}" NEW_ADMIN_PASSWORD || exit 1

say "registering the demo cluster"
# Re-registered rather than reused, so a second run cannot inherit a half-built
# registration from the first.
existing=$(api GET "/clusters?size=500" | python3 -c 'import json,sys; print(next((c["id"] for c in json.load(sys.stdin)["data"] if c["name"]=="demo"), ""))')
if [ -n "$existing" ]; then
  echo "removing the previous registration: $existing"
  api DELETE "/clusters/$existing" >/dev/null
fi
# Every node is seeded. Discovery finds the others from the first, but a node
# discovered through a cluster connection carries no management URL, so it cannot
# be scraped — it would render as an unreachable node in a cluster that is in fact
# healthy.
cluster=$(api POST /clusters -d '{
  "seedUrls": [
    "http://artemis-primary:8161/console/jolokia",
    "http://artemis-backup:8161/console/jolokia",
    "http://artemis-secondary:8161/console/jolokia",
    "http://artemis-secondary-backup:8161/console/jolokia",
    "http://artemis-tertiary:8161/console/jolokia",
    "http://artemis-tertiary-backup:8161/console/jolokia"
  ],
  "name": "demo",
  "description": "Six-node replication estate — three live/backup pairs",
  "credentials": {"username": "artemis", "password": "artemis"},
  "coreCredentials": {"username": "artemis", "password": "artemis"}
}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "registered: $cluster"

# Rates chosen so the four addresses are visibly different on one axis rather than
# one line and three flat ones.
produce() { # node address count sleep-ms
  $COMPOSE exec -T "${CLIENT_JVM[@]}" "$1" $JAR producer \
    --url tcp://localhost:61616 --user artemis --password artemis \
    --destination "queue://$2" --message-count "$3" --sleep "${4:-0}" \
    --message-size 512 >/dev/null 2>&1 || true
}

consume() { # node address count
  $COMPOSE exec -T "${CLIENT_JVM[@]}" "$1" $JAR consumer \
    --url tcp://localhost:61616 --user artemis --password artemis \
    --destination "queue://$2" --message-count "$3" --receive-timeout 2000 \
    --break-on-null >/dev/null 2>&1 || true
}

say "creating the addresses"
for a in ORDERS.inbound PAYMENTS.capture SHIPPING.events NOTIFICATIONS.email AUDIT.trail; do
  produce artemis-primary "$a" 1
  consume artemis-primary "$a" 1
done

say "building production routing: fan-out, wildcard, filters, diverts, capture, bridge"
# The shape of a real estate, so Flow has something to explain. Everything but the bridge goes
# through Rahdar's own audited API; creating something that already exists (a second run) is
# reported by Rahdar and is not a failure here. The bridge uses the broker's own management
# operations, because Rahdar shows bridges and never changes them.
dest_id() { # name: a destination's canonical id, its name percent-encoded
  python3 -c 'import sys, urllib.parse as u; print("artemis.address/" + u.quote(sys.argv[1], safe="-_.~"))' "$1"
}
new_queue() { # address name routing-type [extra attributes, as JSON members]
  printf '{"kind":"artemis.queue","name":"%s","destination":"%s","attributes":{"routingType":"%s","durable":true%s}}' \
    "$2" "$(dest_id "$1")" "$3" "${4:+,$4}"
}
new_address() { # name routing-types
  printf '{"kind":"artemis.address","name":"%s","attributes":{"routingTypes":"%s"}}' "$1" "$2"
}
routing() { # label path body
  local code
  code=$(api POST "/clusters/$cluster$2" -d "$3" -o /dev/null -w '%{http_code}') || true
  echo "  $1: HTTP $code"
}
routing "fan-out address"        /destinations "$(new_address ORDERS.events MULTICAST)"
for q in billing analytics audit; do
  routing "fan-out queue $q"     /subscriptions "$(new_queue ORDERS.events "ORDERS.events.$q" MULTICAST)"
done
# Orders parked for manual review: nothing consumes them, so the SQL Console always has JSON
# bodies to search, however long the demo has been running.
routing "held orders address"    /destinations "$(new_address ORDERS.held ANYCAST)"
routing "held orders queue"      /subscriptions "$(new_queue ORDERS.held ORDERS.held ANYCAST)"
routing "wildcard address"       /destinations "$(new_address 'ORDERS.#' MULTICAST)"
routing "wildcard queue"         /subscriptions "$(new_queue 'ORDERS.#' ORDERS.all.monitor MULTICAST)"
routing "filtered queue"         /subscriptions "$(new_queue SHIPPING.events SHIPPING.express ANYCAST '"filter":"express = true"')"
routing "legacy address"         /destinations "$(new_address LEGACY.orders ANYCAST)"
routing "exclusive divert"       /artemis/diverts '{"name":"legacy-erp-reroute","address":"LEGACY.orders","forwardingAddress":"ORDERS.inbound","exclusive":true,"routingType":"ANYCAST"}'
routing "copy divert"            /artemis/diverts '{"name":"orders-audit-copy","address":"ORDERS.inbound","forwardingAddress":"AUDIT.trail","exclusive":false,"routingType":"ANYCAST"}'
routing "priority address"       /subscriptions "$(new_queue SHIPPING.priority SHIPPING.priority ANYCAST '"autoCreateAddress":true')"
routing "filtered copy divert"   /artemis/diverts '{"name":"shipping-priority-copy","address":"SHIPPING.events","forwardingAddress":"SHIPPING.priority","exclusive":false,"filter":"priority > 6","routingType":"ANYCAST"}'
routing "capture subscription"   /sql/index '{"queuePattern":"PAYMENTS.capture","mode":"CAPTURE","retentionDays":1,"ringSize":10000}'

# A bridge from the primary pair to the secondary one: audit records archived in the other
# data centre. Runtime-deployed through the broker's management operations (not persisted).
jolokia() { # node json
  curl -sS -u artemis:artemis -H 'Origin: http://localhost' \
    -H 'Content-Type: application/json' -d "$2" "http://$($COMPOSE port "$1" 8161)/console/jolokia" >/dev/null 2>&1 || true
}
PRIMARY_MBEAN='org.apache.activemq.artemis:broker=\"primary\"'
jolokia artemis-primary "{\"type\":\"exec\",\"mbean\":\"$PRIMARY_MBEAN\",\"operation\":\"addConnector(java.lang.String,java.lang.String)\",\"arguments\":[\"secondary-connector\",\"tcp://artemis-secondary:61616\"]}"
jolokia artemis-primary "{\"type\":\"exec\",\"mbean\":\"$PRIMARY_MBEAN\",\"operation\":\"createBridge(java.lang.String)\",\"arguments\":[\"{\\\"name\\\":\\\"audit-archive-dc2\\\",\\\"queue-name\\\":\\\"AUDIT.trail\\\",\\\"forwarding-address\\\":\\\"AUDIT.archive\\\",\\\"static-connectors\\\":[\\\"secondary-connector\\\"],\\\"user\\\":\\\"artemis\\\",\\\"password\\\":\\\"artemis\\\",\\\"reconnect-attempts\\\":-1}\"]}"
echo "  bridge audit-archive-dc2: requested"

say "attaching long-lived applications, so Flow has clients to sample"
# The bursts below connect, send a few hundred messages and disconnect: real, but
# gone before Flow can sample the same client twice, and a rate needs two samples.
# These stay attached for the whole run and past it, each under its own client id
# (Artemis refuses a client id already in use), at steady rates. Detached inside the
# broker container, so the loop's `wait` below does not wait for them.
# A destination that names its scheme (topic:// for a multicast publisher) is used as given;
# anything else, including an address::queue subscriber queue, is a queue.
destination() { case "$1" in *://*) echo "$1" ;; *) echo "queue://$1" ;; esac; }

app() { # node kind client-id address sleep-ms
  local count=$(( (TRAFFIC_MINUTES + 30) * 60 * 1000 / $5 ))
  if [ "$2" = producer ]; then
    $COMPOSE exec -d "${CLIENT_JVM[@]}" "$1" $JAR producer --url tcp://localhost:61616 --user artemis --password artemis \
      --clientID "$3" --destination "$(destination "$4")" --message-count "$count" --sleep "$5" --message-size 512
  else
    $COMPOSE exec -d "${CLIENT_JVM[@]}" "$1" $JAR consumer --url tcp://localhost:61616 --user artemis --password artemis \
      --clientID "$3" --destination "$(destination "$4")" --message-count "$count" --sleep "$5" --receive-timeout 600000
  fi
}
# The busiest stream by far, a few hundred a second, so its lines stand out on Flow (width
# follows the square root of the rate); its two consumers together keep up with it.
app artemis-primary   producer order-service        ORDERS.inbound      2
app artemis-primary   consumer billing-service      ORDERS.inbound      3
app artemis-primary   consumer fraud-screening      ORDERS.inbound      6
app artemis-secondary producer payments-gateway     PAYMENTS.capture    80
app artemis-secondary consumer ledger-writer        PAYMENTS.capture    85
app artemis-primary   producer warehouse-events     SHIPPING.events     150
app artemis-primary   consumer shipment-tracker     SHIPPING.events     160
# Multicast fan-out: one publisher, three subscribers, each with its own copy.
app artemis-primary   producer order-events         topic://ORDERS.events 60
app artemis-primary   consumer billing-ledger       ORDERS.events::ORDERS.events.billing   70
app artemis-primary   consumer analytics-pipeline   ORDERS.events::ORDERS.events.analytics 90
app artemis-primary   consumer ops-monitor          ORDERS.#::ORDERS.all.monitor           120
# An old system still writing to its legacy address; the exclusive divert reroutes it all.
app artemis-primary   producer legacy-erp           LEGACY.orders       200
app artemis-primary   consumer express-courier      SHIPPING.events::SHIPPING.express      150
app artemis-primary   consumer priority-desk        SHIPPING.priority   300
# Produced on the primary, consumed only on the secondary: the cluster moves it across (ON_DEMAND).
app artemis-primary   producer inventory-feed       INVENTORY.sync      100
app artemis-secondary consumer inventory-sync       INVENTORY.sync      110
app artemis-secondary consumer audit-archiver       AUDIT.archive       250
# Sends and nobody reads: the queue backs up, and Flow marks it "no consumer".
app artemis-secondary producer notification-service NOTIFICATIONS.email 120

say "building a dead-letter backlog"
# Consumed and rolled back past the redelivery limit is how a message really
# reaches the DLQ; the CLI cannot do that, so these are produced onto the DLQ
# address directly. It is the same address the broker itself dead-letters onto.
produce artemis-primary DLQ 240 0
produce artemis-secondary DLQ 90 0

say "sending a few JSON orders, so the SQL Console has a body to search"
# The CLI's --message-size produces filler, and a body predicate over filler
# demonstrates nothing. These go through Rahdar's own send-message endpoint —
# the same audited path an operator uses — so the console's headline query,
# `body->>'orderId' = ...`, has something real to find.
for id in 4471 4472 4473 4474 4475 4476 4477 4478; do
  tenant=$([ $((id % 2)) -eq 0 ] && echo acme || echo globex)
  api POST "/clusters/$cluster/resources/artemis.queue/ORDERS.held/messages" -d "{
    \"kind\": \"TEXT\",
    \"durable\": true,
    \"body\": \"{\\\"orderId\\\": \\\"$id\\\", \\\"tenant\\\": \\\"$tenant\\\", \\\"total\\\": $((id % 97 + 12)).50, \\\"currency\\\": \\\"EUR\\\"}\",
    \"properties\": {\"tenant\": \"$tenant\", \"orderId\": \"$id\"}
  }" -o /dev/null -f || echo "  order $id: not sent" >&2
done

say "seeding governance: environments, a scoped role, and two operators"
# The administration screen is only legible with more than one account in it, and
# a scoped grant is the thing that is hard to picture from a description.
# Idempotent by name: unlike the cluster these are not torn down between runs,
# and a second run must not fail on the 409 the first one's rows now produce.
ensure() { # collection name-field name body -> id
  local path=$1 field=$2 name=$3 body=$4 found
  found=$(api GET "$path?size=500" | python3 -c "import json,sys; print(next((r['id'] for r in json.load(sys.stdin)['data'] if r['$field']=='$name'), ''))")
  if [ -n "$found" ]; then echo "$found"; return; fi
  api POST "$path" -d "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
}

env_prod=$(ensure /environments name production '{"name": "production", "sortOrder": 1}')
ensure /environments name staging '{"name": "staging", "sortOrder": 2}' >/dev/null

reader=$(ensure /roles name READER '{
  "name": "READER",
  "requiresMfa": false,
  "teamAssignable": false,
  "permissions": ["cluster:read", "subscription:read", "message:read"]
}')
operator=$(ensure /roles name OPERATOR '{
  "name": "OPERATOR",
  "requiresMfa": false,
  "teamAssignable": false,
  "permissions": ["cluster:read", "subscription:read", "subscription:pause", "message:read", "message:move"]
}')

# A random password per run, printed nowhere: these accounts exist to populate the
# administration screen, not to be logged into.
new_user() { # username -> id
  ensure /users username "$1" "{\"username\": \"$1\", \"email\": \"$1@example.com\", \"password\": \"$(head -c 18 /dev/urandom | base64 | tr -d '=+/')\"}"
}
oncall=$(new_user sre-oncall)
analyst=$(new_user support-analyst)

# Re-granting an existing grant is a conflict, not a failure of the run.
api POST "/users/$oncall/grants" -d "{\"roleId\": \"$operator\", \"scopeType\": \"ENVIRONMENT\", \"scopeId\": \"$env_prod\"}" >/dev/null || true
api POST "/users/$analyst/grants" -d "{\"roleId\": \"$reader\", \"scopeType\": \"CLUSTER\", \"scopeId\": \"$cluster\"}" >/dev/null || true

say "stopping one node, so the topology carries a real warning"
$COMPOSE stop artemis-secondary-backup

say "seeded. Rahdar: $RAHDAR — cluster $cluster"

# Last, so the screens above are complete while the traffic runs: a long demo
# (TRAFFIC_MINUTES in the hours) must not hold the users, the DLQ and the stopped
# node back until it ends.
say "driving traffic for ${TRAFFIC_MINUTES} minutes"
deadline=$(( $(date +%s) + TRAFFIC_MINUTES * 60 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  # Balanced: produced and consumed at roughly the same rate.
  produce artemis-primary ORDERS.inbound 400 2 &
  consume artemis-primary ORDERS.inbound 380 &
  produce artemis-secondary PAYMENTS.capture 200 5 &
  consume artemis-secondary PAYMENTS.capture 190 &
  produce artemis-primary SHIPPING.events 120 8 &
  consume artemis-primary SHIPPING.events 115 &

  # No consumer at all: depth climbs and the two throughput lines diverge, which
  # is the backlog signal the charts exist to show.
  produce artemis-secondary NOTIFICATIONS.email 150 6 &

  # Low, steady, never drained — a queue that is simply accumulating.
  produce artemis-primary AUDIT.trail 40 20 &
  wait
done

say "done"
