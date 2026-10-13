# ADR 0002 · Idempotency end to end, and one place that decides what a payment means

**Status:** accepted

## Context

Shoppers double-click, mobile networks retry, gateways deliver webhooks late, twice, or out of order, and
sometimes a payment lands after the hold it was for has expired. "Charge every buyer exactly once" has to hold
through all of it.

## Decision

- **Checkout** takes an `Idempotency-Key`. The first request owns it; retries get the stored response.
  The same key with a different body is a `422`, and the same key while the first request is still running is a
  `409` with `Retry-After`. A request that died mid-flight is taken over after 60 s. The reservation id is derived
  from (customer, key), so a retried checkout finds the same hold instead of taking another.
- **Payment sessions** are created once per order, with the order row locked (Razorpay) and an
  `Idempotency-Key` header (Stripe).
- **Webhooks** are verified (HMAC over the raw body; Stripe also checks the timestamp) and de-duplicated by event
  id **in the same transaction** as their effect, so a failed apply leaves the event to be retried.
- **One function decides** (`PaymentService.apply`), whichever way the news arrives (webhook, checkout callback,
  reconciler), with the order row locked:
  - captured + held reservation → confirm, `PAID`, `ORDER_PAID` event;
  - captured after the hold ended → take the units again if they're still there, else refund;
  - a second captured payment for a paid order → refund;
  - amount or currency mismatch → `REVIEW` (units held, nothing shipped);
  - no matching order → refund;
  - authorized → capture only if the units are still held;
  - a late "failed" never downgrades a captured payment.
- The **reconciler** asks the gateway about orders pending for a while and orders that expired recently, to
  catch lost webhooks and late payments.
- Refunds go through the outbox and the worker with an idempotency key.

## Consequences

- Every path is covered by a test in `PaymentFlowIT` and `CheckoutIT`, and the CI load test audits that no order
  has more than one captured payment.
- Refunds of late payments cost the shop a gateway fee; overselling would cost a customer.
