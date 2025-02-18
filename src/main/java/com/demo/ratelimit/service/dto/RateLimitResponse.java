package com.demo.ratelimit.service.dto;

import java.time.Instant;

public class RateLimitResponse {
    private final boolean allowed;
    private final int remainingRequests;
    private final Instant resetTime;

    public RateLimitResponse(boolean allowed, int remainingRequests, Instant resetTime) {
        this.allowed = allowed;
        this.remainingRequests = remainingRequests;
        this.resetTime = resetTime;
    }

    public int getRemainingRequests() {
        return remainingRequests;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public Instant getResetTime() {
        return resetTime;
    }
}
