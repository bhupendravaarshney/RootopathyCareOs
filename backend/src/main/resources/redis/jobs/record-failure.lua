local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local jobId = ARGV[1]
local leaseToken = ARGV[2]
local reason = ARGV[3]
local defaultRetention = tonumber(ARGV[4])

if redis.call('ZSCORE', KEYS[3], jobId) then
    if redis.call('HGET', KEYS[9], jobId) == leaseToken then
        return 3
    end
    return -1
end
if redis.call('ZSCORE', KEYS[4], jobId) then
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

local attempt = tonumber(redis.call('HGET', KEYS[7], jobId))
local maximumAttempts = tonumber(redis.call('HGET', KEYS[8], jobId))
local initialDelay = tonumber(redis.call('HGET', KEYS[13], jobId))
local maximumDelay = tonumber(redis.call('HGET', KEYS[14], jobId))
local retention = tonumber(redis.call('HGET', KEYS[15], jobId)) or defaultRetention
redis.call('ZREM', KEYS[2], jobId)
redis.call('HSET', KEYS[12], jobId, reason)

if not attempt or not maximumAttempts or not initialDelay or not maximumDelay
        or attempt >= maximumAttempts then
    redis.call('ZADD', KEYS[3], now + retention, jobId)
    return 2
end

local delay = math.min(initialDelay * (2 ^ math.max(attempt - 1, 0)), maximumDelay)
redis.call('HDEL', KEYS[9], jobId)
redis.call('ZADD', KEYS[1], now + delay, jobId)
return 1
