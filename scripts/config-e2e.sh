#!/usr/bin/env bash
#
# The end-to-end proof for declared broker configuration (ADR-0067), against the
# dev pair `just dev-up` brings up.
#
# It exists because the claims this loop makes cannot be checked by a unit test.
# The apply engine's contract is about a real broker: what `addAddressSettings`
# replaces, what a read-back reports, what a second apply converges to, and what
# happens when the canary refuses. `BrokerConfigApplyServiceTest` mocks the broker
# layer, so it can only prove Rahdar's half of every one of those sentences.
#
#   ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=... ./scripts/config-e2e.sh
#
# Everything goes through the product's own surfaces — Rahdar's REST API and the
# Artemis CLI inside the broker image. The only direct reads are the two
# measurements at the end, which ask the broker what it reports back, because
# that is the question.
#
# Exit status is the number of failed checks, so CI can gate on it. Every check
# prints PASS or FAIL and the run continues: a baseline is worth more complete
# than short.
set -uo pipefail

RAHDAR=${RAHDAR:-http://localhost:8080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the password just dev-up printed}
# Once the admin has two-step verification (the first run enrols it), the secret that run printed.
ADMIN_TOTP_SECRET=${ADMIN_TOTP_SECRET:-}
NEW_PASSWORD=${NEW_PASSWORD:-config-e2e-Passw0rd!}
COMPOSE=${COMPOSE:-docker compose -f deploy/compose/dev/compose.dev.yaml}
CLUSTER_NAME=${CLUSTER_NAME:-config-e2e}

JAR=/var/lib/artemis-instance/bin/artemis
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

# status METHOD PATH [curl args...] → HTTP status code on stdout
status() {
  local method=$1 path=$2
  shift 2
  curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" -X "$method" "$RAHDAR/api/v1$path" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"
}

# The import endpoint consumes XML, so it needs its own content type rather than
# api()'s JSON one — sending both is how this script first read every import as a 415.
# xml_import PATH FILE_OR_- → body; xml_import_status PATH FILE_OR_- → HTTP status
xml_import() {
  curl -sS -b "$COOKIES" -c "$COOKIES" -X POST "$RAHDAR/api/v1$1" \
    -H 'Content-Type: application/xml' -H "X-XSRF-TOKEN: $(csrf)" --data-binary "@$2"
}
xml_import_status() {
  curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" -X POST "$RAHDAR/api/v1$1" \
    -H 'Content-Type: application/xml' -H "X-XSRF-TOKEN: $(csrf)" --data-binary "@$2"
}

# jq-free JSON access: py '<expression over the parsed body in `d`>' <<< "$json"
py() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# jolokia SERVICE JSON — ask one broker directly; used only for the out-of-band
# change and the measurements.
jolokia() {
  curl -sS -u artemis:artemis -H 'Content-Type: application/json' \
    -d "$2" "http://$($COMPOSE port "$1" 8161)/console/jolokia/"
}

# The broker MBean is named after the broker, not its bind address — the dev pair
# is broker="primary", not the broker="0.0.0.0" the docs' examples use. Ask.
broker_mbean() {
  jolokia "$1" '{"type":"search","mbean":"org.apache.activemq.artemis:broker=*"}' \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['value'][0])"
}

# broker_exec SERVICE OPERATION ARG... — every argument of every operation used here
# is a String, including the settings JSON, so they are passed through as separate
# argv entries and serialised by python. Building this payload in the shell instead
# meant four levels of quoting, and the escaping silently lost the settings body.
broker_exec() {
  local service=$1 operation=$2 mbean
  shift 2
  mbean=$(broker_mbean "$service")
  jolokia "$service" "$(python3 -c "
import json, sys
print(json.dumps({'type': 'exec', 'mbean': sys.argv[1], 'operation': sys.argv[2], 'arguments': sys.argv[3:]}))
" "$mbean" "$operation" "$@")"
}

expect() { # expect LABEL EXPECTED ACTUAL
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 — expected '$2', got '$3'"; fi
}

# ── 0. sign in ────────────────────────────────────────────────────────────────

say "waiting for Rahdar"
for _ in $(seq 1 60); do
  curl -fsS "$RAHDAR/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done
curl -fsS "$RAHDAR/actuator/health" >/dev/null 2>&1 || die "Rahdar never became healthy at $RAHDAR"

