package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

public class TokenBucketStrategy implements RateLimitStrategy {
    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaConfig quotaConfig;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:token:";
    private static final String TOKENS_FIELD = "tokens";
    private static final String LAST_REFILL_FIELD = "lastRefill";

    /**
     * Lua script: atomically refill tokens based on elapsed time, then deduct 1 token.
     * Returns positive remaining tokens if allowed, negative if exceeded.
     */
    private static final String LUA_SCRIPT = 
        "local current_time = tonumber(ARGV[1])\n" +
        "local window_seconds = tonumber(ARGV[2])\n" +
        "local token_limit = tonumber(ARGV[3])\n" +
        "local refill_rate = token_limit / window_seconds\n" +
        "\n" +
        "local tokens_str = redis.call('HGET', KEYS[1], 'tokens')\n" +
        "local last_refill_str = redis.call('HGET', KEYS[1], 'lastRefill')\n" +
        "\n" +
        "local current_tokens = token_limit\n" +
        "local last_refill = current_time\n" +
        "\n" +
        "if tokens_str then current_tokens = tonumber(tokens_str) end\n" +
        "if last_refill_str then last_refill = tonumber(last_refill_str) end\n" +
        "\n" +
        "local elapsed_millis = current_time - last_refill\n" +
        "local elapsed_seconds = elapsed_millis / 1000.0\n" +
        "local tokens_to_add = refill_rate * elapsed_seconds\n" +
        "current_tokens = current_tokens + tokens_to_add\n" +
        "\n" +
        "if current_tokens > token_limit then current_tokens = token_limit end\n" +
        "\n" +
        "local allowed = current_tokens >= 1\n" +
        "if allowed then current_tokens = current_tokens - 1 end\n" +
        "\n" +
        "redis.call('HSET', KEYS[1], 'tokens', tostring(current_tokens))\n" +
        "redis.call('HSET', KEYS[1], 'lastRefill', tostring(current_time))\n" +
        "redis.call('EXPIRE', KEYS[1], window_seconds + 1)\n" +
        "\n" +
        "if allowed then\n" +
        "  return math.floor(current_tokens)\n" +
        "else\n" +
        "  return -math.floor(current_tokens)\n" +
        "end\n";

    private final RedisScript<Long> luaScript;

    public TokenBucketStrategy(RedisTemplate<String, String> redisTemplate, QuotaConfig quotaConfig) {
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
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;
        long currentTimeMillis = System.currentTimeMillis();
        long windowDurationSeconds = quotaConfig.getWindow().toSeconds();
        long tokenLimit = quotaConfig.getLimit();

        // Execute Lua script atomically (result > 0 if allowed, < 0 if denied)
        Long result = redisTemplate.execute(
            luaScript,
            Collections.singletonList(redisKey),
            String.valueOf(currentTimeMillis),
            String.valueOf(windowDurationSeconds),
            String.valueOf(tokenLimit)
        );

        long remainingTokens = Math.abs(result != null ? result : 0);
        boolean allowed = result != null && result > 0;
        int remaining = (int) remainingTokens;

        long tokensNeededToFill = tokenLimit - remainingTokens;
        double refillRate = (double) tokenLimit / windowDurationSeconds;
        long secondsToFill = (long) Math.ceil(tokensNeededToFill / refillRate);
        Instant resetTime = Instant.ofEpochSecond(currentTimeMillis / 1000 + secondsToFill);

        return new RateLimitResponse(allowed, remaining, resetTime);
    }

    @Override
    public void reset(String key) {
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;
        redisTemplate.delete(redisKey);
    }
}


