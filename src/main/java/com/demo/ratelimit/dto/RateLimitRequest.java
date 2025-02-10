package com.demo.ratelimit.dto;

import java.time.Instant;

public class RateLimitRequest {
    private final int userId;
    private final String key;
    private final Instant timestamp;

    public RateLimitRequest(int userId, String key, Instant timestamp) {
        this.userId = userId;
        this.key = key;
        this.timestamp = timestamp;
    }

    public int getUserId() {
        return userId;
    }

    public String getKey() {
        return key;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
