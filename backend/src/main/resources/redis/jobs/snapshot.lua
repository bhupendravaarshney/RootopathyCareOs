return {
    tostring(redis.call('ZCARD', KEYS[1])),
    tostring(redis.call('ZCARD', KEYS[2])),
    tostring(redis.call('ZCARD', KEYS[3])),
    tostring(redis.call('ZCARD', KEYS[4]))
}
