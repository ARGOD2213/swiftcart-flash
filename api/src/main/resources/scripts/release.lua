-- Gives a held (not yet paid) reservation's units back. Idempotent: releasing twice, or releasing a paid
-- reservation, changes nothing.
-- KEYS[1] meta, KEYS[2] stock, KEYS[3] held per customer, KEYS[4] held per phone, KEYS[5] reservation, KEYS[6] expiry zset
-- ARGV[1] reservation id, ARGV[2] 'expire' (only if its time is up) or 'cancel' (now)
-- Returns {code, stock left for that sku}
local state = redis.call('HGET', KEYS[1], 'state')
if state ~= 'READY' then
  return {'NOT_READY', 0}
end
local r = redis.call('HMGET', KEYS[5], 'sku', 'qty', 'cust', 'phone', 'state', 'exp')
if not r[1] then
  redis.call('ZREM', KEYS[6], ARGV[1])
  return {'MISSING', 0}
end
if r[5] ~= 'R' then
  redis.call('ZREM', KEYS[6], ARGV[1])
  return {'CONFIRMED', 0}
end
if ARGV[2] == 'expire' then
  local t = redis.call('TIME')
  local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
  if now < tonumber(r[6]) then
    return {'NOT_EXPIRED', 0}
  end
end
local qty = tonumber(r[2])
local remaining = redis.call('HINCRBY', KEYS[2], r[1], qty)
-- held counters never go below zero, even if a rebuild reset them in between
if redis.call('HINCRBY', KEYS[3], r[3], -qty) <= 0 then
  redis.call('HDEL', KEYS[3], r[3])
end
if redis.call('HINCRBY', KEYS[4], r[4], -qty) <= 0 then
  redis.call('HDEL', KEYS[4], r[4])
end
redis.call('DEL', KEYS[5])
redis.call('ZREM', KEYS[6], ARGV[1])
return {'RELEASED', remaining}
