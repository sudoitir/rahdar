#!/usr/bin/env bash
#
# Seed the load test's stack: clusters, queues, messages, audit records and accounts.
#
# Runs from scripts/load.sh, which exports RAHDAR, COMPOSE, ADMIN_PASSWORD (the one-time password the first boot
# printed), NEW_ADMIN_PASSWORD, SEED_DIR and the LOAD_* sizes of the profile. Everything Rahdar can do goes through
# its REST API; queues and messages go through the brokers' own management and CLI, because a real estate's
# thousands of queues were never created one audited call at a time. Nothing writes to a table directly.
#
# Writes $SEED_DIR/users.csv (the accounts of the UI and stream scenarios), flood.csv (the one account the heavy
# scenarios share, so that its per-user limit is reached) and clusters.csv (cluster id, name, a queue name).
set -euo pipefail

RAHDAR=${RAHDAR:?}
COMPOSE=${COMPOSE:?}
SEED_DIR=${SEED_DIR:?}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?}
export ADMIN_USER ADMIN_PASSWORD ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
LOAD_BROKERS=${LOAD_BROKERS:?}
LOAD_CLUSTERS=${LOAD_CLUSTERS:?}
LOAD_NODES_PER_CLUSTER=${LOAD_NODES_PER_CLUSTER:?}
LOAD_QUEUES=${LOAD_QUEUES:?}
LOAD_MESSAGES=${LOAD_MESSAGES:?}
LOAD_AUDIT_ROWS=${LOAD_AUDIT_ROWS:?}
LOAD_ACCOUNTS=${LOAD_ACCOUNTS:?}

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT
# shellcheck source=lib/signin.sh
. "$here/lib/signin.sh"

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

mkdir -p "$SEED_DIR"
rahdar_sign_in "${NEW_ADMIN_PASSWORD:-}" NEW_ADMIN_PASSWORD || exit 1
# The same run's later steps and the load test sign the shared accounts in with this one password.
LOAD_PASSWORD=$(openssl rand -hex 16)

say "creating $LOAD_QUEUES queues on each of $LOAD_BROKERS brokers"
jar=/var/lib/artemis-instance/bin/artemis
i=0
while [ "$i" -lt "$LOAD_BROKERS" ]; do
  hostport=$($COMPOSE port "broker-$i" 8161 | head -1)
  python3 "$here/load-queues.py" "http://127.0.0.1:${hostport##*:}/console/jolokia" "$LOAD_QUEUES"
  i=$((i + 1))
done

say "producing $LOAD_MESSAGES messages on each broker"
# Spread over the first ten queues, 512 bytes each, so a queue holds a backlog worth browsing and querying.
spread=10
[ "$LOAD_QUEUES" -ge "$spread" ] || spread=$LOAD_QUEUES
per_queue=$((LOAD_MESSAGES / spread))
i=0
while [ "$i" -lt "$LOAD_BROKERS" ] && [ "$per_queue" -gt 0 ]; do
  q=0
  while [ "$q" -lt "$spread" ]; do
    $COMPOSE exec -T -e 'JAVA_ARGS_APPEND=-Xms16m -Xmx64m -XX:+UseSerialGC' "broker-$i" "$jar" producer \
      --url tcp://localhost:61616 --user artemis --password artemis \
      --destination "queue://load.q.$q" --message-count "$per_queue" --message-size 512 >/dev/null 2>&1 || true
    q=$((q + 1))
  done
  i=$((i + 1))
done

