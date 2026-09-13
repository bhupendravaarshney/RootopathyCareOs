local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local jobId = ARGV[1]
local leaseToken = ARGV[2]

if redis.call('ZSCORE', KEYS[3], jobId) then
    if redis.call('HGET', KEYS[9], jobId) == leaseToken then
        return 2
    end
    return -1
end
if redis.call('HGET', KEYS[9], jobId) ~= leaseToken
        or not redis.call('ZSCORE', KEYS[2], jobId) then
    return -1
end

redis.call('ZREM', KEYS[2], jobId)
redis.call('HSET', KEYS[12], jobId, 'queue-record-invalid')
local retention = tonumber(redis.call('HGET', KEYS[15], jobId)) or tonumber(ARGV[3])
redis.call('ZADD', KEYS[3], now + retention, jobId)
return 1
