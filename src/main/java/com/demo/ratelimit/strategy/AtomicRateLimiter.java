package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

/**
 * AtomicRateLimiter ensures all operations are atomic using Lua scripts.
 * This prevents race conditions that occur with basic Redis operations (GET-then-INCR).
 * This implementation demonstrates that Lua scripts fix the double-counting issue.
 */
public class AtomicRateLimiter implements RateLimitStrategy {

    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:atomic:";

    /**
     * Lua script for atomic fixed window rate limiting.
     * ALL operations happen atomically:
     * 1. GET current counter
     * 2. Increment counter
     * 3. SET TTL (only on first request)
     * 4. Return result indicating if allowed
     *
     * Because this runs in a single Redis call, there is NO window for concurrent
     * threads to interfere with each other. Result is always correct.
     */
    private static final String LUA_SCRIPT =
        "local current = redis.call('GET', KEYS[1]) or '0'\n" +
        "current = tonumber(current)\n" +
        "current = current + 1\n" +
        "redis.call('SET', KEYS[1], current)\n" +
        "if current == 1 then\n" +
        "  redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
        "end\n" +
        "if current <= tonumber(ARGV[2]) then\n" +
        "  return current\n" +
        "else\n" +
        "  return -current\n" +
        "end\n";

    private final RedisScript<Long> luaScript;

    public AtomicRateLimiter(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate cannot be null");
        this.quotaConfig = Objects.requireNonNull(quotaConfig, "quotaConfig cannot be null");
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

        // ATOMIC OPERATION: The entire check-and-increment happens in a single
        // Redis script execution. No other thread can interfere.
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

        Instant resetTime = Instant.ofEpochSecond(windowStart + quotaConfig.getWindow().toSeconds());

        return new RateLimitResponse(allowed, remaining, resetTime);
    }

    @Override
    public void reset(String key) {
        long windowStart = calculateWindowStart();
        String redisKey = generateRedisKey(key, windowStart);
        redisTemplate.delete(redisKey);
    }

    private long calculateWindowStart() {
        long windowDurationSeconds = quotaConfig.getWindow().toSeconds();
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        return (currentTimeSeconds / windowDurationSeconds) * windowDurationSeconds;
    }

    private String generateRedisKey(String key, long windowStart) {
        return RATE_LIMIT_KEY_PREFIX + key + ":" + windowStart;
    }
}


