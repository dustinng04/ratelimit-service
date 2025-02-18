package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Objects;

/**
 * SlidingWindowStrategy - Simple non-atomic implementation.
 * Tracks request timestamps in Redis sorted set.
 * WARNING: This implementation has race conditions - see Phase 3 for atomic version.
 */
public class SlidingWindowStrategy implements RateLimitStrategy {
    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:sliding:";

    public SlidingWindowStrategy(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate cannot be null");
        this.quotaConfig = Objects.requireNonNull(quotaConfig, "quotaConfig cannot be null");
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

        // Non-atomic operations: remove old entries, add current, count
        long windowStart = currentTimeSeconds - windowDurationSeconds;
        
        // Remove expired entries outside window
        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStart);
        
        // Add current timestamp as a member
        redisTemplate.opsForZSet().add(redisKey, String.valueOf(currentTimeSeconds), (double) currentTimeSeconds);
        
        // Set expiration
        redisTemplate.expire(redisKey, quotaConfig.getWindow());
        
        // Count total entries in window
        Long count = redisTemplate.opsForZSet().size(redisKey);
        long requestCount = count != null ? count : 0;
        
        boolean allowed = requestCount <= quotaConfig.getLimit();
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
