-- Marks a held reservation as paid: it leaves the expiry set, so the reaper can never give its units away.
-- A payment that lands after the TTL but before the reaper ran still gets its units: nobody else has them yet.
-- KEYS[1] meta, KEYS[2] reservation, KEYS[3] expiry zset; ARGV[1] reservation id
local state = redis.call('HGET', KEYS[1], 'state')
if state ~= 'READY' then
  return 'NOT_READY'
end
local s = redis.call('HGET', KEYS[2], 'state')
if not s then
  return 'MISSING'
end
if s == 'C' then
  return 'ALREADY'
end
redis.call('HSET', KEYS[2], 'state', 'C')
redis.call('ZREM', KEYS[3], ARGV[1])
return 'CONFIRMED'
