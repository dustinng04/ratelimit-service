package com.demo.ratelimit.service;

import com.demo.ratelimit.metrics.MetricsCollector;
import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitRequest;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import com.demo.ratelimit.strategy.RateLimitStrategy;
import com.demo.ratelimit.strategy.RateLimitStrategyFactory;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitService.class);
    private static final Logger rateLimitLogger = LoggerFactory.getLogger("com.demo.ratelimit.decisions");

    private final RateLimitStrategyFactory strategyFactory;
    private final MetricsCollector metricsCollector;
    private final Map<String, RateLimitStrategy> strategyCache = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitStrategyFactory strategyFactory, MetricsCollector metricsCollector) {
        this.strategyFactory = strategyFactory;
        this.metricsCollector = metricsCollector;
    }

    /**
     * Check if a request is allowed under the given quota configuration.
     */
    public RateLimitResponse checkRateLimit(RateLimitRequest request, QuotaConfig quotaConfig) {
        Timer.Sample sample = metricsCollector.recordLatencyStart();

        try {
            String key = request.getKey();
            RateLimitStrategy strategy = getOrCreateStrategy(quotaConfig);
            RateLimitResponse response = strategy.getRateLimit(key);

            // Record metrics
            metricsCollector.recordRequest(response.isAllowed());

            // Structured logging
            logRateLimitDecision(request, quotaConfig, response);

            return response;
        } finally {
            metricsCollector.recordLatencyStop(sample);
        }
    }

//    Reset the rate limit counter for a key
    public void resetRateLimit(String key, QuotaConfig quotaConfig) {
        RateLimitStrategy strategy = getOrCreateStrategy(quotaConfig);
        strategy.reset(key);
        logger.info("Reset rate limit for key: {} with strategy: {}", key, quotaConfig.getStrategy());
    }

    /**
     * Get rate limit state information for debugging.
     *
     * @param key the rate limit key
     * @param quotaConfig the quota configuration
     * @return map with debugging information
     */
    public Map<String, Object> getDebugInfo(String key, QuotaConfig quotaConfig) {
        Map<String, Object> debugInfo = new HashMap<>();
        RateLimitStrategy strategy = getOrCreateStrategy(quotaConfig);
        RateLimitResponse response = strategy.getRateLimit(key);

        debugInfo.put("key", key);
        debugInfo.put("strategy", quotaConfig.getStrategy().toString());
        debugInfo.put("limit", quotaConfig.getLimit());
        debugInfo.put("window", quotaConfig.getWindow().toString());
        debugInfo.put("allowed", response.isAllowed());
        debugInfo.put("remainingRequests", response.getRemainingRequests());
        debugInfo.put("resetTime", response.getResetTime().toString());
        debugInfo.put("timestamp", System.currentTimeMillis());

        logger.debug("Generated debug info for key: {}", key);
        return debugInfo;
    }

    /**
     * Get or create a rate limit strategy from cache.
     */
    private RateLimitStrategy getOrCreateStrategy(QuotaConfig quotaConfig) {
        String cacheKey = quotaConfig.getStrategy().toString() + ":" + quotaConfig.getLimit() + ":" + quotaConfig.getWindow();
        return strategyCache.computeIfAbsent(cacheKey, k -> strategyFactory.createStrategy(quotaConfig));
    }

    /**
     * Log rate limit decision with structured information.
     */
    private void logRateLimitDecision(RateLimitRequest request, QuotaConfig quotaConfig, RateLimitResponse response) {
        if (response.isAllowed()) {
            rateLimitLogger.info(
                    "REQUEST_ALLOWED | key={} | userId={} | strategy={} | remaining={} | resetTime={}",
                    request.getKey(),
                    request.getUserId(),
                    quotaConfig.getStrategy(),
                    response.getRemainingRequests(),
                    response.getResetTime()
            );
        } else {
            rateLimitLogger.warn(
                    "REQUEST_THROTTLED | key={} | userId={} | strategy={} | limit={} | window={} | resetTime={}",
                    request.getKey(),
                    request.getUserId(),
                    quotaConfig.getStrategy(),
                    quotaConfig.getLimit(),
                    quotaConfig.getWindow(),
                    response.getResetTime()
            );
        }
    }
}

