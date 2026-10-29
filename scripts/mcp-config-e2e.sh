#!/usr/bin/env bash
#
# The configuration loop over MCP, against the dev pair `just dev-up` brings up.
#
# `McpBrokerConfigRealBrokerTest` proves the same loop against a container broker
# from inside the build, through MockMvc. What it cannot prove is the wire: a real
# client speaks JSON-RPC over `POST /mcp` with a bearer key, negotiates a protocol
# version, and reads `text/event-stream` back. This drives exactly that, with curl
# as the client, so the transport is the product's own and nothing is stubbed.
#
#   ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=... ./scripts/mcp-config-e2e.sh
#
# Exit status is the number of failed checks.
set -uo pipefail

RAHDAR=${RAHDAR:-http://localhost:8080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the password just dev-up printed}
# Once the admin has two-step verification (config-e2e.sh enrols it), the secret that run printed.
ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
NEW_PASSWORD=${NEW_PASSWORD:-config-e2e-Passw0rd!}
CLUSTER_NAME=${CLUSTER_NAME:-mcp-config-e2e}
SEED=${SEED:-http://artemis-primary:8161/console/jolokia}
BROKER_USER=${BROKER_USER:-artemis}
BROKER_PASSWORD=${BROKER_PASSWORD:-artemis}
MATCH=${MATCH:-MCP.E2E.#}

COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

# shellcheck source=lib/signin.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/signin.sh"

FAILURES=0
say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }
pass() { printf '\033[32m  PASS %s\033[0m\n' "$*"; }
fail() { printf '\033[31m  FAIL %s\033[0m\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
note() { printf '\033[36m  ·    %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mABORT: %s\033[0m\n' "$*" >&2; exit 99; }

py() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
# ── sign in and mint a key ────────────────────────────────────────────────────

# `config-e2e.sh` rotates the bootstrap password on its first run against a fresh stack, so the same
# fallback applies here: try what was given, then what that script would have set.
rahdar_sign_in "$NEW_PASSWORD" || die "could not sign in to Rahdar"

say "registering $CLUSTER_NAME and minting an MCP key"
existing=$(api GET /clusters | py "next((c['id'] for c in d if c['name']=='$CLUSTER_NAME'), '')")
if [ -n "$existing" ]; then
  CLUSTER=$existing
else
  registered=$(api POST /clusters -d "{\"system\": \"artemis\", \"name\": \"$CLUSTER_NAME\",
    \"connection\": {\"system\": \"artemis\", \"seedUrls\": [\"$SEED\"],
      \"credentials\": {\"username\": \"$BROKER_USER\", \"password\": \"$BROKER_PASSWORD\"}}}")
  CLUSTER=$(py "d['id']" <<<"$registered" 2>/dev/null) || die "register failed: $registered"
fi
note "cluster $CLUSTER"

minted=$(api POST /tokens -d "{\"name\": \"mcp-config-e2e\", \"grants\": [
  {\"scopeType\": \"CLUSTER\", \"scopeId\": \"$CLUSTER\", \"action\": \"cluster:read\"},
  {\"scopeType\": \"CLUSTER\", \"scopeId\": \"$CLUSTER\", \"action\": \"config:write\"},
  {\"scopeType\": \"CLUSTER\", \"scopeId\": \"$CLUSTER\", \"action\": \"config:apply\"}]}")
KEY=$(py "d['value']" <<<"$minted" 2>/dev/null) || die "could not mint a key: $minted"
[ -n "$KEY" ] || die "the mint call returned no key value: $minted"
TOKEN_ID=$(py "d['token']['id']" <<<"$minted" 2>/dev/null || echo "")
cleanup() {
  [ -n "${TOKEN_ID:-}" ] && api DELETE "/tokens/$TOKEN_ID" >/dev/null 2>&1
  rm -f "$COOKIES"
}
trap cleanup EXIT

# ── the MCP wire ──────────────────────────────────────────────────────────────

RPC_ID=0
SESSION=""

# rpc METHOD PARAMS_JSON → the JSON-RPC response body, SSE framing stripped
rpc() {
  RPC_ID=$((RPC_ID + 1))
  local body headers
  headers=$(mktemp)
  body=$(curl -sS -D "$headers" "$RAHDAR/mcp" \
    -H "Authorization: Bearer $KEY" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    ${SESSION:+-H "Mcp-Session-Id: $SESSION"} \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$RPC_ID,\"method\":\"$1\",\"params\":${2:-\{\}}}")
  if [ -z "$SESSION" ]; then
    SESSION=$(awk 'tolower($1) == "mcp-session-id:" { print $2 }' "$headers" | tr -d '\r')
  fi
  rm -f "$headers"
  # A streaming response frames the payload as `data: {...}`; a JSON one does not.
  if grep -q '^data: ' <<<"$body"; then
    grep '^data: ' <<<"$body" | tail -1 | cut -c7-
  else
    printf '%s' "$body"
  fi
}

# tool NAME ARGS_JSON → the tool result
tool() { rpc tools/call "{\"name\":\"$1\",\"arguments\":$2}"; }

say "1. the handshake a real client performs"
init=$(rpc initialize '{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"config-e2e","version":"1"}}')
server=$(py "d['result']['serverInfo']['name']" <<<"$init" 2>/dev/null || echo "")
[ -n "$server" ] && pass "initialize negotiated with $server" || fail "initialize failed: $(head -c 300 <<<"$init")"
curl -sS -o /dev/null "$RAHDAR/mcp" -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  ${SESSION:+-H "Mcp-Session-Id: $SESSION"} \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

listed=$(rpc tools/list '{}')
names=$(py "','.join(t['name'] for t in d['result']['tools'])" <<<"$listed" 2>/dev/null || echo "")
grep -q 'artemis_config' <<<"$names" && pass "tools/list carries the configuration tools" \
  || fail "artemis_config is not in the catalogue: $names"

say "2. declaring over MCP previews by default"
DOC="{\"version\":1,\"addresses\":[],\"addressSettings\":[{\"match\":\"$MATCH\",\"values\":{\"maxDeliveryAttempts\":7}}],\"securitySettings\":[],\"diverts\":[]}"
preview=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"declare\",\"document\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$DOC")}")
grep -q 'Nothing was saved' <<<"$preview" && pass "a declare without dryRun=false saves nothing and says so" \
  || fail "the dry-run declare did not say it saved nothing: $(head -c 300 <<<"$preview")"

saved=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"declare\",\"document\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$DOC"),\"dryRun\":false,\"confirm\":\"$CLUSTER_NAME\"}")
REV=$(py "d['result']['structuredContent']['revision']" <<<"$saved" 2>/dev/null || echo "")
[ -n "$REV" ] && pass "declared as revision $REV" || fail "declare failed: $(head -c 300 <<<"$saved")"

say "3. planning names the hazards to acknowledge"
plan=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"apply\"}")
HASH=$(py "d['result']['structuredContent']['planHash']" <<<"$plan" 2>/dev/null || echo "")
STEPS=$(py "d['result']['structuredContent']['stepCount']" <<<"$plan" 2>/dev/null || echo 0)
ACK=$(py "','.join(d['result']['structuredContent'].get('acknowledge', []))" <<<"$plan" 2>/dev/null || echo "")
note "plan: $STEPS step(s), hash $HASH, acknowledge [$ACK]"
[ "$STEPS" -ge 1 ] && pass "the declaration plans a real step" || fail "nothing planned: $(head -c 300 <<<"$plan")"

say "4. a real run without the acknowledgement is refused"
# Only when there is something to withhold. With no High hazard this same call is
# a perfectly valid apply, and making it here would apply the plan the rest of the
# script is still holding a hash for.
if [ -z "$ACK" ]; then
  note "this plan carries no High hazard; the withholding path is covered by McpBrokerConfigIntegrationTest"
else
  refused=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"apply\",\"dryRun\":false,\"confirm\":\"$CLUSTER_NAME\",\"expectedPlanHash\":\"$HASH\"}")
  grep -qi 'acknowledge' <<<"$refused" && pass "refused, naming what to acknowledge" \
    || fail "an unacknowledged run was not refused: $(head -c 300 <<<"$refused")"
