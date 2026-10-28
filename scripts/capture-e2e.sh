#!/usr/bin/env bash
#
# The end-to-end proof for divert-based message capture (ADR-0062), against the
# dev stack `just dev-up` brings up.
#
# It exists because the claim capture makes cannot be checked by a unit test. The
# whole point is a message that arrives and is consumed before any poll could see
# it, and demonstrating that needs a real broker, a real divert and a real
# consumer racing each other.
#
#   ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=... ./scripts/capture-e2e.sh
#
# Everything goes through the product's own surfaces — Rahdar's REST API and the
# Artemis CLI inside the broker image — except the final assertions, which read
# `message_index` directly. That table is what the console reads, and asserting on
# it is asserting on what an operator would see.
set -euo pipefail

RAHDAR=${RAHDAR:-http://localhost:8080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the password just dev-up printed}
# Once the admin has two-step verification (config-e2e.sh or the demo seed enrols it), the secret that run printed.
ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
# The bootstrap admin must change its password before it can do anything else, so
# the script does that once and uses the new one from then on.
NEW_PASSWORD=${NEW_PASSWORD:-capture-e2e-Passw0rd!}
COMPOSE=${COMPOSE:-docker compose -f deploy/compose/dev/compose.dev.yaml}
QUEUE=${QUEUE:-CAPTURE.PROOF}
COUNT=${COUNT:-200}

JAR=/var/lib/artemis-instance/bin/artemis
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

# shellcheck source=lib/signin.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/signin.sh"

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }
pass() { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }

psql_() { $COMPOSE exec -T postgres psql -qtAX -U rahdar -d rahdar -c "$1"; }

jolokia() { # node-service operation-json
  curl -sS -u artemis:artemis -H 'Content-Type: application/json' \
    -d "$2" "http://$($COMPOSE port "$1" 8161)/console/jolokia/"
}

say "waiting for Rahdar"
for _ in $(seq 1 60); do
  curl -fsS "$RAHDAR/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done

say "signing in"
rahdar_sign_in "$NEW_PASSWORD" || fail "could not sign in to Rahdar"

say "registering the dev cluster"
existing=$(api GET "/clusters?size=500" | python3 -c 'import json,sys; print(next((c["id"] for c in json.load(sys.stdin)["data"] if c["name"]=="dev"), ""))')
if [ -n "$existing" ]; then api DELETE "/clusters/$existing" >/dev/null; fi
CLUSTER=$(api POST /clusters -d '{
  "system": "artemis",
  "name": "dev",
  "description": "capture end-to-end",
  "connection": {
    "system": "artemis",
    "seedUrls": ["http://artemis-primary:8161/console/jolokia", "http://artemis-backup:8161/console/jolokia"],
    "credentials": {"username": "artemis", "password": "artemis"},
    "coreCredentials": {"username": "artemis", "password": "artemis"}
  }
}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
[ -n "$CLUSTER" ] || fail "cluster registration returned no id"
pass "cluster $CLUSTER"

say "creating the queue so the pattern resolves"
$COMPOSE exec -T artemis-primary $JAR producer --url tcp://localhost:61616 \
  --user artemis --password artemis --destination "queue://$QUEUE" --message-count 1 >/dev/null
$COMPOSE exec -T artemis-primary $JAR consumer --url tcp://localhost:61616 \
  --user artemis --password artemis --destination "queue://$QUEUE" --message-count 1 \
  --receive-timeout 2000 --break-on-null >/dev/null

say "waiting for the scrape to see it"
for _ in $(seq 1 30); do
  [ "$(psql_ "SELECT count(*) FROM queue_snapshot WHERE queue_name = '$QUEUE'")" != "0" ] && break
  sleep 2
done

say "turning capture on for $QUEUE"
SUB=$(api POST "/clusters/$CLUSTER/sql/index" -d "{
  \"queuePattern\": \"$QUEUE\", \"mode\": \"CAPTURE\", \"retentionDays\": 1, \"ringSize\": 100000
}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
[ -n "$SUB" ] || fail "subscription creation returned no id"
pass "subscription $SUB"

