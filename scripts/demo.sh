#!/usr/bin/env bash
#
# End-to-end walkthrough of the membership lifecycle against a running instance.
#
#   ./scripts/demo.sh                       # against the default http://localhost:8080
#   BASE_URL=http://localhost:9090 ./scripts/demo.sh   # or override the host/port
#   DEMO_NO_PAUSE=1 ./scripts/demo.sh       # stream start-to-finish without pausing
#
# Every id is discovered from the API (nothing is hardcoded), and each run uses a fresh user id,
# so the script is safe to run repeatedly against the same database. When run interactively it
# pauses for Enter between steps; piped or CI runs stream straight through.
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"

# The admin bootstrap secret is what gates minting an ADMIN token. Prefer the environment, then
# .env, then the value shipped in .env.example.
if [[ -z "${ADMIN_BOOTSTRAP_SECRET:-}" && -f .env ]]; then
  ADMIN_BOOTSTRAP_SECRET="$(grep -E '^ADMIN_BOOTSTRAP_SECRET=' .env | cut -d= -f2- || true)"
fi
ADMIN_BOOTSTRAP_SECRET="${ADMIN_BOOTSTRAP_SECRET:-local-dev-admin-bootstrap}"

# A distinct email per run keeps re-runs independent: registration mints a fresh app_user id each
# time (user_id is an FK now), so the one-live-subscription index is scoped to that new user.
RUN="${RUN:-$(( 90000 + RANDOM % 9000 ))}"

# --- presentation ----------------------------------------------------------------------------
# Emphasis is weight-based (bold), not colour, so section headers stay legible on both light and
# dark terminals. Escapes are emitted only when stdout is a terminal, so piped output stays clean.
if [[ -t 1 ]]; then
  B=$'\033[1m'; GREEN=$'\033[32m'; RESET=$'\033[0m'
  JQC='-C'
else
  B=''; GREEN=''; RESET=''
  JQC=''
fi

# Between steps, wait for Enter — but only when run interactively (a TTY on stdin), so piped or
# CI runs never block. DEMO_NO_PAUSE=1 turns the pause off.
pause() {
  if [[ -t 0 && "${DEMO_NO_PAUSE:-}" != "1" ]]; then
    printf '\n%s' "   ${B}Press Enter for the next step…${RESET}"
    read -r _ || true
  fi
}

STEP=0
rule() { printf '%s\n' "──────────────────────────────────────────────────────────────"; }
step() {
  if ((STEP > 0)); then pause; fi
  STEP=$((STEP + 1))
  printf '\n%s\n' "${B}▶ ${STEP}. $*${RESET}"
}
note() { printf '   %s\n' "$*"; }
good() { printf '   %s\n' "${GREEN}✔ $*${RESET}"; }

# The demo user's current membership tier (or "inactive"), for showing before/after transitions.
member_tier() {
  curl -s "$API/users/$USER_ID/membership" -H "Authorization: Bearer $SELF" \
    | jq -r '.tierCode // "inactive"'
}

# Echo an outgoing request as "→ METHOD /path  {compact-json}  (note)" before its response, so
# each step reads request → response. jq -c compacts the body; a non-JSON body prints as-is.
# Args: METHOD PATH [JSON_BODY] [NOTE]
show_req() {
  local body="" extra="${4:+  ($4)}"
  [[ -n "${3:-}" ]] && body="  $(printf '%s' "$3" | jq -c . 2>/dev/null || printf '%s' "$3")"
  printf '   → %s %s%s%s\n' "$1" "$2" "$body" "$extra"
}

# This script reads and pretty-prints JSON with jq.
if ! command -v jq >/dev/null 2>&1; then
  echo "This demo needs jq. Install it (e.g. 'brew install jq') and re-run." >&2
  exit 1
fi
get() { jq -r "$1"; }
pretty() { jq $JQC "${1:-.}"; }

