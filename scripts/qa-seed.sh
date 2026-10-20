#!/usr/bin/env bash
#
# Fill a QA stack with long and large content, for a UI sweep.
#
# demo-seed.sh makes a stack look like a working estate; this makes it awkward on purpose, because
# layout breaks on the value nobody typed by hand: a queue name past 120 characters, a 200-character
# address, a dead-letter message with a 16 KB body and 400-character headers, an alert rule whose name
# fills a line twice over. Everything goes through Rahdar's own REST API, as demo-seed.sh does, and
# nothing writes to a table directly.
#
# What it builds:
#   - the cluster the sweep opens, named "demo" (registered over the dev stack's two brokers when
#     demo-seed.sh has not already registered the six-node one);
#   - 60 queues: three with names past 120 characters, one on a 200-character address, anycast and
#     multicast, and the DLQ and expiry families;
#   - 18 messages with long headers, properties and bodies on the dead-letter and expiry queues, and 250 small
#     ones on one order queue, for a query with many rows;
#   - an environment, channels, alert rules, masking rules, API tokens, a role and a user with long names;
#   - `qa-reader`, a read-only account whose password is known, for the permission-denied captures;
#   - three teams: one owning patterns with members and a share, one with a long name, and one with nothing.
#
# Not built: a signed plugin. Installing one needs a step-up and a trusted publisher key, which is
# an administrator's decision in the UI rather than something a seed script should take.
#
# Idempotent by name; a second run reports what already exists and changes nothing.
set -euo pipefail

RAHDAR=${RAHDAR:?set RAHDAR to the isolated stack, e.g. http://127.0.0.1:18080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the admin password}
# The built-in ADMIN role requires a second factor (ADR-0143): the secret `just qa-up` enrolled.
ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
# What qa-reader's password becomes; the sweep signs in with it.
QA_USER_PASSWORD=${QA_USER_PASSWORD:?set QA_USER_PASSWORD to the password qa-reader should have}

COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

# shellcheck source=lib/signin.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/signin.sh"

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }

# Names built to be long, in one place so the sweep's routes can name the same ones.
LONG_ADDRESS="qa.long-address.$(printf 'segment-%02d.' $(seq 1 16))terminal"
LONG_QUEUES=(
  qa.reconciliation.nightly-settlement-batch.counterparty-instructions.eu-west-central.priority-high.retry-after-manual-review.v2
  qa.customer-onboarding.identity-verification.document-check.manual-escalation.regional-compliance-queue-for-the-nordics.awaiting-second-reviewer-signoff
  qa.warehouse.inbound-shipments.advance-ship-notices.carrier-integration.late-arrival-exceptions.requires-operator-attention.dock-door-assignments.rev-3
)
# repeat TEXT N → TEXT N times
repeat() { python3 -c 'import sys; print(sys.argv[1] * int(sys.argv[2]), end="")' "$1" "$2"; }

failed=0
# post LABEL PATH BODY [optional]: a refusal of anything not marked optional fails the run at the end,
# after everything else has been tried.
post() {
  local label=$1 path=$2 body=$3 optional=${4:-} out code
  out=$(mktemp)
  code=$(api POST "$path" -d "$body" -o "$out" -w '%{http_code}') || code=000
  case $code in
    2??) ;;
    409) echo "  exists: $label" ;;
    *)
      echo "  HTTP $code: $label: $(head -c 300 "$out")" >&2
      [ -n "$optional" ] || failed=$((failed + 1))
      ;;
  esac
  rm -f "$out"
}

# find_id PATH FIELD NAME → the id of the row whose FIELD is NAME; empty when there is none.
find_id() {
  api GET "$1?size=500" | python3 -c "import json,sys; print(next((r['id'] for r in json.load(sys.stdin)['data'] if r['$2']=='$3'), ''))"
}

