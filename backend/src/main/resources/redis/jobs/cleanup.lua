local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local maximum = tonumber(ARGV[1])

local function purge(stateKey)
    local ids = redis.call('ZRANGEBYSCORE', stateKey, '-inf', now, 'LIMIT', 0, maximum)
    for _, id in ipairs(ids) do
        redis.call('ZREM', stateKey, id)
        local deduplicationDigest = redis.call('HGET', KEYS[11], id)
        if deduplicationDigest and redis.call('HGET', KEYS[10], deduplicationDigest) == id then
            redis.call('HDEL', KEYS[10], deduplicationDigest)
        end
        for metadataKey = 5, 9 do
            redis.call('HDEL', KEYS[metadataKey], id)
        end
        redis.call('HDEL', KEYS[11], id)
        for metadataKey = 12, 15 do
            redis.call('HDEL', KEYS[metadataKey], id)
        end
    end
    return #ids
end

return purge(KEYS[3]) + purge(KEYS[4])
