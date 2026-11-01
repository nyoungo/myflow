package com.joinsoft.auth;

/**
 * Token 存储抽象：默认内存实现，生产可切换 Redis。
 */
public interface TokenStore {

    void save(String token, String userId, long ttlSeconds);

    /**
     * 返回 token 对应的用户ID，不存在或已过期返回 null。
     */
    String findUserId(String token);

    void remove(String token);

    void removeAll(String userId);
}
