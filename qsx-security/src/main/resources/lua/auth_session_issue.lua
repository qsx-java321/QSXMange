-- 登录签发会话（含单端覆盖：同一用户旧端的 at/rt 立即失效）
--
-- KEYS[1] = session 键
-- KEYS[2] = 新的 at 键
-- KEYS[3] = 新的 rt 键
-- ARGV[1] = userId
-- ARGV[2] = 新 access token 明文
-- ARGV[3] = 新 refresh token 明文
-- ARGV[4] = at TTL（秒，整数）
-- ARGV[5] = rt TTL（秒，整数）
-- ARGV[6] = 当前时间戳（毫秒）
-- ARGV[7] = at 键前缀
-- ARGV[8] = rt 键前缀
--
-- 返回 'OK'
--
-- 约定：所有 ARGV 必须是字符串（StringRedisTemplate 用 StringRedisSerializer 序列化参数，
-- 传数字会在触达 Redis 之前抛 ClassCastException），脚本内一律 tonumber() 后再用。

local sessionKey = KEYS[1]
local newAtKey = KEYS[2]
local newRtKey = KEYS[3]
local userId = ARGV[1]
local newAt = ARGV[2]
local newRt = ARGV[3]
local atTtl = tonumber(ARGV[4])
local rtTtl = tonumber(ARGV[5])
local nowMs = tonumber(ARGV[6])
local atPrefix = ARGV[7]
local rtPrefix = ARGV[8]

-- 参数校验必须在任何写操作之前：Lua 脚本报错【不会】回滚已执行的写
if not atTtl or atTtl <= 0 or not rtTtl or rtTtl <= 0 or not nowMs then
    return redis.error_reply('invalid session args')
end

-- 1. 读取旧会话，删除其令牌键（单端覆盖）
--    必须用 HGET 逐字段取：RESP2 下 HGETALL 返回的是 Lua 数组而非 map，
--    old.accessToken 恒为 nil，AT 键将永远删不掉（即时踢下线静默失效）
local oldAt = redis.call('HGET', sessionKey, 'accessToken')
local oldRt = redis.call('HGET', sessionKey, 'refreshToken')
if oldAt then
    redis.call('DEL', atPrefix .. oldAt)
end
if oldRt then
    redis.call('DEL', rtPrefix .. oldRt)
end

-- 2. 先写会话索引，再写令牌键：
--    中途失败时留下的是「会话指向不存在的令牌」（死令牌，安全），
--    而不是「活着的、会话不知情的令牌」（无法吊销，危险）
redis.call('HSET', sessionKey, 'accessToken', newAt, 'refreshToken', newRt, 'firstLoginTs', nowMs)
redis.call('EXPIRE', sessionKey, rtTtl)

redis.call('SET', newAtKey, userId, 'EX', atTtl)
redis.call('SET', newRtKey, userId, 'EX', rtTtl)

return 'OK'
