local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local jobId = ARGV[1]
local leaseToken = ARGV[2]

if redis.call('ZSCORE', KEYS[4], jobId) then
    if redis.call('HGET', KEYS[9], jobId) == leaseToken then
        return 2
    end
    return -1
end
if redis.call('ZSCORE', KEYS[3], jobId) then
    return -1
end

local leaseExpires = tonumber(redis.call('ZSCORE', KEYS[2], jobId))
if not leaseExpires then
    return 0
end
if leaseExpires <= now then
    return -2
end
if redis.call('HGET', KEYS[9], jobId) ~= leaseToken then
    return -1
end

redis.call('ZREM', KEYS[2], jobId)
redis.call('HDEL', KEYS[12], jobId)
local retention = tonumber(redis.call('HGET', KEYS[15], jobId)) or tonumber(ARGV[3])
redis.call('ZADD', KEYS[4], now + retention, jobId)
return 1
