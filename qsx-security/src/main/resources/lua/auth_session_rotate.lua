-- 刷新轮换：校验旧 rt -> 双保险比对 session -> 绝对上限判定 -> 原子轮换
--
-- KEYS[1] = 旧 rt 键（按明文 rt 构造）
-- KEYS[2] = 新的 at 键
-- KEYS[3] = 新的 rt 键
-- ARGV[1] = 旧 refresh token 明文
-- ARGV[2] = 新 access token 明文
-- ARGV[3] = 新 refresh token 明文
-- ARGV[4] = at TTL（秒，整数）
-- ARGV[5] = rt TTL（秒，整数）
-- ARGV[6] = 会话绝对上限（毫秒）
-- ARGV[7] = 当前时间戳（毫秒）
-- ARGV[8] = at 键前缀
-- ARGV[9] = rt 键前缀
-- ARGV[10] = session 键前缀（session 键名含 userId，只有进脚本后才知道，
--            因此在前缀基础上于脚本内拼接）
--
-- 返回 'status|userId'，status ∈ OK / INVALID / EXPIRED（INVALID/EXPIRED 时 userId 为空）
--
-- 用单字符串而非 Lua 表返回：Lua 表在 Java 侧只能映射为原始 List（无法带元素泛型），
-- 统一成字符串可让三条脚本保持同一种结果类型

local oldRtKey = KEYS[1]
local newAtKey = KEYS[2]
local newRtKey = KEYS[3]
local rawRt = ARGV[1]
local newAt = ARGV[2]
local newRt = ARGV[3]
local atTtl = tonumber(ARGV[4])
local rtTtl = tonumber(ARGV[5])
local maxLifetimeMs = tonumber(ARGV[6])
local nowMs = tonumber(ARGV[7])
local atPrefix = ARGV[8]
local rtPrefix = ARGV[9]
local sessionPrefix = ARGV[10]

-- 参数校验先于任何写操作（Lua 报错不回滚已执行的写）
if not atTtl or atTtl <= 0 or not rtTtl or rtTtl <= 0
    or not maxLifetimeMs or not nowMs then
    return redis.error_reply('invalid session args')
end

-- 1. rt -> userId（令牌本身即身份来源，无需客户端自报）
local userId = redis.call('GET', oldRtKey)
if not userId then
    return 'INVALID|'
end

local sessionKey = sessionPrefix .. userId

-- 2. 双保险：session 记录的 rt 必须与本次使用的完全一致。
--    拦截「已被新登录覆盖的旧 rt」与「已被轮换掉的旧 rt」；
--    同时它也是「脚本中途失败后残留的旧 rt 键无害」的承重保证
local sessionRt = redis.call('HGET', sessionKey, 'refreshToken')
if sessionRt ~= rawRt then
    return 'INVALID|'
end

-- 3. firstLoginTs 容错读取：缺失/非法视为会话不可信，直接作废（fail-safe）
local firstLoginTs = tonumber(redis.call('HGET', sessionKey, 'firstLoginTs'))
if not firstLoginTs then
    local brokenAt = redis.call('HGET', sessionKey, 'accessToken')
    if brokenAt then
        redis.call('DEL', atPrefix .. brokenAt)
    end
    redis.call('DEL', oldRtKey)
    redis.call('DEL', sessionKey)
    return 'INVALID|'
end

-- 4. 绝对上限：自首次登录起超限则整会话作废（轮换不续命）
if nowMs - firstLoginTs > maxLifetimeMs then
    local oldAt = redis.call('HGET', sessionKey, 'accessToken')
    if oldAt then
        redis.call('DEL', atPrefix .. oldAt)
    end
    redis.call('DEL', oldRtKey)
    redis.call('DEL', sessionKey)
    return 'EXPIRED|'
end

-- 5. 轮换：旧 at/rt 立即失效（保留旧 AT 就是保留那个 30 分钟的踢人漏洞）
local oldAt = redis.call('HGET', sessionKey, 'accessToken')
if oldAt then
    redis.call('DEL', atPrefix .. oldAt)
end
redis.call('DEL', oldRtKey)

-- 6. 先写会话索引再写令牌键，firstLoginTs 保持不变
redis.call('HSET', sessionKey, 'accessToken', newAt, 'refreshToken', newRt)
redis.call('EXPIRE', sessionKey, rtTtl)

redis.call('SET', newAtKey, userId, 'EX', atTtl)
redis.call('SET', newRtKey, userId, 'EX', rtTtl)

return 'OK|' .. userId
