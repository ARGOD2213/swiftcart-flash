#!/usr/bin/env bash
# Checks the storefront (Next.js) against the local stack (docker compose up, web included): cached pages show
# real drops, the proxy exposes shopper endpoints only, the session lives in an httpOnly cookie, and a whole
# purchase works through the routes the browser uses. CI runs it after demo.sh.
#
# Usage: scripts/storefront-smoke.sh        (needs curl and jq)
set -euo pipefail

API=${API:-http://localhost:8080}
WEB=${WEB:-http://localhost:3000}
RUN=$(date +%s)
SLUG="shop-$RUN"
OUT=/tmp/sc_web_out.$$
HDR=/tmp/sc_web_hdr.$$
JAR=/tmp/sc_web_jar.$$
JAR2=/tmp/sc_web_jar2.$$

step() { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$*"; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$*"; }
# code [CURL ARGS...] -> HTTP status; body in $OUT, headers in $HDR
code() { curl -sS -o "$OUT" -D "$HDR" -w '%{http_code}' "$@"; }
header() { grep -i "^$1:" "$HDR" | head -1 | cut -d' ' -f2- | tr -d '\r'; }
address='{"name":"Asha Rao","line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","pincode":"560001"}'
order_body=$(printf '{"sku":"MR-UK8","quantity":1,"phone":"98765 4%04d","address":%s}' $((RUN % 10000)) "$address")

step "Waiting for the storefront"
for _ in $(seq 1 90); do
  curl -fsS -o /dev/null "$WEB/login" 2>/dev/null && break
  sleep 2
done
curl -fsS -o /dev/null "$WEB/login" || fail "the storefront never came up"
ok "storefront is up"

step "Drop pages come from the API and are cached"
curl -fsS -X POST "$API/dev/drops/demo?slug=$SLUG&stock=5&limit=2&admitPerSecond=50" >/dev/null
c=$(code "$WEB/drops/$SLUG")
[ "$c" = 200 ] && grep -q 'Midnight Runner' "$OUT" || fail "drop page: HTTP $c"
[[ "$(header cache-control)" == *s-maxage=30* ]] || fail "drop page is not cacheable: $(header cache-control)"
c=$(code "$WEB/drops/$SLUG")
[ "$(header x-nextjs-cache)" = HIT ] || fail "second visit was not served from the cache ($(header x-nextjs-cache))"
ok "drop page renders, is CDN-cacheable (s-maxage=30), and the second visit is a cache hit"
c=$(code "$WEB/drops/no-such-drop-$RUN")
[ "$c" = 404 ] || fail "unknown drop: HTTP $c"
ok "an unknown drop is a 404"

step "The home page lists live drops"
for _ in $(seq 1 30); do
  curl -fsS -o "$OUT" "$WEB/" && grep -q "/drops/$SLUG" "$OUT" && break
  sleep 3
done
grep -q "/drops/$SLUG" "$OUT" || fail "the home page never listed $SLUG"
ok "the cached home page refreshed and lists $SLUG"

step "The proxy only reaches shopper endpoints"
for p in admin/drops internal/scaling auth/login dev/customers/bulk; do
  c=$(code -X POST "$WEB/api/sc/$p")
  [ "$c" = 404 ] || fail "/api/sc/$p answered HTTP $c"
done
c=$(code --path-as-is "$WEB/api/sc/drops/../../internal/scaling")
[ "$c" != 200 ] || fail "path traversal reached the API"
ok "admin, internal, dev and token endpoints are not reachable through the storefront"

step "Signing up keeps the token in an httpOnly cookie"
EMAIL="shopper-$RUN@example.com"
c=$(code -c "$JAR" -X POST "$WEB/auth/register" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"Track-and-field-$RUN\",\"name\":\"Asha\"}")
[ "$c" = 200 ] || fail "register: HTTP $c $(cat "$OUT")"
grep -i '^set-cookie: sc_token=' "$HDR" | grep -qi httponly || fail "the session cookie is not httpOnly"
grep -q accessToken "$OUT" && fail "the access token leaked into the response body"
c=$(code -b "$JAR" "$WEB/api/sc/auth/me")
[ "$c" = 200 ] && grep -q "$EMAIL" "$OUT" || fail "me: HTTP $c"
ok "signed in; page scripts never see the token"
c=$(code -X POST "$WEB/auth/register" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"Track-and-field-$RUN\",\"name\":\"Asha\"}")
[ "$c" = 409 ] || fail "registering the same email twice: HTTP $c"
ok "the same email can't register twice (409)"

step "Checkout through the storefront: the line first, then one order per key"
c=$(code -b "$JAR" -X POST "$WEB/api/sc/drops/$SLUG/checkout" -H 'content-type: application/json' \
  -H "idempotency-key: w-$RUN" -d "$order_body")
[ "$c" = 401 ] && [ "$(jq -r .code "$OUT")" = entry_token_missing ] || fail "checkout without the line: HTTP $c"
ok "skipping the line is refused"
curl -fsS -o /dev/null -b "$JAR" -X POST "$WEB/api/sc/drops/$SLUG/queue"
entry=null
for _ in $(seq 1 30); do
  curl -fsS -o "$OUT" -b "$JAR" "$WEB/api/sc/drops/$SLUG/queue/me"
  [ "$(jq -r .state "$OUT")" = ADMITTED ] && { entry=$(jq -r .entryToken "$OUT"); break; }
  sleep 1
done
[ "$entry" != null ] || fail "never admitted"
c=$(code -b "$JAR" -X POST "$WEB/api/sc/drops/$SLUG/checkout" -H 'content-type: application/json' \
  -H "idempotency-key: w-$RUN" -H "x-entry-token: $entry" -d "$order_body")
[ "$c" = 201 ] || fail "checkout: HTTP $c $(cat "$OUT")"
ORDER=$(jq -r .id "$OUT"); GW=$(jq -r .payment.gatewayOrderId "$OUT")
c=$(code -b "$JAR" -X POST "$WEB/api/sc/drops/$SLUG/checkout" -H 'content-type: application/json' \
  -H "idempotency-key: w-$RUN" -H "x-entry-token: $entry" -d "$order_body")
[ "$c" = 201 ] && [ "$(jq -r .id "$OUT")" = "$ORDER" ] && [ "$(header idempotent-replayed)" = true ] \
  || fail "double submit: HTTP $c, order $(jq -r .id "$OUT"), replayed=$(header idempotent-replayed)"
ok "a double-submit returns the same order (Idempotent-Replayed: true)"

step "Paying and tracking the order"
curl -fsS -o /dev/null -X POST "$WEB/api/sim/pay" -H 'content-type: application/json' -d "{\"gatewayOrderId\":\"$GW\"}"
status=
for _ in $(seq 1 40); do
  status=$(curl -fsS -b "$JAR" "$WEB/api/sc/orders/$ORDER" | jq -r .status)
  [ "$status" = FULFILLED ] && break
  sleep 1
done
[ "$status" = FULFILLED ] || fail "order ended as $status"
c=$(code -b "$JAR" "$WEB/orders/$ORDER")
[ "$c" = 200 ] || fail "order page: HTTP $c"
ok "paid → FULFILLED, and the order page loads"

step "Other shoppers can't see the order"
c=$(code -c "$JAR2" -X POST "$WEB/auth/register" -H 'content-type: application/json' \
  -d "{\"email\":\"other-$RUN@example.com\",\"password\":\"Long-jump-pit-$RUN\",\"name\":\"Ben\"}")
[ "$c" = 200 ] || fail "second register: HTTP $c"
c=$(code -b "$JAR2" "$WEB/api/sc/orders/$ORDER")
[ "$c" = 404 ] || fail "another shopper got HTTP $c for this order"
ok "another account gets 404 for it"

step "Signing out ends the session"
curl -fsS -o /dev/null -b "$JAR" -c "$JAR" -X POST "$WEB/auth/logout"
c=$(code -b "$JAR" "$WEB/api/sc/auth/me")
[ "$c" = 401 ] || fail "after sign-out: HTTP $c"
ok "signed out"

printf '\n\033[32mAll storefront checks passed.\033[0m\n'
