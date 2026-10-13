#!/usr/bin/env bash
# Walks through SwiftCart Flash's guarantees against the local stack (docker compose up). CI runs this same
# script after `docker compose up`, so every claim below is checked on every push.
#
# Usage: scripts/demo.sh        (needs curl and jq)
set -euo pipefail

API=${API:-http://localhost:8080}
RUN=$(date +%s)
SLUG="demo-$RUN"

step() { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$*"; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$*"; }
# call METHOD PATH TOKEN [BODY] [EXTRA CURL ARGS...] -> prints "status body"
call() {
  local method=$1 path=$2 token=$3 body=${4:-}
  shift 4 || shift $#
  local out
  out=$(curl -sS -o /tmp/sc_body.$$ -w '%{http_code}' -X "$method" "$API$path" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "$@" ${body:+--data "$body"})
  printf '%s %s' "$out" "$(cat /tmp/sc_body.$$)"
}
status_of() { cut -d' ' -f1 <<<"$1"; }
body_of() { cut -d' ' -f2- <<<"$1"; }
address='{"name":"Asha Rao","line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","pincode":"560001"}'
checkout_body() { printf '{"sku":"%s","quantity":%s,"phone":"%s","address":%s}' "$1" "$2" "$3" "$address"; }

step "Waiting for the API"
for _ in $(seq 1 120); do
  curl -fsS "$API/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$API/actuator/health/readiness" >/dev/null || fail "API never became ready"
ok "API is up"

step "A drop with 3 pairs per size, 2 per customer, 30-second holds"
drop=$(curl -fsS -X POST "$API/dev/drops/demo?slug=$SLUG&stock=3&limit=2&admitPerSecond=50&ttlSeconds=30")
DROP_ID=$(jq -r .id <<<"$drop")
echo "$drop" | jq -c .
mapfile -t T < <(curl -fsS -X POST "$API/dev/customers/bulk?count=5&prefix=$SLUG" | jq -r '.[]')
ASHA=${T[0]}; BEN=${T[1]}; CHITRA=${T[2]}; DEV=${T[3]}

admit() { # token -> entry token (joins the line and waits for its turn)
  curl -fsS -X POST "$API/api/drops/$SLUG/queue" -H "Authorization: Bearer $1" >/dev/null
  for _ in $(seq 1 30); do
    local s
    s=$(curl -fsS "$API/api/drops/$SLUG/queue/me" -H "Authorization: Bearer $1")
    [ "$(jq -r .state <<<"$s")" = ADMITTED ] && { jq -r .entryToken <<<"$s"; return; }
    sleep 1
  done
  fail "never admitted"
}

step "The waiting room: no entry token, no checkout"
r=$(call POST "/api/drops/$SLUG/checkout" "$ASHA" "$(checkout_body MR-UK8 1 9876500001)" -H "Idempotency-Key: k-$RUN-0")
[ "$(status_of "$r")" = 401 ] && [ "$(body_of "$r" | jq -r .code)" = entry_token_missing ] || fail "checkout without a token: $r"
ok "skipping the line is refused (401 entry_token_missing)"
E_ASHA=$(admit "$ASHA"); E_BEN=$(admit "$BEN"); E_CHITRA=$(admit "$CHITRA"); E_DEV=$(admit "$DEV")
ok "four shoppers joined the line and were admitted at the admit rate"

step "Asha buys 2 (her limit) and pays"
r=$(call POST "/api/drops/$SLUG/checkout" "$ASHA" "$(checkout_body MR-UK8 2 '98765 00001')" -H "Idempotency-Key: k-$RUN-asha" -H "X-Entry-Token: $E_ASHA")
[ "$(status_of "$r")" = 201 ] || fail "Asha's checkout: $r"
ASHA_ORDER=$(body_of "$r" | jq -r .id); ASHA_GW=$(body_of "$r" | jq -r .payment.gatewayOrderId)
paid=$(curl -fsS -X POST "$API/dev/simulator/pay" -H 'Content-Type: application/json' -d "{\"gatewayOrderId\":\"$ASHA_GW\",\"duplicate\":true}")
jq -c . <<<"$paid"
[ "$(jq -r .duplicate <<<"$paid")" = duplicate ] || fail "a re-delivered webhook was processed twice"
ok "paid; the gateway's duplicate webhook was acknowledged and skipped"
r=$(call POST "/api/drops/$SLUG/checkout" "$ASHA" "$(checkout_body MR-UK9 1 9876500001)" -H "Idempotency-Key: k-$RUN-asha2" -H "X-Entry-Token: $E_ASHA")
[ "$(body_of "$r" | jq -r .code)" = limit_reached ] || fail "Asha went over her limit: $r"
ok "a third pair is refused: limit_reached (counts across orders and sizes)"

step "Ben double-clicks: the same Idempotency-Key twice is one order"
b1=$(call POST "/api/drops/$SLUG/checkout" "$BEN" "$(checkout_body MR-UK8 1 9876500002)" -H "Idempotency-Key: k-$RUN-ben" -H "X-Entry-Token: $E_BEN")
b2=$(call POST "/api/drops/$SLUG/checkout" "$BEN" "$(checkout_body MR-UK8 1 9876500002)" -H "Idempotency-Key: k-$RUN-ben" -H "X-Entry-Token: $E_BEN")
[ "$(body_of "$b1" | jq -r .id)" = "$(body_of "$b2" | jq -r .id)" ] || fail "two orders from one key"
BEN_ORDER=$(body_of "$b1" | jq -r .id); BEN_GW=$(body_of "$b1" | jq -r .payment.gatewayOrderId)
ok "same order $BEN_ORDER both times"
r=$(call POST "/api/drops/$SLUG/checkout" "$BEN" "$(checkout_body MR-UK9 1 9876500002)" -H "Idempotency-Key: k-$RUN-ben" -H "X-Entry-Token: $E_BEN")
[ "$(status_of "$r")" = 422 ] || fail "a reused key with a different body was accepted: $r"
ok "the same key with a different body is rejected (422)"

step "UK 8 is now sold out (Asha 2 paid + Ben 1 held = 3)"
r=$(call POST "/api/drops/$SLUG/checkout" "$CHITRA" "$(checkout_body MR-UK8 1 9876500003)" -H "Idempotency-Key: k-$RUN-chitra" -H "X-Entry-Token: $E_CHITRA")
[ "$(body_of "$r" | jq -r .code)" = sold_out ] || fail "expected sold_out: $r"
ok "Chitra is told sold_out; nothing is oversold"
r=$(call POST "/api/drops/$SLUG/checkout" "$DEV" "$(checkout_body MR-UK9 2 '+91-98765-00002')" -H "Idempotency-Key: k-$RUN-dev" -H "X-Entry-Token: $E_DEV")
[ "$(body_of "$r" | jq -r .code)" = phone_limit_reached ] || fail "a second account on the same phone got past the limit: $r"
ok "Dev, a second account with Ben's phone written differently, is held to the same limit: phone_limit_reached"

step "Ben doesn't pay: his hold expires after 30 s and the pair goes back on sale"
for _ in $(seq 1 60); do
  st=$(curl -fsS "$API/api/orders/$BEN_ORDER" -H "Authorization: Bearer $BEN" | jq -r .status)
  [ "$st" = EXPIRED ] && break
  sleep 1
done
[ "$st" = EXPIRED ] || fail "Ben's hold never expired"
ok "Ben's order EXPIRED; stock: $(curl -fsS "$API/api/drops/$SLUG/stock" | jq -c .available)"
r=$(call POST "/api/drops/$SLUG/checkout" "$CHITRA" "$(checkout_body MR-UK8 1 9876500003)" -H "Idempotency-Key: k-$RUN-chitra2" -H "X-Entry-Token: $(admit "$CHITRA")")
[ "$(status_of "$r")" = 201 ] || fail "Chitra couldn't buy the released pair: $r"
CHITRA_GW=$(body_of "$r" | jq -r .payment.gatewayOrderId)
curl -fsS -X POST "$API/dev/simulator/pay" -H 'Content-Type: application/json' -d "{\"gatewayOrderId\":\"$CHITRA_GW\"}" >/dev/null
ok "Chitra bought the released pair"

step "Ben's payment finally lands, after the pair was resold: refunded, not oversold"
late=$(curl -fsS -X POST "$API/dev/simulator/pay" -H 'Content-Type: application/json' -d "{\"gatewayOrderId\":\"$BEN_GW\"}")
jq -c . <<<"$late"
for _ in $(seq 1 30); do
  pst=$(curl -fsS "$API/api/orders/$BEN_ORDER" -H "Authorization: Bearer $BEN" | jq -r '.payments[0].status')
  [ "$pst" = REFUNDED ] && break
  sleep 1
done
[ "$pst" = REFUNDED ] || fail "Ben's late payment was not refunded (status $pst)"
ok "Ben's late payment was refunded by the worker"

step "A forged webhook is rejected"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/webhooks/simulator" -H 'Content-Type: application/json' \
  -H 'X-Razorpay-Signature: 0000' -d '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_x","status":"captured","amount":1,"order_id":"x"}}}}')