say "signing in"
rahdar_sign_in "$NEW_PASSWORD" || die "could not sign in to Rahdar"
pass "signed in as $ADMIN_USER"

# The run applies to the catch-all address setting, and addAddressSettings replaces
# the entry rather than merging into it (notes §15 M2). Keep what the broker had so
# cleanup can put it back: without this the second run starts from the first run's
# output and its recommendations are already closed.
BASELINE_CATCHALL=$(broker_exec artemis-primary 'getAddressSettingsAsJSON(java.lang.String)' '#' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['value'])" 2>/dev/null || echo "")

# ── 1. first launch → register ────────────────────────────────────────────────

say "1. registering the dev pair"
existing=$(api GET /clusters | py "next((c['id'] for c in d if c['name']=='$CLUSTER_NAME'), '')")
[ -n "$existing" ] && api DELETE "/clusters/$existing" >/dev/null

check=$(api POST '/clusters?dryRun=true' -d "{
  \"seedUrls\": [\"http://artemis-primary:8161/console/jolokia\", \"http://artemis-backup:8161/console/jolokia\"],
  \"name\": \"$CLUSTER_NAME\",
  \"credentials\": {\"username\": \"artemis\", \"password\": \"artemis\"},
  \"coreCredentials\": {\"username\": \"artemis\", \"password\": \"artemis\"}
}")
note "connection check: $(py "json.dumps(d)[:200]" <<<"$check" 2>/dev/null || echo unparsed)"
# The check is where a first-launch operator meets their capability gaps.
if py "any(r['appliable'] for r in d['recommendations']['recommendations'])" <<<"$check" 2>/dev/null | grep -q True; then
  pass "P-9 the connection check offers recommended configuration Rahdar can apply"
  note "     seeded from: $(py "d['recommendations']['seededFrom']" <<<"$check")"
  note "     appliable:   $(py "', '.join(r['capability'] for r in d['recommendations']['recommendations'] if r['appliable'])" <<<"$check")"
  note "     manual:      $(py "', '.join(r['capability'] for r in d['recommendations']['recommendations'] if not r['appliable'])" <<<"$check")"
else
  fail "P-9 the connection check reports gaps but offers nothing to apply — copy-only"
fi

CLUSTER=$(api POST /clusters -d "{
  \"seedUrls\": [\"http://artemis-primary:8161/console/jolokia\", \"http://artemis-backup:8161/console/jolokia\"],
  \"name\": \"$CLUSTER_NAME\",
  \"description\": \"configuration end-to-end\",
  \"credentials\": {\"username\": \"artemis\", \"password\": \"artemis\"},
  \"coreCredentials\": {\"username\": \"artemis\", \"password\": \"artemis\"}
}" | py "d['id']")
[ -n "$CLUSTER" ] || die "cluster registration returned no id"
pass "registered cluster $CLUSTER"

say "waiting for topology to report a live node"
for _ in $(seq 1 30); do
  LIVE=$(api GET "/clusters/$CLUSTER/topology" \
    | py "sum(1 for n in d['nodes'] for e in n['endpoints'] if e.get('active'))" 2>/dev/null || echo 0)
  [ "${LIVE:-0}" -ge 1 ] && break
  sleep 2
done
[ "${LIVE:-0}" -ge 1 ] || die "no live node after 60s"
pass "$LIVE live node(s)"

# ── 2. adopt → declare ────────────────────────────────────────────────────────

say "2. adopting the running configuration"
adoption=$(api POST "/clusters/$CLUSTER/artemis/config/adopt")
ADOPT_SETTINGS=$(py "len(d['document']['addressSettings'])" <<<"$adoption")
note "adoption proposes $ADOPT_SETTINGS address-setting match(es), \
$(py "len(d['document']['securitySettings'])" <<<"$adoption") security-setting(s), \
$(py "len(d['document']['diverts'])" <<<"$adoption") divert(s)"
note "disagreements between nodes: $(py "len(d.get('disagreements', []))" <<<"$adoption")"

# DEFECT PROBE P-1: does the adoption preview disclose the drift it closes?
if py "'closes' in json.dumps(d).lower() or 'findings' in json.dumps(d).lower()" <<<"$adoption" | grep -q True; then
  pass "P-1 adoption preview mentions the findings it closes"
else
  fail "P-1 adoption preview does not disclose the drift findings it will erase"
fi

