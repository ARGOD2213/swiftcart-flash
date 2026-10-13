#!/usr/bin/env bash
# Runs the k6 drop rehearsal against the local stack, waits for walked-away holds to expire, then audits the
# drop. Fails if anything was oversold, anyone went over the limit or anyone was charged twice.
#
# Usage: scripts/loadtest.sh            (needs docker, curl, jq; the compose stack running)
#   SHOPPERS=1500 VUS=300 STOCK=150     defaults: 1,500 shoppers chasing 300 pairs
set -euo pipefail

API=${API:-http://localhost:8080}
SLUG="load-$(date +%s)"
here=$(cd "$(dirname "$0")/.." && pwd)

echo "▶ k6: ${SHOPPERS:-1500} shoppers, ${VUS:-300} at a time, ${STOCK:-150} pairs per size"
chmod a+w "$here/loadtest" 2>/dev/null || true
set +e
docker run --rm --network host -v "$here/loadtest:/scripts" \
  -e API="$API" -e SLUG="$SLUG" -e SHOPPERS="${SHOPPERS:-1500}" -e VUS="${VUS:-300}" -e STOCK="${STOCK:-150}" \
  grafana/k6:0.54.0 run --quiet /scripts/drop.js
k6_exit=$?
set -e

echo "▶ waiting for walked-away holds (30 s) to expire and return to stock"
sleep 40

ADMIN=$(curl -s -X POST "$API/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"email":"admin@swiftcart.local","password":"local-admin-password","name":"Admin"}' | jq -r '.accessToken // empty')
[ -n "$ADMIN" ] || ADMIN=$(curl -fsS -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@swiftcart.local","password":"local-admin-password"}' | jq -r .accessToken)
DROP_ID=$(jq -r .dropId "$here/loadtest/result.json")
audit=$(curl -fsS "$API/api/admin/drops/$DROP_ID/audit" -H "Authorization: Bearer $ADMIN")
echo "$audit" | jq '{ok, skus, customersOverLimit, phonesOverLimit, ordersChargedMoreThanOnce, capturedPaymentsWithoutPaidOrder, refunds, ordersByStatus}' \
  | tee "$here/loadtest/audit.json"

jq -r .line "$here/loadtest/result.json"
sold=$(jq '[.skus[].paidUnits] | add' <<<"$audit")
total=$(jq '[.skus[].initialStock] | add' <<<"$audit")
echo "sold $sold of $total pairs"

[ "$(jq -r .ok <<<"$audit")" = true ] || { echo "✗ audit failed"; exit 1; }
[ "$sold" -le "$total" ] || { echo "✗ oversold: $sold > $total"; exit 1; }
[ "$k6_exit" -eq 0 ] || { echo "✗ k6 thresholds failed (exit $k6_exit)"; exit 1; }
echo "✓ no oversell, no one over the limit, no one charged twice"
