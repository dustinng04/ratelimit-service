package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

/**
 * FixedWindowStrategy using Lua script for atomic operations.
 * CRITICAL: The Lua script ensures that increment-and-check happens atomically,
 * preventing the GET-then-INCR race condition that allows double-counting.
 */
public class FixedWindowStrategy implements RateLimitStrategy {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:fixed:";
    
    /**
     * Lua script for atomic fixed window rate limiting.
     * IMPORTANCE: This script executes atomically in Redis, preventing race conditions.
     *
     * LOGIC:
     * 1. Get current counter value (or 0 if not exists)
     * 2. Increment counter by 1
     * 3. Set TTL if this is the first request (count == 1)
     * 4. Return 1 if within limit, 0 if exceeded
     *
     * This is guaranteed to execute as a single atomic operation in Redis,
     * so multiple concurrent threads see serialized increments.
     */
    private static final String LUA_SCRIPT =
        "local current = redis.call('GET', KEYS[1]) or '0'\n" +
        "current = tonumber(current)\n" +
        "current = current + 1\n" +
        "redis.call('SET', KEYS[1], current)\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
        "if current <= tonumber(ARGV[2]) then\n" +
        "  return current\n" +
        "else\n" +
        "  return -current\n" +
        "end\n";

    private final RedisScript<Long> luaScript;

    public FixedWindowStrategy(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
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
        long windowStart = calculateWindowStart();
        String redisKey = generateRedisKey(key, windowStart);
        
        // ATOMIC OPERATION: Execute Lua script atomically in Redis
        // This ensures that the increment-and-check cannot be interrupted by concurrent requests
        long ttl = quotaConfig.getWindow().toSeconds();
        long result = redisTemplate.execute(
            luaScript,
            Collections.singletonList(redisKey),
            String.valueOf(ttl),
            String.valueOf(quotaConfig.getLimit())
        );

        // INTERPRETATION:
        // result > 0: request was allowed, value is the current count
        // result < 0: request was denied, abs(value) is the current count
        long currentCount = Math.abs(result);
        boolean allowed = result > 0;

        int remaining = Math.max(0, quotaConfig.getLimit() - (int) currentCount);

        // Calculate reset time (end of current window)
        Instant resetTime = Instant.ofEpochSecond(windowStart + quotaConfig.getWindow().toSeconds());
        
        return new RateLimitResponse(allowed, remaining, resetTime);
    }
    
    @Override
    public void reset(String key) {
        long windowStart = calculateWindowStart();
        String redisKey = generateRedisKey(key, windowStart);
        redisTemplate.delete(redisKey);
    }
    
    /**
     * Calculate the start of the current fixed window
     * For example, window = 60 secs & current time is 1683000045 -> 1683000000
     */
    private long calculateWindowStart() {
        long windowDurationSeconds = quotaConfig.getWindow().toSeconds();
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        return (currentTimeSeconds / windowDurationSeconds) * windowDurationSeconds;
    }

    private String generateRedisKey(String key, long windowStart) {
        return RATE_LIMIT_KEY_PREFIX + key + ":" + windowStart;
    }
}
