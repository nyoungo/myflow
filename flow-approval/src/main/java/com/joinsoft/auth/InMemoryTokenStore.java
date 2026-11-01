package com.joinsoft.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 Token 存储，用于本地开发与测试，无需 Redis。
 */
@Component
@ConditionalOnProperty(name = "flow.token-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryTokenStore implements TokenStore {

    private static final class Entry {
        private final String userId;
        private final long expireAt;

        private Entry(String userId, long expireAt) {
            this.userId = userId;
            this.expireAt = expireAt;
        }
    }

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    @Override
    public void save(String token, String userId, long ttlSeconds) {
        tokens.put(token, new Entry(userId, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    @Override
    public String findUserId(String token) {
        Entry entry = tokens.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt < System.currentTimeMillis()) {
            tokens.remove(token);
            return null;
        }
        return entry.userId;
    }

    @Override
    public void remove(String token) {
        tokens.remove(token);
    }

    @Override
    public void removeAll(String userId) {
        tokens.entrySet().removeIf(entry -> entry.getValue().userId.equals(userId));
    }
}
