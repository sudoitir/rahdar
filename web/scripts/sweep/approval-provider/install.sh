#!/usr/bin/env bash
# Install an approval provider into the isolated QA stack, so the sweep can photograph held requests.
#
#   RAHDAR=http://127.0.0.1:18480 web/scripts/sweep/approval-provider/install.sh     (after `just qa-up`)
#
# Builds the `qa-approvals` plugin from this directory against `target/classes` (run `./mvnw package` first),
# allows unverified plugins and installs it: it holds every gated operation of anyone but `admin`, who decides
# them. Then it creates `qa-approver`, a second approver (one approver locks the gate), and `qa-requester`, a newer account than `admin` (an approver's account must be older than
# the requester's) that operates the `qa-orders` team's queues and may change Rahdar's settings, and saves its
# browser session to `web/.sweep/auth/requester.json` for the sweep's requester captures. Last, it leaves the
# requester with a request in each state the sweep photographs: held (a settings change and a purge), succeeded,
# rejected, cancelled, refused (its queue is gone when it is approved) and, a minute later, expired.
#
# Reads the credentials `just qa-up` saved to web/.sweep/auth/credentials.env.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
RAHDAR=${RAHDAR:?set RAHDAR to the isolated stack, e.g. http://127.0.0.1:18480}
AUTH=$REPO/web/.sweep/auth
# shellcheck source=/dev/null
. "$AUTH/credentials.env"
export RAHDAR ADMIN_USER=admin ADMIN_PASSWORD ADMIN_TOTP_SECRET
REQUESTER_PASSWORD=${REQUESTER_PASSWORD:-$QA_USER_PASSWORD}

BUILD=$(mktemp -d)
COOKIES=$BUILD/admin.cookies
trap 'rm -rf "$BUILD"' EXIT
# shellcheck source=../../../../scripts/lib/signin.sh
. "$REPO/scripts/lib/signin.sh"

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }

say "building the plugin"
[ -d "$REPO/target/classes" ] || { echo "no target/classes: run ./mvnw package first" >&2; exit 1; }
(cd "$REPO" && ./mvnw -o -q dependency:build-classpath -Dmdep.outputFile="$BUILD/classpath" >/dev/null)
mkdir -p "$BUILD/classes/META-INF/rahdar"
javac --release 25 -d "$BUILD/classes" -cp "$REPO/target/classes:$(cat "$BUILD/classpath")" \
  $(find "$HERE/src" -name '*.java')
# The plugin names the contract it is built against; read it from the Rahdar that was built, so it never goes stale.
contract=$(sed -n 's/.*VERSION = \([0-9]*\);.*/\1/p' "$REPO/src/main/java/io/github/sudoitir/rahdar/kernel/plugin/Contract.java")
sed "s/\"contract\": [0-9]*/\"contract\": $contract/" "$HERE/plugin.json" > "$BUILD/classes/META-INF/rahdar/plugin.json"
jar --create --file "$BUILD/qa-approvals.jar" -C "$BUILD/classes" .

say "installing it"
# A fresh sign-in is the step-up the trust policy and the activation ask for.
rahdar_sign_in "" || exit 1
if api GET /gate/status | grep -q '"attached":true'; then
  echo "already installed"
