package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/**
 * SlidingWindowStrategy - atomic add/remove/count via Lua script.
 * Prevents race conditions in concurrent timestamp operations.
 */
public class SlidingWindowStrategy implements RateLimitStrategy {
    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:sliding:";

    /**
     * Lua script: atomically remove expired entries, add current timestamp, and count.
     * Returns positive count if allowed, negative if exceeded.
     */
    private static final String LUA_SCRIPT = 
        "local current_time = tonumber(ARGV[1])\n" +
        "local window_seconds = tonumber(ARGV[2])\n" +
        "local limit = tonumber(ARGV[3])\n" +
        "local request_id = ARGV[4]\n" +
        "local window_start = current_time - window_seconds\n" +
        "\n" +
        "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, window_start)\n" +
        "redis.call('ZADD', KEYS[1], current_time, request_id)\n" +
        "local count = redis.call('ZCARD', KEYS[1])\n" +
        "redis.call('EXPIRE', KEYS[1], window_seconds + 1)\n" +
        "\n" +
        "if count <= limit then\n" +
        "  return count\n" +
        "else\n" +
        "  return -count\n" +
        "end\n";

    private final RedisScript<Long> luaScript;

    public SlidingWindowStrategy(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate cannot be null");
        this.quotaConfig = Objects.requireNonNull(quotaConfig, "quotaConfig cannot be null");

        // Create RedisScript for Lua evaluation
        this.luaScript = RedisScript.of(LUA_SCRIPT, Long.class);
    }

    @Override
    public boolean isAllowed(String key) {
        return getRateLimit(key).isAllowed();
    }

    @Override
    public RateLimitResponse getRateLimit(String key) {
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        long windowDurationSeconds = quotaConfig.getWindow().toSeconds();
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;

        Long result = redisTemplate.execute(
            luaScript,
            Collections.singletonList(redisKey),
            String.valueOf(currentTimeSeconds),
            String.valueOf(windowDurationSeconds),
            String.valueOf(quotaConfig.getLimit()),
            UUID.randomUUID().toString()
        );

        long requestCount = Math.abs(result != null ? result : 0);
        boolean allowed = result != null && result > 0;
        int remaining = Math.max(0, quotaConfig.getLimit() - (int) requestCount);

        long resetTimeSeconds = currentTimeSeconds + windowDurationSeconds;
        Instant resetTime = Instant.ofEpochSecond(resetTimeSeconds);
        
        return new RateLimitResponse(allowed, remaining, resetTime);
    }

    @Override
    public void reset(String key) {
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;
        redisTemplate.delete(redisKey);
    }
}
