package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * NonAtomicRateLimiter demonstrates race conditions using basic Redis operations.
 * Uses GET-then-SET pattern (non-atomic), showing double-counting under concurrent load.
 * This is the "naive" approach that should NOT be used in production.
 */
public class NonAtomicRateLimiter implements RateLimitStrategy {

    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:nonatomic:";

    public NonAtomicRateLimiter(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate cannot be null");
        this.quotaConfig = Objects.requireNonNull(quotaConfig, "quotaConfig cannot be null");
    }

    @Override
    public boolean isAllowed(String key) {
        return getRateLimit(key).isAllowed();
    }

    @Override
    public RateLimitResponse getRateLimit(String key) {
        long windowStart = calculateWindowStart();
        String redisKey = generateRedisKey(key, windowStart);

        // RACE CONDITION WINDOW: Between GET and INCR, multiple threads can see same value

        // Step 1: GET current count
        String countStr = (String) redisTemplate.opsForValue().get(redisKey);
        long currentCount = countStr != null ? Long.parseLong(countStr) : 0;

        // RACE CONDITION HERE: Multiple threads can read the same count before increment
        // Thread A sees count=5, Thread B sees count=5 (both think they're allowed)
        // Both then increment, resulting in count=7 instead of expected behavior

        // Step 2: Check if allowed (before incrementing)
        boolean allowed = currentCount < quotaConfig.getLimit();

        // Step 3: INCR the counter
        Long newCount = redisTemplate.opsForValue().increment(redisKey);

        // Step 4: SET expiration if first request
        if (newCount != null && newCount == 1) {
            long ttl = quotaConfig.getWindow().toSeconds();
            redisTemplate.expire(redisKey, ttl, TimeUnit.SECONDS);
        }

        int remaining = Math.max(0, quotaConfig.getLimit() - (int) (long) newCount);
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