else
  api PUT /admin/plugins/trust-policy -f -o /dev/null -d '{"allowUnverified":true}'
  sha=$(curl -sS -f -b "$COOKIES" -c "$COOKIES" -X PUT "$RAHDAR/api/v1/admin/plugins/upload" \
    -H 'Content-Type: application/octet-stream' -H "X-XSRF-TOKEN: $(csrf)" --data-binary @"$BUILD/qa-approvals.jar" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha256"])')
  api POST "/admin/plugins/uploads/$sha/activate?acknowledge=true" -f -o /dev/null
  for _ in $(seq 1 60); do
    api GET /gate/status | grep -q '"attached":true' && break
    sleep 2
  done
fi
api GET /gate/status; echo

say "creating qa-approver and qa-requester"
find_id() {
  api GET "$1?size=500" | python3 -c "import json,sys; print(next((r['id'] for r in json.load(sys.stdin)['data'] if r['$2']=='$3'), ''))"
}
role=$(find_id /roles name QA_SETTINGS)
[ -n "$role" ] || role=$(api POST /roles -d '{"name":"QA_SETTINGS","requiresMfa":false,"teamAssignable":false,"permissions":["settings:read","settings:write","cluster:read","subscription:read","message:read"]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
# A user's first sign-in with the password it was given sets the one it keeps.
create_user() { # name password
  if [ -z "$(find_id /users username "$1")" ]; then
    initial=$(openssl rand -hex 16)
    api POST /users -f -o /dev/null -d "{\"username\":\"$1\",\"email\":\"$1@example.com\",\"password\":\"$initial\"}"
    (
      COOKIES=$(mktemp)
      trap 'rm -f "$COOKIES"' EXIT
      ADMIN_USER=$1 ADMIN_PASSWORD=$initial
      rahdar_sign_in "$2" REQUESTER_PASSWORD
    )
  fi
}
# One approver locks the gate (ADR-0187): a request needs someone other than its requester, so the provider holds
# nothing until two accounts may decide. `qa-approver` is the second, and must be older than the requester.
approver_role=$(find_id /roles name QA_APPROVER)
[ -n "$approver_role" ] || approver_role=$(api POST /roles -d '{"name":"QA_APPROVER","requiresMfa":false,"teamAssignable":false,"permissions":["qa-approvals:approve","cluster:read","subscription:read","settings:read"]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
create_user qa-approver "$REQUESTER_PASSWORD"
api POST "/users/$(find_id /users username qa-approver)/grants" -o /dev/null -d "{\"roleId\":\"$approver_role\",\"scopeType\":\"GLOBAL\"}" || true
create_user qa-requester "$REQUESTER_PASSWORD"
requester=$(find_id /users username qa-requester)
api POST "/users/$requester/grants" -o /dev/null -d "{\"roleId\":\"$role\",\"scopeType\":\"GLOBAL\"}" || true
# What the requester needs to ask for a Kafka offset reset and a truncate, which the gate holds as well.
kafka_role=$(find_id /roles name QA_KAFKA)
[ -n "$kafka_role" ] || kafka_role=$(api POST /roles -d '{"name":"QA_KAFKA","requiresMfa":false,"teamAssignable":false,"permissions":["subscription:seek","destination:truncate","destination:read","cluster:read","subscription:read","message:read"]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
api POST "/users/$requester/grants" -o /dev/null -d "{\"roleId\":\"$kafka_role\",\"scopeType\":\"GLOBAL\"}" || true
orders=$(find_id /teams name qa-orders)
operator=$(api GET /teams/lookups/roles | python3 -c "import json,sys; print(next(r['id'] for r in json.load(sys.stdin)['roles'] if r['name']=='TEAM_OPERATOR'))")
api POST "/teams/$orders/members" -o /dev/null -d "{\"principalType\":\"USER\",\"userId\":\"$requester\",\"roleId\":\"$operator\"}" || true

say "saving its session"
REQUESTER_PASSWORD=$REQUESTER_PASSWORD node --experimental-strip-types "$REPO/web/scripts/sweep/auth.ts" requester

say "leaving requests in every state"
cluster=$(find_id /clusters name demo)
for queue in held-1 held-2 held-3 held-4 held-5 held-6 held-toast; do
  api POST "/clusters/$cluster/subscriptions" -o /dev/null \
    -d "{\"kind\":\"artemis.queue\",\"name\":\"qa.reconciliation.$queue\",\"destination\":\"artemis.address/qa.reconciliation.$queue\",\"attributes\":{\"routingType\":\"ANYCAST\",\"durable\":true,\"autoCreateAddress\":true}}" || true
done
ADMIN_COOKIES=$COOKIES
COOKIES=$BUILD/requester.cookies
(ADMIN_USER=qa-requester ADMIN_PASSWORD=$REQUESTER_PASSWORD ADMIN_TOTP_SECRET= rahdar_sign_in "") || exit 1
# purge QUEUE → the held request's id
purge() {
  api POST "/clusters/$cluster/resources/artemis.queue/qa.reconciliation.$1:purge" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["heldOperation"]["id"])'
}
purge held-1 >/dev/null
approved=$(purge held-2)
rejected=$(purge held-3)
cancelled=$(purge held-4)
refused=$(purge held-6)
purge held-5 >/dev/null
api POST "/held-operations/$cancelled/cancel" -f -o /dev/null
api POST /settings/changes -o /dev/null \
  -d '{"changes":[{"key":"gate.run-window","value":"30m"},{"key":"gate.max-open-per-requester","value":"40"}]}'
COOKIES=$ADMIN_COOKIES
# decide ID VOTE REASON
decide() {
  api GET "/held-operations/$1" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"vote":sys.argv[1],"reason":sys.argv[2] or None,"paramsHash":d["paramsHash"],"version":d["version"]}))' "$2" "$3" \
    | api POST "/held-operations/$1/decision" -f -o /dev/null --data-binary @-
}
decide "$approved" APPROVE "Checked with the batch owner"
decide "$rejected" REJECT "The queue still has unprocessed settlements; drain it first"
# Refused with 503 when a node of the estate is stopped (it cannot be estimated): then the request is approved and runs.
api DELETE "/clusters/$cluster/resources/artemis.queue/qa.reconciliation.held-6" -f -o /dev/null || true
decide "$refused" APPROVE ""
echo "the expiring request expires within a few minutes"