fi

say "5. a stale plan hash is refused"
stale=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"apply\",\"dryRun\":false,\"confirm\":\"$CLUSTER_NAME\",\"expectedPlanHash\":\"deadbeef\"${ACK:+,\"acknowledge\":\"$ACK\"}}")
grep -qi 'plan' <<<"$stale" && grep -qiE 'changed|stale|moved|hash' <<<"$stale" \
  && pass "a plan the cluster has moved past is refused" \
  || fail "a stale planHash was not refused: $(head -c 300 <<<"$stale")"

say "6. applying for real, canary first"
applied=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"apply\",\"dryRun\":false,\"confirm\":\"$CLUSTER_NAME\",\"expectedPlanHash\":\"$HASH\"${ACK:+,\"acknowledge\":\"$ACK\"}}")
OUTCOME=$(py "d['result']['structuredContent']['outcome']" <<<"$applied" 2>/dev/null || echo "")
[ "$OUTCOME" = "APPLIED" ] && pass "applied and verified by read-back" || fail "apply outcome was '$OUTCOME': $(head -c 400 <<<"$applied")"

again=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"apply\"}")
[ "$(py "d['result']['structuredContent']['stepCount']" <<<"$again" 2>/dev/null || echo -1)" = "0" ] \
  && pass "re-planning converges to zero steps" || fail "a second plan still has steps"

