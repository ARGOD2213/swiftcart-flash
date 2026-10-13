# ADR 0003 · A waiting room in front of checkout, and recovery from the order log

**Status:** accepted

## Context

Pods take a minute or two to scale; a drop's crowd arrives in one second. Bots try to skip the line or hold
many places. And Redis can fail over to a replica that missed the last writes.

## Decision

**Waiting room**
- Shoppers join a line per drop (`join.lua`): one ticket per account, so rejoining keeps your place and an account
  can't hold several.
- One leader (Postgres advisory lock) admits the next `admitPerSecond` tickets once a second (`admit.lua`), never
  before the drop opens, not while paused, and not while every unit is sold or held.
- An admitted shopper gets an **entry token** (JWT, its own key and type) naming the customer and the drop.
  Checkout refuses requests without a valid one, so the line can't be skipped by calling the API.
- Polling backs off with the shopper's distance from the front.
- Pre-scaling: the API reports how many checkout pods the next drop needs (`/internal/scaling`), and KEDA scales to
  that from 15 minutes before the drop opens.

**Recovery**
- Postgres is the order log: every hold that became an order is there.
- `InventoryRebuilder` marks the drop `REBUILDING` (checkouts get 503, payment events are retried), recomputes stock,
  holds and per-customer/per-phone counts from the orders, rewrites Redis, and marks it `READY`. Re-arming an armed
  drop whose Redis data is missing does this automatically.
- Holds that only existed in Redis (a checkout that crashed before writing its order) are dropped, which only returns
  units to stock.
- A database-side safety net in the reaper releases pending orders whose hold ended minutes ago, in case Redis
  lost the expiry entry.

## Consequences

- `WaitingRoomIT` and `ReaperAndRebuildIT` cover the line, entry tokens, pausing, sold-out handling, Redis loss
  and the safety net.
- The admit rate is a knob: set it to what checkout can comfortably handle, and watch `/api/admin/drops/{id}/queue`.