# ensure PATH FIELD NAME BODY → id, creating the row when it is missing.
ensure() {
  local id
  id=$(find_id "$1" "$2" "$3")
  if [ -z "$id" ]; then
    id=$(api POST "$1" -d "$4" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  fi
  echo "$id"
}

say "waiting for Rahdar to answer"
for _ in $(seq 1 60); do
  curl -fsS "$RAHDAR/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done

say "signing in to Rahdar"
rahdar_sign_in "" || exit 1

say "finding the cluster the sweep opens"
cluster=$(find_id /clusters name demo)
if [ -z "$cluster" ]; then
  cluster=$(api POST /clusters -d '{
    "seedUrls": ["http://artemis-primary:8161/console/jolokia"],
    "name": "demo",
    "description": "Primary and backup, registered by the QA seed from one URL",
    "credentials": {"username": "artemis", "password": "artemis"},
    "coreCredentials": {"username": "artemis", "password": "artemis"},
    "adopt": true
  }' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  echo "registered: $cluster"
else
  echo "found: $cluster"
fi

say "creating 60 queues"
queue() { # address name routing-type
  post "queue $2" "/clusters/$cluster/subscriptions" \
    "{\"kind\":\"artemis.queue\",\"name\":\"$2\",\"destination\":\"artemis.address/$1\",\"attributes\":{\"routingType\":\"$3\",\"durable\":true,\"autoCreateAddress\":true}}"
}
# 1 on a 200-character address, 3 with names past 120 characters.
queue "$LONG_ADDRESS" "$LONG_ADDRESS" ANYCAST
for name in "${LONG_QUEUES[@]}"; do queue "$name" "$name" ANYCAST; done
# 18 multicast subscribers: six fan-out addresses with three queues each.
for n in 1 2 3 4 5 6; do
  post "address qa.fanout.$n" "/clusters/$cluster/destinations" "{\"kind\":\"artemis.address\",\"name\":\"qa.fanout.$n\",\"attributes\":{\"routingTypes\":\"MULTICAST\"}}"
  for sub in a b c; do queue "qa.fanout.$n" "qa.fanout.$n.sub-$sub" MULTICAST; done
done
# The DLQ family and the expiry family: DLQ and ExpiryQueue come with the brokers' configuration.
for name in DLQ DLQ.qa.orders DLQ.qa.payments DLQ.qa.shipping DLQ.qa.notifications \
            ExpiryQueue EXPIRY.qa.orders EXPIRY.qa.payments EXPIRY.qa.shipping; do
  queue "$name" "$name" ANYCAST
done
# 29 anycast queues across four regions fill it to 60.
regions=(eu-west eu-central us-east ap-south)
for i in $(seq 1 29); do
  name=$(printf 'qa.orders.%s.%03d' "${regions[$((i % 4))]}" "$i")
  queue "$name" "$name" ANYCAST
done

say "sending long messages to the dead-letter and expiry queues"
# 12 dead-lettered and 6 expired, each with a body of about 16 KB, headers and properties of hundreds of characters.
long_message() { # queue n
  python3 - "$1" "$2" <<'PY' | api POST "/clusters/$cluster/resources/artemis.queue/$1/messages" --data-binary @- -o /dev/null -w '%{http_code}' | grep -q '^2' \
    || { echo "  message $2 to $1 was refused" >&2; failed=$((failed + 1)); }
import json, sys
queue, n = sys.argv[1], int(sys.argv[2])
lines = [{"sku": f"SKU-{n:03d}-{i:04d}", "description": "Replacement part, long description " * 3, "quantity": i % 7 + 1} for i in range(90)]
body = json.dumps({"orderId": f"ORD-{n:05d}", "tenant": "acme-global-industrial-holdings-international", "lines": lines,
                   "failure": "java.lang.IllegalStateException: " + "downstream settlement service rejected the instruction; " * 8})
print(json.dumps({
    "kind": "TEXT", "durable": True, "body": body,
    "headers": {"correlationId": "corr-" + f"{n:03d}-" + "c" * 400, "replyTo": "reply.to." + "r" * 200, "groupId": "group-" + "g" * 150},
    "properties": {
        "failureReason": "Exceeded the redelivery limit after repeated processing errors; " * 8,
        "originalAddress": queue,
        "xLong" + "PropertyName" * 5 + str(n): "v" * 400,
    },
}))
PY
}
for n in $(seq 1 12); do long_message DLQ "$n"; done
for n in $(seq 1 6); do long_message ExpiryQueue "$n"; done

say "sending 250 small messages to one queue, for a query with many rows"
for n in $(seq 1 250); do
  code=$(api POST "/clusters/$cluster/resources/artemis.queue/qa.orders.eu-central.001/messages" -o /dev/null -w '%{http_code}' \
    -d "{\"kind\":\"TEXT\",\"durable\":true,\"body\":\"{\\\"orderId\\\":\\\"ORD-$n\\\",\\\"amount\\\":$((n * 7 % 500))}\",\"properties\":{\"seq\":$n}}") || code=000
  case $code in 2??) ;; *) failed=$((failed + 1)) ;; esac
