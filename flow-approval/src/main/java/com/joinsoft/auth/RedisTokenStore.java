package com.joinsoft.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Redis Token 存储，启用方式：{@code flow.token-store=redis}。
 */
@Component
@ConditionalOnProperty(name = "flow.token-store", havingValue = "redis")
public class RedisTokenStore implements TokenStore {

    private static final String PREFIX = "flow:token:";

    private final StringRedisTemplate redisTemplate;

    public RedisTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(String token, String userId, long ttlSeconds) {
        redisTemplate.opsForValue().set(PREFIX + token, userId, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String findUserId(String token) {
        return redisTemplate.opsForValue().get(PREFIX + token);
    }

    @Override
    public void remove(String token) {
        redisTemplate.delete(PREFIX + token);
    }

    @Override
    public void removeAll(String userId) {
        Set<String> keys = redisTemplate.keys(PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            String value = redisTemplate.opsForValue().get(key);
            if (userId.equals(value)) {
                redisTemplate.delete(key);
            }
        }
    }
}
