-- 验证码校验：比对 + 用后即焚 / 失败计数作废，一次性原子完成
--
-- KEYS[1] = code 键  KEYS[2] = attempt 键
-- ARGV[1] = 输入码  ARGV[2] = maxAttempts  ARGV[3] = codeTtlSeconds
--
-- 返回：'OK' | 'MISS'（不存在/已过期） | 'WRONG'（填错但还有次数） | 'LOCK'（超限作废）
--
-- 为什么必须原子：GET -> 比对 -> INCR -> DEL 是非原子读改写，
-- 并发提交同一个码时双方都能读到并「校验通过」，再用后即焚就成了空话
-- （即验证码可被双花）。会话改造时 refresh 的非原子读改写踩过同一类坑。

local codeKey = KEYS[1]
local attemptKey = KEYS[2]

local input = ARGV[1]
local maxAttempts = tonumber(ARGV[2])
local codeTtl = tonumber(ARGV[3])

local code = redis.call('GET', codeKey)
if not code then
    return 'MISS'
end

if code == input then
    -- 用后即焚：即便后续业务步骤失败（DB 异常等），这张码也不允许重放
    redis.call('DEL', codeKey)
    redis.call('DEL', attemptKey)
    return 'OK'
end

local fails = redis.call('INCR', attemptKey)
if fails == 1 then
    redis.call('EXPIRE', attemptKey, codeTtl)
end
if fails >= maxAttempts then
    -- 超限作废：码与计数一起删除，用户需重新获取
    redis.call('DEL', codeKey)
    redis.call('DEL', attemptKey)
    return 'LOCK'
end

return 'WRONG'
