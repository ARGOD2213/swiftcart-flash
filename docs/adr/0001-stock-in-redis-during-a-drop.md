# ADR 0001 · During a drop, stock lives in Redis and changes only through Lua

**Status:** accepted

## Context

A drop puts thousands of buyers on the same few rows at the same second. Row locks in Postgres serialise them
and the database becomes the bottleneck; optimistic concurrency turns the spike into a retry storm. Checking
stock in one call and decrementing it in another is how oversells happen.

## Decision

- When a drop is **armed**, its stock is loaded into Redis (`sc:{dropId}:stock`, one field per SKU). Arming
  again never overwrites live stock.
- A checkout runs `reserve.lua`: drop open? quantity sane? customer under the limit? phone under the limit?
  enough stock? then decrement, record the hold, add it to the expiry set. Redis runs scripts one at a time,
  so nothing can interleave between the check and the decrement.
- Holds expire after the drop's TTL (`release.lua`, run by the reaper); payment confirms a hold
  (`confirm.lua`), after which it can never be released.
- All of a drop's keys share the `{dropId}` hash tag, so the scripts stay valid on Redis Cluster.
- ElastiCache runs with `maxmemory-policy noeviction`: when memory is full, writes fail (checkout returns 503)
  instead of stock counters being evicted.

## Consequences

- No oversell under any concurrency: `InventoryIT` races 300 buyers for 50 units and gets exactly 50, and the
  k6 load test in CI audits a full drop.
- Redis is the source of truth during the window, so losing it matters. Postgres keeps the order log, and
  `InventoryRebuilder` restores stock, holds and per-customer counts from it (see ADR 0003).
- If Redis is unreachable, checkout answers `503 inventory_unavailable`. It never guesses stock from
  anywhere else.
