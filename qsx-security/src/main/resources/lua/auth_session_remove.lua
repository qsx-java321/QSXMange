-- 会话清理：登出 / 踢人 / 禁用 / 删除用户 / 改密 统一入口
--
-- KEYS[1] = session 键
-- ARGV[1] = at 键前缀
-- ARGV[2] = rt 键前缀
--
-- 返回 'OK'（幂等：会话不存在时同样是 OK）

local sessionKey = KEYS[1]
local atPrefix = ARGV[1]
local rtPrefix = ARGV[2]

-- 用 HGET 逐字段取，不用 HGETALL（RESP2 下后者是数组，字段访问恒为 nil，
-- 结果就是 AT 键永远删不掉——「即时踢下线」静默失效）
local at = redis.call('HGET', sessionKey, 'accessToken')
local rt = redis.call('HGET', sessionKey, 'refreshToken')

if at then
    redis.call('DEL', atPrefix .. at)
end
if rt then
    redis.call('DEL', rtPrefix .. rt)
end
redis.call('DEL', sessionKey)

return 'OK'
