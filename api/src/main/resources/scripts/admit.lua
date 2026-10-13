-- Moves the waiting room forward by up to ARGV[1] tickets. Called once a second by one leader.
-- Admits nobody before the drop opens, while admissions are paused, or while every unit is sold or held
-- (holds that expire put units back, and admissions resume by themselves).
-- KEYS[1] meta, KEYS[2] stock, KEYS[3] ticket sequence, KEYS[4] admitted, KEYS[5] paused flag
-- Returns {admitted so far, admitted now}
local meta = redis.call('HMGET', KEYS[1], 'state', 'opens', 'closes', 'expireAt')
local admitted = tonumber(redis.call('GET', KEYS[4]) or '0')
if meta[1] ~= 'READY' then
  return {admitted, 0}
end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if now < tonumber(meta[2]) or now >= tonumber(meta[3]) then
  return {admitted, 0}
end
if redis.call('EXISTS', KEYS[5]) == 1 then
  return {admitted, 0}
end
local total = 0
for _, v in ipairs(redis.call('HVALS', KEYS[2])) do
  total = total + tonumber(v)
end
if total <= 0 then
  return {admitted, 0}
end
local seq = tonumber(redis.call('GET', KEYS[3]) or '0')
local target = math.min(seq, admitted + tonumber(ARGV[1]))
if target > admitted then
  redis.call('SET', KEYS[4], target)
  redis.call('PEXPIREAT', KEYS[4], meta[4])
end
return {target, target - admitted}
