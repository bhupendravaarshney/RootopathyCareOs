local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local maximumClaims = tonumber(ARGV[1])
local leaseDuration = tonumber(ARGV[2])
local recoveryBatch = tonumber(ARGV[3])
local defaultRetention = tonumber(ARGV[4])
local recoveredForRetry = 0
local recoveredToDeadLetter = 0
local corruptToDeadLetter = 0

local function retryDelay(attempt, initialDelay, maximumDelay)
    return math.min(initialDelay * (2 ^ math.max(attempt - 1, 0)), maximumDelay)
end

local function recordIsComplete(id)
    local deduplicationDigest = redis.call('HGET', KEYS[11], id)
    return redis.call('HGET', KEYS[5], id)
            and redis.call('HGET', KEYS[6], id)
            and redis.call('HGET', KEYS[7], id)
            and redis.call('HGET', KEYS[8], id)
            and redis.call('HGET', KEYS[13], id)
            and redis.call('HGET', KEYS[14], id)
            and redis.call('HGET', KEYS[15], id)
            and deduplicationDigest
            and redis.call('HGET', KEYS[10], deduplicationDigest) == id
end

local function deadLetter(id, reason)
    redis.call('ZREM', KEYS[1], id)
    redis.call('ZREM', KEYS[2], id)
    redis.call('HDEL', KEYS[9], id)
    redis.call('HSET', KEYS[12], id, reason)
    local retention = tonumber(redis.call('HGET', KEYS[15], id)) or defaultRetention
    redis.call('ZADD', KEYS[3], now + retention, id)
end

local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', now, 'LIMIT', 0, recoveryBatch)
for _, id in ipairs(expired) do
    if not recordIsComplete(id) then
        deadLetter(id, 'queue-record-invalid')
        corruptToDeadLetter = corruptToDeadLetter + 1
    else
        local attempt = tonumber(redis.call('HGET', KEYS[7], id))
        local maximumAttempts = tonumber(redis.call('HGET', KEYS[8], id))
        if not attempt or not maximumAttempts or attempt >= maximumAttempts then
            deadLetter(id, 'job-lease-expired')
            recoveredToDeadLetter = recoveredToDeadLetter + 1
        else
            local initialDelay = tonumber(redis.call('HGET', KEYS[13], id))
            local maximumDelay = tonumber(redis.call('HGET', KEYS[14], id))
            redis.call('ZREM', KEYS[2], id)
            redis.call('HDEL', KEYS[9], id)
            redis.call('HSET', KEYS[12], id, 'job-lease-expired')
            redis.call('ZADD', KEYS[1], now + retryDelay(attempt, initialDelay, maximumDelay), id)
            recoveredForRetry = recoveredForRetry + 1
        end
    end
end

local response = {
    tostring(recoveredForRetry),
    tostring(recoveredToDeadLetter),
    tostring(corruptToDeadLetter)
}
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, maximumClaims)
local leaseExpires = now + leaseDuration
for index, id in ipairs(due) do
    if not recordIsComplete(id) then
        deadLetter(id, 'queue-record-invalid')
        corruptToDeadLetter = corruptToDeadLetter + 1
        response[3] = tostring(corruptToDeadLetter)
    else
        local payload = redis.call('HGET', KEYS[5], id)
        local signature = redis.call('HGET', KEYS[6], id)
        local attempt = tonumber(redis.call('HGET', KEYS[7], id)) + 1
        local leaseToken = ARGV[4 + index]
        redis.call('ZREM', KEYS[1], id)
        redis.call('HSET', KEYS[7], id, attempt)
        redis.call('HSET', KEYS[9], id, leaseToken)
        redis.call('ZADD', KEYS[2], leaseExpires, id)
        table.insert(response, id)
        table.insert(response, leaseToken)
        table.insert(response, tostring(attempt))
        table.insert(response, tostring(leaseExpires))
        table.insert(response, payload)
        table.insert(response, signature)
    end
end
return response