[ "$code" = 400 ] || fail "forged webhook got HTTP $code"
ok "400 invalid_signature"

step "Fulfilment: Asha's paid order was invoiced and handed to the warehouse"
for _ in $(seq 1 30); do
  st=$(curl -fsS "$API/api/orders/$ASHA_ORDER" -H "Authorization: Bearer $ASHA" | jq -r .status)
  [ "$st" = FULFILLED ] && break
  sleep 1
done
[ "$st" = FULFILLED ] || fail "Asha's order is $st, not FULFILLED"
ok "FULFILLED (outbox → SQS FIFO → worker → invoice, email, warehouse)"

step "Audit: the drop's invariants"
ADMIN=$(curl -s -X POST "$API/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"email":"admin@swiftcart.local","password":"local-admin-password","name":"Admin"}' | jq -r '.accessToken // empty')
[ -n "$ADMIN" ] || ADMIN=$(curl -fsS -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@swiftcart.local","password":"local-admin-password"}' | jq -r .accessToken)
audit=$(curl -fsS "$API/api/admin/drops/$DROP_ID/audit" -H "Authorization: Bearer $ADMIN")
jq -c '{ok, skus: [.skus[] | {sku, initialStock, paidUnits, redisAvailable}], ordersChargedMoreThanOnce, refunds, ordersByStatus}' <<<"$audit"
[ "$(jq -r .ok <<<"$audit")" = true ] || fail "audit failed"
ok "no oversell, no customer over the limit, nobody charged twice, Redis and the order log agree"

printf '\n\033[1mAll checks passed.\033[0m\n'
