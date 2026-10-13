-- Loads a drop's stock into Redis. Never overwrites: arming a drop that is already live must not reset its stock.
-- KEYS[1] meta, KEYS[2] stock
-- ARGV[1] opens (ms), ARGV[2] closes (ms), ARGV[3] per-customer limit, ARGV[4] reservation ttl (ms),
-- ARGV[5] expire-at for every key of the drop (ms), then pairs: sku code, units
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 'EXISTS'
end
local i = 6
while i < #ARGV do
  redis.call('HSET', KEYS[2], ARGV[i], ARGV[i + 1])
  i = i + 2
end
redis.call('HSET', KEYS[1], 'state', 'READY', 'opens', ARGV[1], 'closes', ARGV[2], 'limit', ARGV[3],
  'ttl', ARGV[4], 'expireAt', ARGV[5])
redis.call('PEXPIREAT', KEYS[1], ARGV[5])
redis.call('PEXPIREAT', KEYS[2], ARGV[5])
return 'ARMED'