say "7. every read kind answers"
for kind in declaration drift xml applies; do
  out=$(tool artemis_config "{\"clusterId\":\"$CLUSTER\",\"kind\":\"$kind\"}")
  if [ -z "$out" ] || grep -q '"isError":true' <<<"$out" || grep -q '"error"' <<<"$out"; then
    fail "kind=$kind did not answer: $(head -c 200 <<<"$out")"
  else
    pass "kind=$kind"
  fi
done

say "cleaning up"
# The broker keeps a runtime setting across restarts (ADR-0065), so the
# declaration going away is not enough: without removing what this run applied,
# the next run finds the broker already matching and plans nothing. Rahdar removes
# only what Rahdar applied (ADR-0067 D6), which is exactly this.
EMPTY='{"version":1,"addresses":[],"addressSettings":[],"securitySettings":[],"diverts":[]}'
tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"declare\",\"document\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$EMPTY"),\"dryRun\":false,\"confirm\":\"$CLUSTER_NAME\"}" >/dev/null
teardown=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"apply\"}")
TEARDOWN_HASH=$(py "d['result']['structuredContent']['planHash']" <<<"$teardown" 2>/dev/null || echo "")
TEARDOWN_ACK=$(py "','.join(d['result']['structuredContent'].get('acknowledge', []))" <<<"$teardown" 2>/dev/null || echo "")
removed=$(tool artemis_config_change "{\"clusterId\":\"$CLUSTER\",\"op\":\"apply\",\"dryRun\":false,\"confirm\":\"$CLUSTER_NAME\",\"expectedPlanHash\":\"$TEARDOWN_HASH\"${TEARDOWN_ACK:+,\"acknowledge\":\"$TEARDOWN_ACK\"}}")
grep -q '"outcome":"APPLIED"' <<<"$removed" \
  && pass "what this run applied was removed again, so the run repeats" \
  || note "nothing to remove (the broker had no leftover from this run)"

# The cluster is deliberately left registered. Rahdar removes only what Rahdar
# applied, and that record belongs to the cluster: deleting and re-registering
# would orphan the setting on the broker and the next run would find it already
# matching and plan nothing. Re-running reuses this cluster by name.

if [ "$FAILURES" -eq 0 ]; then
  printf '\n\033[32mall checks passed\033[0m\n'
else
  printf '\n\033[31m%d check(s) failed\033[0m\n' "$FAILURES"
fi
exit "$FAILURES"
