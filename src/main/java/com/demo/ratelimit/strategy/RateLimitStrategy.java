package com.demo.ratelimit.strategy;

import com.demo.ratelimit.service.dto.RateLimitResponse;

public interface RateLimitStrategy {
    boolean isAllowed(String key);
    RateLimitResponse getRateLimit(String key);
    void reset(String key);
}
