-- One ticket per customer per drop: joining again returns the same place in line (no line-jumping, and a bot
-- can't hold many places with one account).
-- KEYS[1] tickets hash (customer -> ticket), KEYS[2] ticket sequence; ARGV[1] customer id, ARGV[2] expire-at (ms)
local existing = redis.call('HGET', KEYS[1], ARGV[1])
if existing then
  return {tonumber(existing), 0}
end
local n = redis.call('INCR', KEYS[2])
redis.call('HSET', KEYS[1], ARGV[1], n)
redis.call('PEXPIREAT', KEYS[1], ARGV[2])
redis.call('PEXPIREAT', KEYS[2], ARGV[2])
return {n, 1}
