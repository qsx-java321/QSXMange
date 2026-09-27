-- 验证码发送：限流 + 落码 + 清零错次，一次性原子完成
--
-- KEYS[1] = code 键              KEYS[2] = attempt 键
-- KEYS[3] = limit 键（60s 间隔）  KEYS[4] = daily 键（日期串）
-- ARGV[1] = code
-- ARGV[2] = codeTtlSeconds  ARGV[3] = gapTtlSeconds
-- ARGV[4] = dailyTtlSeconds ARGV[5] = dailyLimit
--
-- 返回：'OK' | 'GAP'（间隔未到） | 'QUOTA'（当日超限）
--
-- 为什么必须整段原子（而不是 Java 侧 INCR + EXPIRE）：
--   ① INCR 成功而 EXPIRE 未执行（进程中断 / 连接断开）会留下**永不过期**的键，
--      该 {scene}:{email} 之后再也发不出验证码，且没有任何报错；
--   ② 若每次发送都 EXPIRE 86400，日限就变成滑动窗口，持续调用者永远撞不到上限。
--   因此：日限用日期串键（跨天自然换键），TTL 只在计数为 1 时设置。
--
-- 参数约定（同会话脚本）：ARGV 全为字符串，脚本内 tonumber()。

local codeKey = KEYS[1]
local attemptKey = KEYS[2]
local limitKey = KEYS[3]
local dailyKey = KEYS[4]

local code = ARGV[1]
local codeTtl = tonumber(ARGV[2])
local gapTtl = tonumber(ARGV[3])
local dailyTtl = tonumber(ARGV[4])
local dailyLimit = tonumber(ARGV[5])

-- 60s 间隔：SET NX 原子占位，占不到说明刚发过。
-- 注意判定写法：RESP2 下 SET 成功返回的是 table（{ok='OK'}）而非字符串 'OK'，
-- 写成 `== 'OK'` 会恒为 false；失败返回 nil（Lua 中为 false）。故只能用 `not acquired`。
local acquired = redis.call('SET', limitKey, '1', 'NX', 'EX', gapTtl)
if not acquired then
    return 'GAP'
end

local sent = redis.call('INCR', dailyKey)
if sent == 1 then
    redis.call('EXPIRE', dailyKey, dailyTtl)
end
if sent > dailyLimit then
    -- 刻意不回滚已占用的 limit 键：该用户当日已到顶，60s 间隔已无意义
    return 'QUOTA'
end

redis.call('SET', codeKey, code, 'EX', codeTtl)
-- 重新发码即重置错误计数：新码应享有完整的 maxAttempts 次机会
redis.call('DEL', attemptKey)

return 'OK'