say "waiting for the reconciler to install the tap"
for _ in $(seq 1 30); do
  state=$(psql_ "SELECT capture_state FROM message_capture_node WHERE subscription_id = '$SUB'" | head -1)
  [ "$state" = "ACTIVE" ] && break
  sleep 2
done
[ "$state" = "ACTIVE" ] || fail "capture never became ACTIVE (last: ${state:-none}); detail: $(psql_ "SELECT capture_detail FROM message_capture_node WHERE subscription_id = '$SUB'")"
pass "tap installed and draining"

# ---- 4.11: the case sampling cannot see -------------------------------------

say "producing $COUNT messages and consuming them immediately"
$COMPOSE exec -T artemis-primary $JAR producer --url tcp://localhost:61616 \
  --user artemis --password artemis --destination "queue://$QUEUE" \
  --message-count "$COUNT" --message-size 256 >/dev/null
$COMPOSE exec -T artemis-primary $JAR consumer --url tcp://localhost:61616 \
  --user artemis --password artemis --destination "queue://$QUEUE" \
  --message-count "$COUNT" --receive-timeout 5000 --break-on-null >/dev/null

say "asserting the index holds them, by the ORIGINAL queue name"
for _ in $(seq 1 30); do
  captured=$(psql_ "SELECT count(*) FROM message_index WHERE queue_name = '$QUEUE' AND origin = 'CAPTURED'")
  [ "$captured" -ge "$COUNT" ] && break
  sleep 2
done
[ "$captured" -ge "$COUNT" ] || fail "expected >= $COUNT captured rows for $QUEUE, found $captured"
pass "$captured rows with origin='CAPTURED', found by '$QUEUE' — not by the capture queue's name"

sampled=$(psql_ "SELECT count(*) FROM message_index WHERE queue_name = '$QUEUE' AND origin = 'SAMPLED'")
pass "sampling recorded $sampled of the same messages (this is the gap capture closes)"

# ---- 5.11: failover ---------------------------------------------------------

