package com.demo.ratelimit.controller;

import com.demo.ratelimit.service.RateLimitService;
import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitRequest;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * RateLimitController exposes REST endpoints for rate limiting operations.
 * Includes debugging endpoints to inspect rate limit state.
 */
@RestController
@RequestMapping("/api/ratelimit")
public class RateLimitController {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitController.class);

    private final RateLimitService rateLimitService;

    public RateLimitController(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/check")
    public ResponseEntity<?> checkRateLimit(
            @RequestParam String key,
            @RequestParam(defaultValue = "FIXED_WINDOW") String strategy,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "60") long windowSeconds) {

        try {
            logger.info("Checking rate limit for key: {} with strategy: {}", key, strategy);

            // Create quota configuration
            QuotaConfig.Strategy strategyEnum = QuotaConfig.Strategy.valueOf(strategy.toUpperCase());
            QuotaConfig quotaConfig = new QuotaConfig(strategyEnum, limit, Duration.ofSeconds(windowSeconds));

            // Create rate limit request
            RateLimitRequest request = new RateLimitRequest(1, key, Instant.now());

            // Check rate limit
            RateLimitResponse response = rateLimitService.checkRateLimit(request, quotaConfig);

            // Return response with appropriate HTTP status
            if (response.isAllowed()) {
                logger.debug("Request allowed for key: {} | remaining: {}", key, response.getRemainingRequests());
                return ResponseEntity.ok(buildResponseBody(true, response, quotaConfig));
            } else {
                logger.debug("Request throttled for key: {} | retry after: {}", key, response.getResetTime());
                return ResponseEntity
                        .status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(buildResponseBody(false, response, quotaConfig));
            }
        } catch (IllegalArgumentException e) {
            logger.error("Invalid strategy: {}", strategy, e);
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Invalid strategy: " + strategy));
        } catch (Exception e) {
            logger.error("Unexpected error checking rate limit for key: {}", key, e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> resetRateLimit(
            @RequestParam String key,
            @RequestParam(defaultValue = "FIXED_WINDOW") String strategy,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "60") long windowSeconds) {

        try {
            logger.info("Resetting rate limit for key: {}", key);

            QuotaConfig.Strategy strategyEnum = QuotaConfig.Strategy.valueOf(strategy.toUpperCase());
            QuotaConfig quotaConfig = new QuotaConfig(strategyEnum, limit, Duration.ofSeconds(windowSeconds));

            rateLimitService.resetRateLimit(key, quotaConfig);

            return ResponseEntity.ok(Map.of(
                    "message", "Rate limit reset",
                    "key", key,
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            logger.error("Error resetting rate limit for key: {}", key, e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to reset rate limit"));
        }
    }

    @GetMapping("/debug")
    public ResponseEntity<?> debugRateLimit(
            @RequestParam String key,
            @RequestParam(defaultValue = "FIXED_WINDOW") String strategy,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "60") long windowSeconds) {

        try {
            logger.debug("Debug request for key: {} with strategy: {}", key, strategy);

            QuotaConfig.Strategy strategyEnum = QuotaConfig.Strategy.valueOf(strategy.toUpperCase());
            QuotaConfig quotaConfig = new QuotaConfig(strategyEnum, limit, Duration.ofSeconds(windowSeconds));

            Map<String, Object> debugInfo = rateLimitService.getDebugInfo(key, quotaConfig);

            return ResponseEntity.ok(debugInfo);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid strategy for debug: {}", strategy, e);
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Invalid strategy: " + strategy));
        } catch (Exception e) {
            logger.error("Error getting debug info for key: {}", key, e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve debug information"));
        }
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "RateLimitService",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Build response body with all relevant information.
     */
    private Map<String, Object> buildResponseBody(boolean allowed, RateLimitResponse response, QuotaConfig quotaConfig) {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("allowed", allowed);
        responseBody.put("remainingRequests", response.getRemainingRequests());
        responseBody.put("resetTime", response.getResetTime().toString());
        responseBody.put("limit", quotaConfig.getLimit());
        responseBody.put("window", quotaConfig.getWindow().toString());
        responseBody.put("strategy", quotaConfig.getStrategy().toString());
        responseBody.put("timestamp", System.currentTimeMillis());
        return responseBody;
    }
}

