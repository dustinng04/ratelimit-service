package com.demo.ratelimit.dto;

import java.time.Duration;

public class QuotaConfig {
    public enum Strategy {
        FIXED_WINDOW,
        SLIDING_WINDOW,
        TOKEN_BUCKET,
        LEAKY_BUCKET
    }
    private final Strategy strategy;
    private final int limit;
    private final Duration window;

    public QuotaConfig(Strategy strategy, int limit, Duration window) {
        this.strategy = strategy;
        this.limit = limit;
        this.window = window;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public int getLimit() {
        return limit;
    }

    public Duration getWindow() {
        return window;
    }

}
