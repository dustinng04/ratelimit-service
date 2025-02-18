package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Objects;

/**
 * TokenBucketStrategy - Simple non-atomic implementation.
 * Stores token count and last refill timestamp in a Redis hash.
 * WARNING: This implementation has race conditions - see Phase 3 for atomic version.
 */
public class TokenBucketStrategy implements RateLimitStrategy {
    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:token:";
    private static final String TOKENS_FIELD = "tokens";
    private static final String LAST_REFILL_FIELD = "lastRefill";

    public TokenBucketStrategy(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate cannot be null");
        this.quotaConfig = Objects.requireNonNull(quotaConfig, "quotaConfig cannot be null");
    }

    @Override
    public boolean isAllowed(String key) {
        return getRateLimit(key).isAllowed();
    }

    @Override
    public RateLimitResponse getRateLimit(String key) {
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;
        long currentTimeMillis = System.currentTimeMillis();
        long windowDurationSeconds = quotaConfig.getWindow().toSeconds();
        long tokenLimit = quotaConfig.getLimit();

        // Non-atomic operations: read, calculate, update (race condition possible)

        // Get current token count and last refill time
        String tokensStr = redisTemplate.opsForHash().get(redisKey, TOKENS_FIELD) != null ?
            (String) redisTemplate.opsForHash().get(redisKey, TOKENS_FIELD) : null;
        String lastRefillStr = redisTemplate.opsForHash().get(redisKey, LAST_REFILL_FIELD) != null ?
            (String) redisTemplate.opsForHash().get(redisKey, LAST_REFILL_FIELD) : null;

        double currentTokens = tokenLimit;
        long lastRefill = currentTimeMillis;

        if (tokensStr != null) {
            currentTokens = Double.parseDouble(tokensStr);
        }
        if (lastRefillStr != null) {
            lastRefill = Long.parseLong(lastRefillStr);
        }

        // Calculate tokens to add based on elapsed time
        double refillRate = (double) tokenLimit / windowDurationSeconds;
        long elapsedMillis = currentTimeMillis - lastRefill;
        double elapsedSeconds = elapsedMillis / 1000.0;
        double tokensToAdd = refillRate * elapsedSeconds;
        currentTokens += tokensToAdd;

        // Cap at token limit
        if (currentTokens > tokenLimit) {
            currentTokens = tokenLimit;
        }

        // Deduct 1 token if allowed
        boolean allowed = currentTokens >= 1;
        if (allowed) {
            currentTokens -= 1;
        }

        // Update Redis with new state
        redisTemplate.opsForHash().put(redisKey, TOKENS_FIELD, String.valueOf(currentTokens));
        redisTemplate.opsForHash().put(redisKey, LAST_REFILL_FIELD, String.valueOf(currentTimeMillis));
        redisTemplate.expire(redisKey, quotaConfig.getWindow());

        int remaining = (int) Math.floor(currentTokens);

        // Calculate reset time (when bucket will be full again)
        long tokensNeededToFill = tokenLimit - (long) Math.floor(currentTokens);
        double refillTime = tokensNeededToFill / refillRate;
        long secondsToFill = Math.round(Math.ceil(refillTime));
        Instant resetTime = Instant.ofEpochSecond(currentTimeMillis / 1000 + secondsToFill);

        return new RateLimitResponse(allowed, remaining, resetTime);
    }

    @Override
    public void reset(String key) {
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;
        redisTemplate.delete(redisKey);
    }
}