done

say "creating an environment, channels and alert rules with long names"
post "environment" /environments "{\"name\":\"qa-environment-with-an-unreasonably-long-name-$(repeat x 80)\",\"sortOrder\":10}" optional
secret=$(openssl rand -base64 24)
bound=
for n in 1 2 3; do
  name="qa-channel-$n-$(repeat 'notification-channel-' 6)"
  id=$(find_id /channels name "$name")
  if [ -z "$id" ]; then
    id=$(api POST /channels -d "{\"name\":\"$name\",\"kind\":\"WEBHOOK\",\"enabled\":true,
      \"config\":\"{\\\"url\\\":\\\"https://alerts.example.com/hooks/$(repeat 'segment/' 10)$n\\\"}\",\"secret\":\"$secret\"}" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))') || id=
  fi
  if [ -n "$id" ]; then bound+="\"$id\","; else echo "  channel $n was refused" >&2; fi
done
conditions=(NODE_DOWN CLUSTER_DEGRADED REPLICATION_BEHIND CLOCK_SKEW CONFIG_DRIFT SETUP_RISK SPLIT_BRAIN)
for n in 1 2 3 4 5 6 7; do
  severity=$([ $((n % 2)) -eq 0 ] && echo CRITICAL || echo WARNING)
  post "alert rule $n" "/clusters/$cluster/alerts/rules" \
    "{\"name\":\"qa-alert-rule-$n-$(repeat 'fires-when-the-condition-holds-' 6)\",\"kind\":\"STATE\",\"stateCondition\":\"${conditions[$((n - 1))]}\",
      \"forSeconds\":60,\"severity\":\"$severity\",\"enabled\":true,\"channelIds\":[${bound%,}]}" optional
done

say "creating masking rules and API tokens with long names"
post "masking rule" /governance/rules \
  "{\"addressPattern\":\"qa.long-pattern.$(repeat 'segment.' 28)*\",\"target\":\"PROPERTY\",\"selector\":\"$(repeat 'nationalIdentifier' 14)\",\"dataClass\":\"NATIONAL_ID\",\"action\":\"REDACT\",\"enabled\":true}" optional
post "masking rule" /governance/rules \
  '{"addressPattern":"PAYMENTS.*","target":"BODY_PATH","selector":"$.card.number","dataClass":"PAN","action":"PARTIAL","enabled":true}' optional
expires=$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
for n in 1 2 3; do
  post "API token $n" /tokens \
    "{\"name\":\"qa-token-$n-$(repeat 'integration-token-' 8)\",\"expiresAt\":\"$expires\",\"grants\":[{\"action\":\"cluster:read\",\"scopeType\":\"GLOBAL\"}]}" optional
done