say "registering $LOAD_CLUSTERS clusters of $LOAD_NODES_PER_CLUSTER nodes"
# Cluster c takes its own brokers, c x nodes onward: Rahdar refuses to register a broker in two clusters.
printf 'clusterId,clusterName,queue\n' > "$SEED_DIR/clusters.csv"
cluster_ids=()
c=0
while [ "$c" -lt "$LOAD_CLUSTERS" ]; do
  seeds=""
  n=0
  while [ "$n" -lt "$LOAD_NODES_PER_CLUSTER" ]; do
    b=$((c * LOAD_NODES_PER_CLUSTER + n))
    seeds="$seeds${seeds:+,}\"http://broker-$b:8161/console/jolokia\""
    n=$((n + 1))
  done
  name="load-$c"
  reply=$(api POST /clusters -d "{\"seedUrls\":[$seeds],\"name\":\"$name\",\"description\":\"Load test cluster $c\",\"credentials\":{\"username\":\"artemis\",\"password\":\"artemis\"},\"coreCredentials\":{\"username\":\"artemis\",\"password\":\"artemis\"}}")
  id=$(json 'd["id"]' <<<"$reply" 2>/dev/null) || { echo "registering $name failed: $reply" >&2; exit 1; }
  echo "  $name: $id"
  cluster_ids+=("$id")
  q=0
  while [ "$q" -lt 10 ] && [ "$q" -lt "$LOAD_QUEUES" ]; do
    printf '%s,%s,load.q.%d\n' "$id" "$name" "$q" >> "$SEED_DIR/clusters.csv"
    q=$((q + 1))
  done
  c=$((c + 1))
done

say "waiting for Rahdar to read the queues"
want=$LOAD_QUEUES
# The next tier-C tick (15 s in the load stack) walks every queue of every node.
tick=${LOAD_TIER_C%s}
tries=$((tick * 2 / 3 + 40))
for id in "${cluster_ids[@]}"; do
  for _ in $(seq 1 "$tries"); do
    have=$(api GET "/clusters/$id/subscriptions?size=1" | json 'd["count"]' 2>/dev/null || echo 0)
    [ "$have" -ge "$want" ] && break
    sleep 3
  done
  echo "  $id: $have of $want queues"
done

say "writing $LOAD_AUDIT_ROWS audit records through the API"
# Each queue Rahdar creates is an audited mutation, so the audit export has rows to stream.
n=0
while [ "$n" -lt "$LOAD_AUDIT_ROWS" ]; do
  id=${cluster_ids[$((n % LOAD_CLUSTERS))]}
  api POST "/clusters/$id/subscriptions" -o /dev/null \
    -d "{\"kind\":\"artemis.queue\",\"name\":\"load.audit.$n\",\"destination\":\"artemis.address/load.audit.$n\",\"attributes\":{\"routingType\":\"ANYCAST\",\"durable\":true,\"autoCreateAddress\":true}}" || true
  n=$((n + 1))
done

say "creating $LOAD_ACCOUNTS accounts and the flood account"
role=$(api POST /roles -d '{"name":"LOAD","requiresMfa":false,"teamAssignable":false,"permissions":["cluster:read","queue:read","address:read","message:read","connection:read","alert:read","audit:export","data:read"]}' | json 'd["id"]')
printf 'username,password\n' > "$SEED_DIR/users.csv"
printf 'username,password\n' > "$SEED_DIR/flood.csv"
make_account() { # username file
  local name=$1 file=$2 initial uid
  initial=$(openssl rand -hex 16)
  uid=$(api POST /users -d "{\"username\":\"$name\",\"email\":\"$name@example.com\",\"password\":\"$initial\"}" | json 'd["id"]')
  api POST "/users/$uid/grants" -d "{\"roleId\":\"$role\",\"scopeType\":\"GLOBAL\"}" -o /dev/null
  # A new account changes its one-time password before anything else; the sign-in helper does it.
  (
    COOKIES=$(mktemp)
    trap 'rm -f "$COOKIES"' EXIT
    ADMIN_USER=$name ADMIN_PASSWORD=$initial
    rahdar_sign_in "$LOAD_PASSWORD" LOAD_PASSWORD
  )
  printf '%s,%s\n' "$name" "$LOAD_PASSWORD" >> "$file"
}
make_account load-flood "$SEED_DIR/flood.csv"
a=1
while [ "$a" -le "$LOAD_ACCOUNTS" ]; do
  make_account "$(printf 'load-%03d' "$a")" "$SEED_DIR/users.csv"
  a=$((a + 1))
done
echo "seeded: $LOAD_CLUSTERS clusters, $LOAD_BROKERS brokers, $LOAD_QUEUES queues each, $LOAD_ACCOUNTS accounts"