say "saving the adopted document as revision 1"
doc=$(py "json.dumps(d['document'])" <<<"$adoption")
saved=$(api PUT "/clusters/$CLUSTER/artemis/config" \
  -d "{\"document\": $doc, \"expectedRevision\": 0, \"note\": \"adopted by config-e2e\", \"source\": \"ADOPT\"}")
REVISION=$(py "d['revision']" <<<"$saved" 2>/dev/null || echo "")
[ -n "$REVISION" ] || die "save returned no revision: $saved"
pass "revision $REVISION saved"

# DEFECT PROBE P-1b: is the adoption recorded as ADOPT, or laundered as EDIT?
src=$(api GET "/clusters/$CLUSTER/artemis/config/revisions" | py "d[0]['source']")
expect "P-1b revision records its source as ADOPT" "ADOPT" "$src"

# ── 3. dry run → apply → verify ───────────────────────────────────────────────

say "3. dry run of the adopted declaration (expect: nothing to do)"
plan=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=true" -d '{}')
STEPS=$(py "d['plan']['stepCount']" <<<"$plan")
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
expect "an adopted declaration plans zero steps" "0" "$STEPS"

say "declaring something the broker does not have"
# A queue-level setting on a match of our own: additive, reversible, and not a
# policy that could lose a message.
doc2=$(python3 -c "
import json, sys
d = json.load(sys.stdin)
doc = d['document']
doc['addressSettings'].append({'match': 'CONFIG.E2E.#', 'values': {'maxDeliveryAttempts': 7}})
print(json.dumps(doc))" <<<"$adoption")
saved=$(api PUT "/clusters/$CLUSTER/artemis/config" -d "{\"document\": $doc2, \"expectedRevision\": $REVISION, \"note\": \"config-e2e probe setting\"}")
PREVIOUS_REVISION=$REVISION
REVISION=$(py "d['revision']" <<<"$saved" 2>/dev/null || echo "")
[ -n "$REVISION" ] || die "save of the probe setting returned no revision: $saved"
pass "revision $REVISION declares CONFIG.E2E.#"

plan=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=true" -d '{}')
STEPS=$(py "d['plan']['stepCount']" <<<"$plan")
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
CANARY=$(py "d['plan']['canaryNodeId']" <<<"$plan")
HAZARDS=$(py "json.dumps(d['plan'].get('highHazardIds', []))" <<<"$plan")
note "plan: $STEPS step(s), canary $CANARY, high hazards $HAZARDS"
[ "$STEPS" -ge 1 ] && pass "the new setting produced a step" || fail "no step planned for a setting the broker lacks"

say "stale planHash is refused"
code=$(status POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"deadbeef\", \"acknowledgedHazards\": $HAZARDS}")
expect "stale planHash → 409" "409" "$code"

say "stale revision is refused"
code=$(status POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=false" \
  -d "{\"revision\": $PREVIOUS_REVISION, \"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}")
expect "stale revision → 409" "409" "$code"

say "applying for real"
# Watch the config stream across the apply: the screen's live timeline is built
# from these frames, and they are published while the transaction is still open,
# which no unit test can prove.
STREAM_LOG=$(mktemp)
curl -sN -b "$COOKIES" "$RAHDAR/api/v1/stream?clusterId=$CLUSTER&topics=config" >"$STREAM_LOG" &
STREAM_PID=$!
sleep 1
outcome=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}")
sleep 1
kill "$STREAM_PID" 2>/dev/null
wait "$STREAM_PID" 2>/dev/null
frames=$(grep -c 'apply-progress' "$STREAM_LOG" || true)
[ "$frames" -ge 1 ] && pass "the apply reported its progress live ($frames frame(s) on the config stream)" \
  || fail "the apply published no progress frame — the timeline would show a spinner"
grep -q '"phase":"VERIFYING"' "$STREAM_LOG" \
  && pass "the canary's read-back is visible as it happens" \
  || fail "no VERIFYING frame: the canary's verification is not observable"
rm -f "$STREAM_LOG"
OUTCOME=$(py "d['outcome']" <<<"$outcome")
APPLY_ID=$(py "d['applyId']" <<<"$outcome" 2>/dev/null || echo "")
note "outcome: $OUTCOME — $(py "d['summary']" <<<"$outcome")"
expect "apply reports APPLIED" "APPLIED" "$OUTCOME"

say "every step was verified by read-back"
unverified=$(py "sum(1 for n in d['nodes'] for s in n['steps'] if s['status']=='APPLIED' and s['verified']!='VERIFIED')" <<<"$outcome")
expect "no applied step left unverified" "0" "$unverified"

say "re-running converges (every step ALREADY, nothing written)"
plan=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=true" -d '{}')
expect "second dry run plans zero steps" "0" "$(py "d['plan']['stepCount']" <<<"$plan")"

# ── 4. drift ──────────────────────────────────────────────────────────────────

say "4. drift evaluation after a verified apply"
# The screen reads "last evaluated 4m ago" against this; an age with no cadence
# beside it cannot be told apart from a scheduler that has stopped.
cadence=$(py "d['driftIntervalSeconds']" <<<"$(api GET "/clusters/$CLUSTER/artemis/config")")
[ "$cadence" -gt 0 ] && pass "the declaration carries the cadence it is evaluated on (${cadence}s)" \
  || fail "the declaration does not say how often it is evaluated"
report=$(api POST "/clusters/$CLUSTER/artemis/config/drift/evaluate")
drifted=$(py "sum(1 for n in d['nodes'] if n['state']=='DRIFTED')" <<<"$report")
expect "no node drifted right after a verified apply" "0" "$drifted"

# DEFECT PROBE P-1/D-a: does IN_SYNC say why?
if py "all(n.get('basis') for n in d['nodes'] if n['state']=='IN_SYNC')" <<<"$report" | grep -q True; then
  pass "D-a every IN_SYNC node states its basis"
else
  fail "D-a a node is IN_SYNC with no basis recorded (verified apply / adoption / observed match)"
fi

say "changing the broker underneath the declaration"
# Take the setting away on the primary only; the declaration still wants it.
removal=$(broker_exec artemis-primary 'removeAddressSettings(java.lang.String)' 'CONFIG.E2E.#')
py "d['status']" <<<"$removal" | grep -q 200 || fail "the out-of-band removal itself failed: $(head -c 200 <<<"$removal")"
report=$(api POST "/clusters/$CLUSTER/artemis/config/drift/evaluate")
drifted=$(py "sum(1 for n in d['nodes'] if n['state']=='DRIFTED')" <<<"$report")
[ "$drifted" -ge 1 ] && pass "drift is seen after an out-of-band change" || fail "an out-of-band removal produced no drift"

say "adopting while drift is open"
adoption=$(api POST "/clusters/$CLUSTER/artemis/config/adopt")
doc3=$(py "json.dumps(d['document'])" <<<"$adoption")
closes=$(py "len(d['closes'])" <<<"$adoption")
[ "$closes" -ge 1 ] && pass "P-1 the preview names the $closes finding(s) it would close" \
  || fail "P-1 drift is open but the adoption preview closes nothing"

# Unconfirmed, it is refused: the effect is indistinguishable from an apply and
# the meaning is its opposite — the broker keeps doing whatever it is doing.
code=$(status PUT "/clusters/$CLUSTER/artemis/config" \
  -d "{\"document\": $doc3, \"expectedRevision\": $REVISION, \"note\": \"adopt over open drift\", \"source\": \"ADOPT\"}")
expect "P-1 an unconfirmed adoption over open drift is refused" "409" "$code"

api PUT "/clusters/$CLUSTER/artemis/config" \
  -d "{\"document\": $doc3, \"expectedRevision\": $REVISION, \"note\": \"adopt over open drift\",
       \"source\": \"ADOPT\", \"confirm\": \"$CLUSTER_NAME\"}" >/dev/null
report=$(api POST "/clusters/$CLUSTER/artemis/config/drift/evaluate")
after=$(py "sum(1 for n in d['nodes'] if n['state']=='DRIFTED')" <<<"$report")
note "drifted nodes after a confirmed adoption: $after (was $drifted) — the broker was NOT written"
basis=$(py "next((n.get('basis') for n in d['nodes'] if n['state']=='IN_SYNC'), 'none')" <<<"$report")
expect "P-1 the node now says it agrees because it was adopted" "ADOPTED" "$basis"
REVISION=$(api GET "/clusters/$CLUSTER/artemis/config" | py "d['revision']")

# ── 5. concurrency, caps, modes ───────────────────────────────────────────────

say "5. two applies at once"
# The race only exists while an apply is in flight, and an apply with nothing to do
# returns immediately — so give it real work first. Several matches, so the run is
# long enough for the second call to arrive inside it.
doc4=$(python3 -c "
import json, sys
doc = json.load(sys.stdin)['document']
for i in range(12):
    doc['addressSettings'].append({'match': f'CONFIG.E2E.RACE{i}.#', 'values': {'maxDeliveryAttempts': 5}})
print(json.dumps(doc))" <<<"$(api GET "/clusters/$CLUSTER/artemis/config")")
saved=$(api PUT "/clusters/$CLUSTER/artemis/config" -d "{\"document\": $doc4, \"expectedRevision\": $REVISION, \"note\": \"config-e2e race probe\"}")
REVISION=$(py "d['revision']" <<<"$saved" 2>/dev/null || echo "$REVISION")

plan=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=true" -d '{}')
note "race plan: $(py "d['plan']['stepCount']" <<<"$plan") step(s)"
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
HAZARDS=$(py "json.dumps(d['plan'].get('highHazardIds', []))" <<<"$plan")
status POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}" >/tmp/config-e2e-a.code &
first=$!
second=$(status POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}")
wait $first
firstcode=$(cat /tmp/config-e2e-a.code)
note "concurrent applies returned $firstcode and $second"
if [ "$firstcode" = "409" ] || [ "$second" = "409" ]; then
  pass "one of two concurrent applies was refused with 409"
elif [ "$firstcode" = "200" ] && [ "$second" = "200" ]; then
  # Both succeeding proves nothing on its own: the first may simply have finished
  # before the second arrived. Only a plan that still had steps makes it a finding.
  note "both returned 200 — inconclusive unless the run was still in flight; see the step count above"
  fail "two concurrent applies both proceeded ($firstcode, $second)"
else
  fail "unexpected pair of apply results ($firstcode, $second)"
fi

say "config-managed mode refuses an apply server-side"
api PATCH "/clusters/$CLUSTER/artemis/config/mode" \
  -d '{"applyMode": "CONFIG_MANAGED", "reportUndeclared": false, "undeclaredExclusions": []}' >/dev/null
plan=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=true" -d '{}')
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
code=$(status POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": []}")
if [ "$code" = "409" ] || [ "$code" = "422" ]; then
  pass "P-5 a config-managed cluster refuses apply over HTTP ($code)"
else
  fail "P-5 a config-managed cluster applied over HTTP anyway ($code) — only the UI gates it"
fi
api PATCH "/clusters/$CLUSTER/artemis/config/mode" \
  -d '{"applyMode": "RAHDAR_MANAGED", "reportUndeclared": false, "undeclaredExclusions": []}' >/dev/null

# ── 6. import hardening ───────────────────────────────────────────────────────

say "6. import refuses what it cannot honour"
unknown=$(mktemp)
printf '%s' '<core><address-settings><address-setting match="#"><max-size-byte>10</max-size-byte></address-setting></address-settings></core>' >"$unknown"
code=$(xml_import_status "/clusters/$CLUSTER/artemis/config/import-xml" "$unknown")
body=$(xml_import "/clusters/$CLUSTER/artemis/config/import-xml" "$unknown")
note "unknown-key import returned $code"
if py "any('max-size-byte' in json.dumps(v) for v in d.get('errors', []))" <<<"$body" | grep -q True; then
  pass "P-8 an unknown address-setting key is a violation"
else
  fail "P-8 an unknown key ('max-size-byte') is reported as merely unsupported, not refused (ADR-0067 D10)"
  note "     import said: $(py "json.dumps(d.get('unsupported', d))[:200]" <<<"$body" 2>/dev/null || echo "$body" | head -c 200)"
fi
rm -f "$unknown"

say "import refuses an XXE payload"
xxefile=$(mktemp)
printf '%s' '<?xml version="1.0"?><!DOCTYPE core [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><core><address-settings><address-setting match="&xxe;"/></address-settings></core>' >"$xxefile"
body=$(xml_import "/clusters/$CLUSTER/artemis/config/import-xml" "$xxefile")
if grep -q "root:" <<<"$body"; then
  fail "SECURITY external entity was resolved by the import parser"
else
  pass "external entities are not resolved"
fi
rm -f "$xxefile"

say "import refuses an oversized payload"
bigfile=$(mktemp)
python3 -c "
import sys
sys.stdout.write('<core><address-settings>')
sys.stdout.write('<address-setting match=\"A.#\"><max-delivery-attempts>1</max-delivery-attempts></address-setting>' * 40000)
sys.stdout.write('</address-settings></core>')" >"$bigfile"
# A preview reports what it refuses in `errors` and answers 200, the same shape
# malformed XML already uses; the refusal is the errors list, not the status.
body=$(xml_import "/clusters/$CLUSTER/artemis/config/import-xml" "$bigfile")
if py "any('KiB' in json.dumps(v) for v in d.get('errors', []))" <<<"$body" | grep -q True; then
  pass "P-7 an oversized import is refused by the size cap"
else
  fail "P-7 a $(($(wc -c <"$bigfile")))-byte import was accepted — no size cap"
fi
rm -f "$bigfile"

say "export → import round-trips"
roundtrip=$(mktemp)
curl -sS -b "$COOKIES" -c "$COOKIES" "$RAHDAR/api/v1/clusters/$CLUSTER/artemis/config/export-xml" >"$roundtrip"
body=$(xml_import "/clusters/$CLUSTER/artemis/config/import-xml" "$roundtrip")
# An error body carries neither key, so assert the parse actually happened.
if ! py "'sections' in d or 'document' in d or 'unsupported' in d" <<<"$body" | grep -q True; then
  fail "round-trip import did not return a parse result: $(head -c 200 <<<"$body")"
fi
unsupported=$(py "len(d.get('unsupported', []))" <<<"$body" 2>/dev/null || echo '?')
violations=$(py "len(d.get('errors', []))" <<<"$body" 2>/dev/null || echo '?')
expect "Rahdar's own export imports without violations" "0" "$violations"
rm -f "$roundtrip"
note "round-trip reported $unsupported unsupported element(s)"

# ── 7. the two measurements only a live broker settles ────────────────────────

say "7. what the broker reports back (feeds notes §15/§16)"
# M8 must SET the key before concluding anything: §15 M1 established that a key set
# nowhere is simply absent, so reading '#' and finding no threshold proves only that
# the dev broker.xml does not set one.
broker_exec artemis-primary 'addAddressSettings(java.lang.String,java.lang.String)' \
  'probe.slow.#' \
  '{"slowConsumerThreshold":1,"slowConsumerThresholdMeasurementUnit":"MESSAGES_PER_SECOND","slowConsumerPolicy":"NOTIFY","slowConsumerCheckPeriod":5}' >/dev/null
probe=$(broker_exec artemis-primary 'getAddressSettingsAsJSON(java.lang.String)' 'probe.slow.#')
# The operation returns the entry as a JSON *string*, so its quotes arrive escaped:
# the body reads \"slowConsumerThreshold\":1, and a pattern with a bare quote misses.
if grep -q 'slowConsumerThreshold' <<<"$probe"; then
  pass "M8 slowConsumerThreshold IS echoed once set — the key is verifiable, and a plan may declare it"
  note "     CapabilityProbe reports an absent threshold as 'off' on this basis (P-10 closed)"
else
  note "M8 slowConsumerThreshold is not echoed even when set — UNVERIFIABLE;"
  note "   a plan that sets it must say so rather than report MISMATCH forever"
fi
broker_exec artemis-primary 'removeAddressSettings(java.lang.String)' 'probe.slow.#' >/dev/null

settings=$(broker_exec artemis-primary 'getAddressSettingsAsJSON(java.lang.String)' '#')
note "keys reported: $(python3 -c "
import json, sys
v = json.load(sys.stdin)['value']
print(len(json.loads(v) if isinstance(v, str) else v))" <<<"$settings" 2>/dev/null || echo '?')"

broker_exec artemis-primary \
  'addSecuritySettings(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)' \
  CONFIG.E2E.ROLES amq amq amq amq amq amq amq amq amq amq amq >/dev/null
roles=$(broker_exec artemis-primary 'getRolesAsJSON(java.lang.String)' 'CONFIG.E2E.ROLES')
# §15 M7: the keys come back, but as false — presence proves nothing, the value does.
viewvalue=$(python3 -c "
import json, sys
v = json.load(sys.stdin)['value']
roles = json.loads(v) if isinstance(v, str) else v
print(next((r.get('view') for r in roles if r.get('name') == 'amq'), 'absent'))" <<<"$roles" 2>/dev/null || echo unparsed)
if [ "$viewvalue" = "True" ]; then
  note "M9 view/edit role types ARE reported back — verifiable"
else
  note "M9 view reads back as '$viewvalue' after being granted — unverifiable, as notes §15 M7 recorded"
fi

# ── 8. capability gaps close from the declaration ─────────────────────────────

say "8. declaring the capability probe's recommendations (P-9)"
recs=$(api GET "/clusters/$CLUSTER/artemis/config/recommendations")
appliable=$(py "', '.join(r['capability'] for r in d['recommendations'] if r['appliable'])" <<<"$recs" 2>/dev/null || echo "")
note "appliable: ${appliable:-none}; seeded from $(py "d['seededFrom']" <<<"$recs" 2>/dev/null || echo unknown)"

# The entry must carry the keys the node already has: addAddressSettings replaces
# the entry, so a recommendation holding only its own keys would silently reset
# everything else on '#' (notes §15 M2).
if [ -n "$appliable" ]; then
  if py "any(len(r['values']) > len(r['keys']) for r in d['recommendations'] if r['appliable'] and r['section'] == 'ADDRESS_SETTING')" <<<"$recs" 2>/dev/null | grep -q True; then
    pass "a recommended address setting carries the node's existing keys, not only its own"
  else
    fail "a recommended address setting carries only its own keys — applying it would reset the rest"
  fi
else
  note "nothing left to recommend — this broker already has every appliable setting"
fi

if [ -n "$appliable" ]; then
  declared=$(api POST "/clusters/$CLUSTER/artemis/config/recommendations/declare" -d '{}')
  revnow=$(py "d['revision']" <<<"$declared" 2>/dev/null || echo "")
  src=$(py "d['source']" <<<"$declared" 2>/dev/null || echo "")
  if [ "$src" = "RECOMMENDED" ]; then
    pass "declared as revision $revnow, source RECOMMENDED — the audit trail says where it came from"
  else
    fail "declaring the recommendations recorded source '$src'"
  fi

  plan=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=true" -d '{}')
  steps=$(py "d['plan']['stepCount']" <<<"$plan" 2>/dev/null || echo 0)
  if [ "$steps" -gt 0 ]; then
    pass "the recommendations produce a real plan ($steps steps) — nothing written yet"
  else
    fail "the recommendations produced an empty plan"
  fi
  h=$(py "d['plan']['planHash']" <<<"$plan")
  # Every High hazard must be acknowledged by id, the same gate the UI puts a
  # checkbox on; a plan that replaces an address setting usually carries one.
  hz=$(py "json.dumps([z['id'] for z in d['plan']['hazards'] if z['hazardClass'] == 'HIGH'])" <<<"$plan")
  note "hazards: $(py "', '.join(z['hazardClass'] + ' ' + z['kind'] for z in d['plan']['hazards']) or 'none'" <<<"$plan")"
  applied=$(api POST "/clusters/$CLUSTER/artemis/config/apply?dryRun=false" -d "{\"expectedPlanHash\": \"$h\", \"acknowledgedHazards\": $hz}")
  outcome=$(py "d['outcome']" <<<"$applied" 2>/dev/null || echo unparsed)
  if [ "$outcome" = "APPLIED" ]; then
    pass "the recommended configuration applied and verified by read-back"
  else
    fail "applying the recommended configuration ended $outcome"
  fi

  after=$(api GET "/clusters/$CLUSTER/artemis/config/recommendations")
  left=$(py "', '.join(r['capability'] for r in d['recommendations'] if r['appliable'])" <<<"$after" 2>/dev/null || echo "")
  if [ -z "$left" ]; then
    pass "the probe re-run finds no appliable gap left"
  else
    note "still appliable after the apply: $left"
    note "   (a gap whose verdict also needs a broker-plugin cannot close from here)"
  fi
fi

# ── done ──────────────────────────────────────────────────────────────────────

say "cleaning up"
broker_exec artemis-primary 'removeSecuritySettings(java.lang.String)' 'CONFIG.E2E.ROLES' >/dev/null 2>&1
# Put the catch-all back as it was found, so the next run measures the same broker.
[ -n "$BASELINE_CATCHALL" ] && broker_exec artemis-primary \
  'addAddressSettings(java.lang.String,java.lang.String)' '#' "$BASELINE_CATCHALL" >/dev/null 2>&1
rm -f /tmp/config-e2e-a.code

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32m%s\033[0m\n' "all checks passed"
else
  printf '\033[31m%s\033[0m\n' "$FAILURES check(s) failed — each is a numbered defect for the phase list"
fi
exit "$FAILURES"