require_app() {
  if ! curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    echo "The app is not answering at $BASE_URL." >&2
    echo "Start it first:  docker compose up -d && mvn spring-boot:run" >&2
    exit 1
  fi
}

# ---------------------------------------------------------------------------------------------

require_app
printf '\n%s\n' "${B}FirstClub Membership — end-to-end demo${RESET}"
printf '%s\n' "${BASE_URL}"
rule

step "Catalog: public, no token needed. Cached in memory."
curl -s "$API/plans" | pretty '[.[] | {id, cadence, price, durationDays}]'
curl -s "$API/tiers" | pretty '[.[] | {code, rank, benefits: [.benefits[].code]}]'
note "Perks are rows, not code. Gold and Platinum unlock progressively more."

PLAN_ID=$(curl -s "$API/plans" | get '[.[] | select(.cadence=="MONTHLY")][0].id')
SILVER_ID=$(curl -s "$API/tiers" | get '[.[] | select(.code=="SILVER")][0].id')
GOLD_ID=$(curl -s "$API/tiers" | get '[.[] | select(.code=="GOLD")][0].id')

step "Register a user (admin), then mint tokens."
ADMIN=$(curl -s -X POST "$API/auth/token" -H 'Content-Type: application/json' \
  -d "{\"userId\":0,\"admin\":true,\"secret\":\"$ADMIN_BOOTSTRAP_SECRET\"}" | get .token)
if [[ -z "${ADMIN:-}" || "$ADMIN" == "null" ]]; then
  echo "Could not mint an ADMIN token: ADMIN_BOOTSTRAP_SECRET does not match the app's." >&2
  exit 1
fi
USER_BODY="{\"name\":\"Demo User $RUN\",\"email\":\"demo-$RUN-$(date +%s)@firstclub.test\"}"
show_req POST /api/v1/users "$USER_BODY"
USER_JSON=$(curl -s -X POST "$API/users" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "$USER_BODY")
echo "$USER_JSON" | pretty '{id, name, email}'
USER_ID=$(echo "$USER_JSON" | get .id)
STRANGER_ID=$(( USER_ID + 1 )) # a second user, used only to prove authorization is enforced
SELF=$(curl -s -X POST "$API/auth/token" -H 'Content-Type: application/json' \
  -d "{\"userId\":$USER_ID}" | get .token)
STRANGER=$(curl -s -X POST "$API/auth/token" -H 'Content-Type: application/json' \
  -d "{\"userId\":$STRANGER_ID}" | get .token)
good "Registered user #$USER_ID; self + admin tokens minted."
note "Registration-first: the user exists in app_user before it can subscribe (user_id is an FK)."
note "A self token needs no secret; admin=true requires the bootstrap secret, so escalation isn't anonymous."

