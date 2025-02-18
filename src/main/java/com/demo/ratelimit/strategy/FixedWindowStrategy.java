package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Objects;

/**
 * FixedWindowStrategy - Simple non-atomic implementation.
 * WARNING: This implementation has race conditions - see Phase 3 for atomic version.
 */
public class FixedWindowStrategy implements RateLimitStrategy {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:fixed:";

    public FixedWindowStrategy(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
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
        
        // Non-atomic operations: GET then INCR (race condition possible)
        String currentStr = redisTemplate.opsForValue().get(redisKey);
        long currentCount = currentStr != null ? Long.parseLong(currentStr) : 0;

        // Increment counter
        currentCount++;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(currentCount));
        redisTemplate.expire(redisKey, quotaConfig.getWindow());

        boolean allowed = currentCount <= quotaConfig.getLimit();
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
