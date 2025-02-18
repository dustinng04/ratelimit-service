package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Factory for creating rate limit strategy instances based on configuration
 */
@Component
public class RateLimitStrategyFactory {
    private final RedisTemplate<String, String> redisTemplate;

    public RateLimitStrategyFactory(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate cannot be null");
    }

    /**
     * Create a rate limit strategy based on the quota configuration
     *
     * @param quotaConfig the quota configuration specifying strategy type, limit, and window
     * @return an instance of the appropriate RateLimitStrategy
     * @throws IllegalArgumentException if strategy type is not supported
     */
    public RateLimitStrategy createStrategy(QuotaConfig quotaConfig) {
        Objects.requireNonNull(quotaConfig, "quotaConfig cannot be null");

        return switch (quotaConfig.getStrategy()) {
            case FIXED_WINDOW -> new FixedWindowStrategy(redisTemplate, quotaConfig);
            case SLIDING_WINDOW -> new SlidingWindowStrategy(redisTemplate, quotaConfig);
            case TOKEN_BUCKET -> new TokenBucketStrategy(redisTemplate, quotaConfig);
            case LEAKY_BUCKET -> throw new IllegalArgumentException("LEAKY_BUCKET strategy not yet implemented");
        };
    }
}

