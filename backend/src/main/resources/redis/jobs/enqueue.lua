local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local jobId = ARGV[1]
local deduplicationDigest = ARGV[2]
local payload = ARGV[3]
local signature = ARGV[4]
local notBefore = tonumber(ARGV[5])

if notBefore > now + tonumber(ARGV[10]) then
    return -3
end

local existingJobId = redis.call('HGET', KEYS[10], deduplicationDigest)
if existingJobId then
    if existingJobId ~= jobId then
        return -1
    end
    if redis.call('HGET', KEYS[5], jobId) ~= payload
            or redis.call('HGET', KEYS[6], jobId) ~= signature then
        return -1
    end
    if not redis.call('ZSCORE', KEYS[1], jobId)
            and not redis.call('ZSCORE', KEYS[2], jobId)
            and not redis.call('ZSCORE', KEYS[3], jobId)
            and not redis.call('ZSCORE', KEYS[4], jobId) then
        return -4
    end
    return 0
end

if redis.call('HEXISTS', KEYS[5], jobId) == 1 then
    return -2
end

redis.call('HSET', KEYS[5], jobId, payload)
redis.call('HSET', KEYS[6], jobId, signature)
redis.call('HSET', KEYS[7], jobId, 0)
redis.call('HSET', KEYS[8], jobId, ARGV[6])
redis.call('HDEL', KEYS[9], jobId)
redis.call('HSET', KEYS[10], deduplicationDigest, jobId)
redis.call('HSET', KEYS[11], jobId, deduplicationDigest)
redis.call('HDEL', KEYS[12], jobId)
redis.call('HSET', KEYS[13], jobId, ARGV[7])
redis.call('HSET', KEYS[14], jobId, ARGV[8])
redis.call('HSET', KEYS[15], jobId, ARGV[9])
redis.call('ZADD', KEYS[1], notBefore, jobId)
return 1