step "Subscribe to Monthly / Silver."
SUB_BODY="{\"userId\":$USER_ID,\"planId\":$PLAN_ID,\"tierId\":$SILVER_ID}"
show_req POST /api/v1/subscriptions "$SUB_BODY" "Idempotency-Key: demo-$USER_ID"
SUB=$(curl -s -X POST "$API/subscriptions" \
  -H "Authorization: Bearer $SELF" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$USER_ID" \
  -d "$SUB_BODY")
echo "$SUB" | pretty '{id, status, pricePaid, expiresAt}'
SUB_ID=$(echo "$SUB" | get .id)
good "Active subscription #$SUB_ID; expiry is tracked on the record above."

step "Replay the SAME Idempotency-Key."
show_req POST /api/v1/subscriptions "$SUB_BODY" "Idempotency-Key: demo-$USER_ID"
curl -s -X POST "$API/subscriptions" \
  -H "Authorization: Bearer $SELF" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$USER_ID" \
  -d "$SUB_BODY" | pretty '{id, status}'
note "Same body, same key → same subscription id, no second charge: at-most-once, not just retry-safe."

step "Authorization: a stranger cannot read this member's data."
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$API/users/$USER_ID/membership" \
  -H "Authorization: Bearer $STRANGER")
echo "   stranger (user $STRANGER_ID) → GET /users/$USER_ID/membership → HTTP $CODE"
note "403: a valid token proves who you are, not whose data you may touch. @PreAuthorize"
note "enforces self-or-admin on every per-user endpoint."

step "Try to upgrade to Gold without qualifying."
UPGRADE_BODY="{\"targetTierId\":$GOLD_ID}"
show_req POST "/api/v1/subscriptions/$SUB_ID/upgrade" "$UPGRADE_BODY"
body=$(mktemp)
CODE=$(curl -s -o "$body" -w '%{http_code}' -X POST "$API/subscriptions/$SUB_ID/upgrade" \
  -H "Authorization: Bearer $SELF" -H 'Content-Type: application/json' \
  -d "$UPGRADE_BODY")
echo "   ← HTTP $CODE"
pretty . < "$body"
rm -f "$body"
note "422, as RFC-7807 problem+json. Tier criteria are enforced, not advisory."

step "Feed per-order events as ADMIN. Tier is derived from the orders."
MONTH="$(date -u +%Y-%m)"
echo "   BEFORE — membership tier: $(member_tier)"
ORDER1="{\"orderCount\":18,\"orderId\":$((USER_ID * 100 + 1)),\"orderAmount\":15000,\"occurredAt\":\"${MONTH}-05T10:00:00Z\",\"cohorts\":[]}"
ORDER2="{\"orderCount\":20,\"orderId\":$((USER_ID * 100 + 2)),\"orderAmount\":10000,\"occurredAt\":\"${MONTH}-12T10:00:00Z\",\"cohorts\":[]}"
show_req POST "/api/v1/users/$USER_ID/activity" "$ORDER1"
curl -s -X POST "$API/users/$USER_ID/activity" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "$ORDER1" >/dev/null
show_req POST "/api/v1/users/$USER_ID/activity" "$ORDER2"
curl -s -X POST "$API/users/$USER_ID/activity" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "$ORDER2" | pretty '{tierCode, tierName, active}'
echo "   AFTER  — membership tier: $(member_tier)"
note "Order value and count both come from the orders: 25000 spend and 20 orders clear"
note "Platinum's thresholds (20000 spend / 15 orders). Cohort membership is a third"
note "criterion, fed the same way (a value in the cohorts list); left empty here."
# Same orderId as ORDER1 (…01), higher orderCount — proves the upsert ignores the re-delivery.
ORDER1_AGAIN="{\"orderCount\":20,\"orderId\":$((USER_ID * 100 + 1)),\"orderAmount\":15000,\"occurredAt\":\"${MONTH}-05T10:00:00Z\",\"cohorts\":[]}"
show_req POST "/api/v1/users/$USER_ID/activity" "$ORDER1_AGAIN"
curl -s -X POST "$API/users/$USER_ID/activity" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "$ORDER1_AGAIN" >/dev/null
note "Re-delivering an order (same orderId) is a no-op: spend is keyed on order id, not additive."
note "ADMIN-only on purpose: order value and cohorts ARE the tier criteria, so a user posting"
note "their own activity would be free tier escalation."

step "Membership now: auto-upgraded, with the richer benefit set."
curl -s "$API/users/$USER_ID/membership" -H "Authorization: Bearer $SELF" \
  | pretty '{tierCode, active, expiresAt, benefits: [.benefits[].code]}'
note "Silver to Platinum without an explicit upgrade call: the rule engine did it."

step "Configure a NEW perk on a tier at runtime, no restart, no deploy."
note "This is the 'each tier unlocks additional perks, should be configurable' requirement."
PERK="FESTIVE_DROP_$USER_ID" # per-run code so re-runs create a fresh perk, not a collision

# Summarise Gold's state from a /tiers payload: perk count, and whether it carries this perk.
gold_state() {
  jq -r --arg p "$PERK" '
    (.[] | select(.code == "GOLD")) as $g
    | "\($g.benefits | length) perks; carries \($p)? "
      + ([$g.benefits[] | select(.code == $p)] | length > 0 | tostring)'
}

echo "   BEFORE  — Gold has $(curl -s "$API/tiers" | gold_state)"
CREATE_BODY="{\"code\":\"$PERK\",\"type\":\"EXCLUSIVE_COUPONS\",\"name\":\"Festive coupon drop\",\"description\":\"Seasonal coupons, configured live\"}"
show_req POST /api/v1/admin/benefits "$CREATE_BODY"
curl -s -X POST "$API/admin/benefits" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "$CREATE_BODY" | pretty '{code, type, name}'
ASSIGN_BODY="{\"tierId\":$GOLD_ID,\"benefitCode\":\"$PERK\",\"params\":{\"couponsPerCycle\":3,\"campaign\":\"DIWALI\"}}"
show_req POST /api/v1/admin/benefits/assignments "$ASSIGN_BODY"
curl -s -o /dev/null -w '   ← HTTP %{http_code} (assignment)\n' -X POST "$API/admin/benefits/assignments" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "$ASSIGN_BODY"
echo "   AFTER   — Gold has $(curl -s "$API/tiers" | gold_state)"
echo "   The perk just added, with its params:"
curl -s "$API/tiers" | jq $JQC --arg p "$PERK" \
  '[.[] | select(.code == "GOLD") | .benefits[] | select(.code == $p)][0] | {code, params}'
note "Perk count rose by one and 'carries?' flipped false → true. The perk and its params are"
note "rows; caches were evicted, so the change is live immediately."

step "Downgrade (always the member's choice) then cancel."
echo "   BEFORE — tier $(member_tier), status ACTIVE"
DOWNGRADE_BODY="{\"targetTierId\":$SILVER_ID}"
show_req POST "/api/v1/subscriptions/$SUB_ID/downgrade" "$DOWNGRADE_BODY"
curl -s -X POST "$API/subscriptions/$SUB_ID/downgrade" \
  -H "Authorization: Bearer $SELF" -H 'Content-Type: application/json' \
  -d "$DOWNGRADE_BODY" | pretty '{tierCode, status}'
show_req POST "/api/v1/subscriptions/$SUB_ID/cancel"
curl -s -X POST "$API/subscriptions/$SUB_ID/cancel" \
  -H "Authorization: Bearer $SELF" | pretty '{tierCode, status}'
note "Downgrade dropped the tier (tierCode above) and repriced the renewal with no mid-cycle"
note "refund; cancel then ended the membership (status ACTIVE → CANCELLED)."

step "Credit note issued for the unused time (proration on cancel)."
curl -s "$API/users/$USER_ID/credit-notes" -H "Authorization: Bearer $SELF" \
  | pretty '[.[] | {reason, amount, subscriptionId, createdAt}]'
note "The basis is money actually collected (summed APPLIED ledger rows), not list price, so"
note "an unconverted trial credits zero by construction rather than by a status check."

step "Clean up: unassign this run's perk from Gold."
curl -s -o /dev/null -X DELETE "$API/admin/benefits/assignments/$GOLD_ID/$PERK" \
  -H "Authorization: Bearer $ADMIN" \
  -w "   DELETE /admin/benefits/assignments/$GOLD_ID/$PERK → HTTP %{http_code}\n"
note "So repeated demo runs don't pile perks onto Gold. Unassign is a soft delete; the benefit"
note "stays in the catalog and a re-assign would reactivate the same mapping."

rule
printf '%s\n' "${GREEN}${B}✔ Done.${RESET}"
note "Swagger UI:  $BASE_URL/swagger-ui/index.html"
note "Postman:     postman/  (import the collection + environment)"