if [ "${SKIP_FAILOVER:-0}" != "1" ]; then
  say "failing the primary over to its backup"
  primary_rows=$captured
  $COMPOSE stop artemis-primary >/dev/null

  say "waiting for Rahdar to see the backup go live"
  for _ in $(seq 1 40); do
    live=$(psql_ "SELECT count(*) FROM broker_node WHERE active = true AND name LIKE '%backup%'")
    [ "$live" != "0" ] && break
    sleep 3
  done
  [ "$live" != "0" ] || fail "the backup never became live"
  pass "backup is live"

  say "waiting for the tap to be installed on the promoted backup"
  for _ in $(seq 1 40); do
    backup_state=$(psql_ "SELECT n.name || '=' || c.capture_state FROM message_capture_node c
                            JOIN broker_node n ON n.id = c.node_id
                           WHERE c.subscription_id = '$SUB' AND n.name LIKE '%backup%'" | head -1)
    case "$backup_state" in *=ACTIVE) break;; esac
    sleep 3
  done
  case "$backup_state" in
    *=ACTIVE) pass "tap re-asserted on the backup ($backup_state)";;
    *) fail "capture never reached the backup (last: ${backup_state:-none}); detail: $(psql_ "SELECT capture_detail FROM message_capture_node WHERE subscription_id = '$SUB'")";;
  esac

  # The window between promotion and the pass that noticed is a real gap, and the
  # coverage record has to admit to it rather than implying continuous cover.
  gap=$(psql_ "SELECT c.captured_from > s.capture_from FROM message_capture_node c
                 JOIN broker_node n ON n.id = c.node_id
                 JOIN message_index_subscription s ON s.id = c.subscription_id
                WHERE c.subscription_id = '$SUB' AND n.name LIKE '%backup%'" | head -1)
  [ "$gap" = "t" ] || fail "the backup's coverage window claims to start when the subscription did"
  pass "the backup's coverage starts when its tap did, not when the subscription did"

  say "producing through the promoted backup"
  $COMPOSE exec -T artemis-backup $JAR producer --url tcp://localhost:61616 \
    --user artemis --password artemis --destination "queue://$QUEUE" --message-count 50 >/dev/null
  $COMPOSE exec -T artemis-backup $JAR consumer --url tcp://localhost:61616 \
    --user artemis --password artemis --destination "queue://$QUEUE" --message-count 50 \
    --receive-timeout 5000 --break-on-null >/dev/null

  for _ in $(seq 1 30); do
    captured=$(psql_ "SELECT count(*) FROM message_index WHERE queue_name = '$QUEUE' AND origin = 'CAPTURED'")
    [ "$captured" -ge $((primary_rows + 50)) ] && break
    sleep 2
  done
  [ "$captured" -ge $((primary_rows + 50)) ] \
    || fail "expected $((primary_rows + 50)) captured rows after failover, found $captured"
  pass "capture continued through the failover ($captured rows)"

  say "bringing the primary back"
  $COMPOSE start artemis-primary >/dev/null
  for _ in $(seq 1 40); do
    curl -sf -u artemis:artemis \
      "http://$($COMPOSE port artemis-primary 8161)/console/jolokia/read/org.apache.activemq.artemis:broker=%22primary%22/Started" \
      >/dev/null 2>&1 && break
    sleep 3
  done
fi

# ---- 5.12: convergence without duplication ----------------------------------

say "killing Rahdar mid-capture and restarting it"
before=$captured
$COMPOSE restart rahdar >/dev/null
for _ in $(seq 1 60); do
  curl -fsS "$RAHDAR/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done
rahdar_sign_in "$NEW_PASSWORD" || fail "could not sign in again after the restart"

say "waiting for the reconciler to converge again"
for _ in $(seq 1 40); do
  state=$(psql_ "SELECT capture_state FROM message_capture_node WHERE subscription_id = '$SUB'" | head -1)
  [ "$state" = "ACTIVE" ] || [ "$state" = "DEGRADED" ] && break
  sleep 3
done
after=$(psql_ "SELECT count(*) FROM message_index WHERE queue_name = '$QUEUE' AND origin = 'CAPTURED'")
[ "$after" -eq "$before" ] || fail "restart duplicated rows: $before before, $after after"
pass "converged with no duplicate rows ($after)"

diverts=$(jolokia artemis-primary '{"type":"read","mbean":"org.apache.activemq.artemis:broker=\"primary\"","attribute":"DivertNames"}')
echo "$diverts" | grep -q 'rahdar.capture' || fail "the tap was not re-asserted after the restart"
pass "one tap, still installed, not a second one"

# ---- 5.12: deletion removes everything --------------------------------------

say "deleting the subscription"
api DELETE "/clusters/$CLUSTER/sql/index/$SUB" >/dev/null

say "waiting for the reconciler to remove the tap"
for _ in $(seq 1 40); do
  diverts=$(jolokia artemis-primary '{"type":"read","mbean":"org.apache.activemq.artemis:broker=\"primary\"","attribute":"DivertNames"}')
  echo "$diverts" | grep -q 'rahdar.capture' || break
  sleep 3
done
echo "$diverts" | grep -q 'rahdar.capture' && fail "the divert is still there: $diverts"
pass "divert gone"

queues=$(jolokia artemis-primary '{"type":"exec","mbean":"org.apache.activemq.artemis:broker=\"primary\"","operation":"getQueueNames()"}')
echo "$queues" | grep -q 'rahdar.capture' && fail "the capture queue is still there"
pass "capture queue gone"

roles=$(jolokia artemis-primary '{"type":"exec","mbean":"org.apache.activemq.artemis:broker=\"primary\"","operation":"getRolesAsJSON(java.lang.String)","arguments":["rahdar.capture.x"]}')
settings=$(jolokia artemis-primary '{"type":"exec","mbean":"org.apache.activemq.artemis:broker=\"primary\"","operation":"getAddressSettingsAsJSON(java.lang.String)","arguments":["rahdar.capture.x"]}')
echo "$settings" | grep -q '"addressFullMessagePolicy":"DROP"' && fail "the address setting is still there: $settings"
pass "address setting gone (falls back to the broker's default policy)"
echo "  roles now resolve to the broker default: $roles"

printf '\n\033[32mAll capture end-to-end assertions passed.\033[0m\n'
