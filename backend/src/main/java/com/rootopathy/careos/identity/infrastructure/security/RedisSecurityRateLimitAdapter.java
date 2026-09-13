package com.rootopathy.careos.identity.infrastructure.security;

import com.rootopathy.careos.identity.application.SecurityRateLimitPort;
import com.rootopathy.careos.identity.application.SecurityTokenPort;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public final class RedisSecurityRateLimitAdapter implements SecurityRateLimitPort {
    private final StringRedisTemplate redisTemplate;
    private final SecurityTokenPort tokenCodec;

    public RedisSecurityRateLimitAdapter(StringRedisTemplate redisTemplate, SecurityTokenPort tokenCodec) {
        this.redisTemplate = redisTemplate;
        this.tokenCodec = tokenCodec;
    }

    @Override
    public boolean consume(String scope, String discriminator, int limit, Duration window) {
        var key = key(scope, discriminator);
        var attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(key, window);
        }
        return attempts != null && attempts <= limit;
    }

    @Override
    public void clear(String scope, String discriminator) {
        redisTemplate.delete(key(scope, discriminator));
    }

    private String key(String scope, String discriminator) {
        return "careos:security:rate:" + scope + ":" + tokenCodec.digest(discriminator);
    }
}
