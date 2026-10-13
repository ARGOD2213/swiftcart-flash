-- Atomically takes units for one checkout and holds them for the reservation TTL.
-- Nothing else can run between the checks and the decrement, so stock can't go below zero (no oversell)
-- and a customer can't exceed the per-customer limit, even with thousands of concurrent requests.
--
-- KEYS[1] meta, KEYS[2] stock, KEYS[3] held per customer, KEYS[4] held per phone, KEYS[5] reservation, KEYS[6] expiry zset
-- ARGV[1] sku code, ARGV[2] quantity, ARGV[3] customer id, ARGV[4] phone (E.164), ARGV[5] reservation id
-- Returns {code, number, expires-at ms}
local meta = redis.call('HMGET', KEYS[1], 'state', 'opens', 'closes', 'limit', 'ttl', 'expireAt')
if not meta[1] or meta[1] ~= 'READY' then
  return {'NOT_READY', 0, 0}
end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if now < tonumber(meta[2]) then
  return {'NOT_OPEN', 0, 0}
end
if now >= tonumber(meta[3]) then
  return {'CLOSED', 0, 0}
end

-- the same reservation id again (a retried checkout): hand back the existing hold, take nothing more
local existing = redis.call('HMGET', KEYS[5], 'state', 'exp')
if existing[1] then
  return {'DUPLICATE', 0, tonumber(existing[2])}
end

local qty = tonumber(ARGV[2])
local limit = tonumber(meta[4])
if not qty or qty < 1 or qty > limit or qty ~= math.floor(qty) then
  return {'BAD_QTY', limit, 0}
end
local stock = redis.call('HGET', KEYS[2], ARGV[1])
if not stock then
  return {'UNKNOWN_SKU', 0, 0}
end
stock = tonumber(stock)
local heldByCustomer = tonumber(redis.call('HGET', KEYS[3], ARGV[3]) or '0')
if heldByCustomer + qty > limit then
  return {'LIMIT', math.max(0, limit - heldByCustomer), 0}
end
local heldByPhone = tonumber(redis.call('HGET', KEYS[4], ARGV[4]) or '0')
if heldByPhone + qty > limit then
  return {'PHONE_LIMIT', math.max(0, limit - heldByPhone), 0}
end
if stock <= 0 then
  return {'SOLD_OUT', 0, 0}
end
if stock < qty then
  return {'INSUFFICIENT', stock, 0}
end

local remaining = redis.call('HINCRBY', KEYS[2], ARGV[1], -qty)
redis.call('HINCRBY', KEYS[3], ARGV[3], qty)
redis.call('HINCRBY', KEYS[4], ARGV[4], qty)
local exp = now + tonumber(meta[5])
redis.call('HSET', KEYS[5], 'sku', ARGV[1], 'qty', qty, 'cust', ARGV[3], 'phone', ARGV[4], 'state', 'R', 'exp', exp)
redis.call('ZADD', KEYS[6], exp, ARGV[5])
local expireAt = meta[6]
redis.call('PEXPIREAT', KEYS[3], expireAt)
redis.call('PEXPIREAT', KEYS[4], expireAt)
redis.call('PEXPIREAT', KEYS[5], expireAt)
redis.call('PEXPIREAT', KEYS[6], expireAt)
return {'OK', remaining, exp}