say "creating a role and users"
reader=$(ensure /roles name READER '{"name":"READER","requiresMfa":false,"teamAssignable":false,"permissions":["cluster:read","subscription:read","message:read"]}')
long_role="qa-role-with-a-name-that-keeps-going-$(repeat 'and-going-' 8)"
long_role_id=$(ensure /roles name "$long_role" "{\"name\":\"$long_role\",\"requiresMfa\":false,\"teamAssignable\":false,\"permissions\":[\"cluster:read\"]}")
long_user="qa-operator-with-an-unreasonably-long-username-$(repeat 'abcdefghij' 4)"
if [ -z "$(find_id /users username "$long_user")" ]; then
  api POST /users -d "{\"username\":\"$long_user\",\"email\":\"$long_user@an-equally-long-domain-name.example.com\",\"password\":\"$(openssl rand -hex 16)\"}" -o /dev/null -w '  user: HTTP %{http_code}\n'
fi
long_user_id=$(find_id /users username "$long_user")
[ -z "$long_user_id" ] || api POST "/users/$long_user_id/grants" -d "{\"roleId\":\"$long_role_id\",\"scopeType\":\"CLUSTER\",\"scopeId\":\"$cluster\"}" -o /dev/null || true

if [ -z "$(find_id /users username qa-reader)" ]; then
  initial=$(openssl rand -hex 16)
  api POST /users -d "{\"username\":\"qa-reader\",\"email\":\"qa-reader@example.com\",\"password\":\"$initial\"}" >/dev/null
  # A new account must change its one-time password before anything else: do it here, with the same
  # sign-in the other scripts use, so the sweep can sign in with the password it was given.
  (
    COOKIES=$(mktemp)
    trap 'rm -f "$COOKIES"' EXIT
    ADMIN_USER=qa-reader ADMIN_PASSWORD=$initial
    rahdar_sign_in "$QA_USER_PASSWORD" QA_USER_PASSWORD
  ) || failed=$((failed + 1))
fi
reader_id=$(find_id /users username qa-reader)
api POST "/users/$reader_id/grants" -d "{\"roleId\":\"$reader\",\"scopeType\":\"CLUSTER\",\"scopeId\":\"$cluster\"}" -o /dev/null || true

say "creating teams"
# One team that owns several patterns on the cluster, with members and a share; one with a long name that is
# shared with; and one with nothing yet, for the empty tabs.
team_operator=$(api GET /teams/lookups/roles | python3 -c "import json,sys; print(next(r['id'] for r in json.load(sys.stdin)['roles'] if r['name']=='TEAM_OPERATOR'))")
orders=$(ensure /teams name qa-orders '{"name":"qa-orders"}')
long_team="qa-team-for-the-regional-compliance-and-$(repeat 'onboarding-' 5)desk"
long_team_id=$(ensure /teams name "$long_team" "{\"name\":\"$long_team\"}")
ensure /teams name qa-team-empty '{"name":"qa-team-empty"}' >/dev/null
post "orders: reconciliation queues" "/teams/$orders/patterns" "{\"clusterId\":\"$cluster\",\"kind\":\"QUEUE\",\"pattern\":\"qa.reconciliation.#\"}"
post "orders: long address" "/teams/$orders/patterns" "{\"clusterId\":\"$cluster\",\"kind\":\"BOTH\",\"pattern\":\"qa.long-address.#\"}"
post "long team: onboarding" "/teams/$long_team_id/patterns" "{\"clusterId\":\"$cluster\",\"kind\":\"QUEUE\",\"pattern\":\"qa.customer-onboarding.#\"}"
post "orders: member $long_user" "/teams/$orders/members" "{\"principalType\":\"USER\",\"userId\":\"$long_user_id\",\"roleId\":\"$team_operator\"}"
post "orders: member qa-reader" "/teams/$orders/members" "{\"principalType\":\"USER\",\"userId\":\"$reader_id\",\"roleId\":\"$team_operator\"}"
post "orders shares with the long team" "/teams/$orders/shares" "{\"targetTeamId\":\"$long_team_id\",\"clusterId\":\"$cluster\",\"kind\":\"QUEUE\",\"pattern\":\"qa.reconciliation.nightly-settlement-batch.#\",\"roleId\":\"$team_operator\"}" optional

if [ "$failed" -gt 0 ]; then
  say "$failed required step(s) failed; the content above is incomplete"
  exit 1
fi
say "done. Rahdar: $RAHDAR, cluster $cluster"
