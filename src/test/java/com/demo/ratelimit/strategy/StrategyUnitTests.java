package com.demo.ratelimit.strategy;

import com.demo.ratelimit.metrics.MetricsCollector;
import com.demo.ratelimit.service.RateLimitService;
import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitRequest;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Unit Tests for Rate Limiting Strategies
 * Phase 5 - Testing & Validation - Unit Tests
 * Tests cover:
 * 1. Requests within limit (allowed)
 * 2. Requests over limit (denied)
 * 3. Window boundary behavior
 * 4. Reset functionality
 * 5. Metrics recording
 */
@SpringBootTest
@ActiveProfiles("test")
class StrategyUnitTests {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RateLimitStrategyFactory strategyFactory;

    @Autowired
    private MetricsCollector metricsCollector;

    @Autowired
    private RateLimitService rateLimitService;

    private static final String TEST_KEY = "unit-test-key";
    private static final int LIMIT = 5;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    @BeforeEach
    void setUp() {
        // Clean up Redis before each test
        clearAllTestKeys();
    }

    private void clearAllTestKeys() {
        // Clear fixed window keys for current and adjacent windows (in case of timing issues)
        long currentTime = System.currentTimeMillis() / 1000;
        long windowSize = WINDOW.toSeconds();
        for (long offset = -1; offset <= 1; offset++) {
            long windowStart = ((currentTime + offset * windowSize) / windowSize) * windowSize;
            String fixedKey = "ratelimit:fixed:" + TEST_KEY + ":" + windowStart;
            redisTemplate.delete(fixedKey);
        }

        // Clear sliding window
        redisTemplate.delete("ratelimit:sliding:" + TEST_KEY);

        // Clear token bucket
        redisTemplate.delete("ratelimit:token:" + TEST_KEY);
    }

    // ======================== FIXED WINDOW STRATEGY TESTS ========================

    @Nested
    @DisplayName("FixedWindowStrategy Tests")
    class FixedWindowStrategyTests {

        private FixedWindowStrategy strategy;
        private QuotaConfig config;
        private String testKey = "fixed-window-test";

        @BeforeEach
        void setUp() {
            clearTestKey(testKey);
            config = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, LIMIT, WINDOW);
            strategy = new FixedWindowStrategy(redisTemplate, config);
        }

        @AfterEach
        void tearDown() {
            clearTestKey(testKey);
        }

        private void clearTestKey(String key) {
            long currentTime = System.currentTimeMillis() / 1000;
            long windowSize = WINDOW.toSeconds();
            for (long offset = -1; offset <= 1; offset++) {
                long windowStart = ((currentTime + offset * windowSize) / windowSize) * windowSize;
                String fixedKey = "ratelimit:fixed:" + key + ":" + windowStart;
                redisTemplate.delete(fixedKey);
            }
        }

        @Test
        @DisplayName("Should allow requests within limit")
        void testRequestsWithinLimit() {
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(testKey);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
                assertTrue(response.getRemainingRequests() >= 0, "Remaining requests should be non-negative");
            }
        }

        @Test
        @DisplayName("Should deny requests over limit")
        void testRequestsOverLimit() {
            // Make LIMIT requests (should all be allowed)
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(testKey);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
            }

            // Next request should be denied
            RateLimitResponse deniedResponse = strategy.getRateLimit(testKey);
            assertFalse(deniedResponse.isAllowed(), "Request over limit should be denied");
            assertEquals(0, deniedResponse.getRemainingRequests(), "Remaining requests should be 0");
        }

        @Test
        @DisplayName("Should show correct remaining requests")
        void testRemainingRequests() {
            // First call - we accept 1 request, leaving (limit - 1)
            RateLimitResponse initial = strategy.getRateLimit(testKey);
            assertTrue(initial.isAllowed(), "First request should be allowed");
            assertEquals(LIMIT - 1, initial.getRemainingRequests(),
                    "After 1st request should have (limit - 1)");

            // Second call  - we accept 1 request, leaving (limit - 2)
            RateLimitResponse second = strategy.getRateLimit(testKey);
            assertTrue(second.isAllowed(), "Second request should be allowed");
            assertEquals(LIMIT - 2, second.getRemainingRequests(),
                    "After 2nd request should have (limit - 2)");
        }

        @Test
        @DisplayName("Should provide reset time in response")
        void testResetTimeInResponse() {
            RateLimitResponse response = strategy.getRateLimit(testKey);
            Instant resetTime = response.getResetTime();

            assertNotNull(resetTime, "Reset time should not be null");
            assertTrue(resetTime.isAfter(Instant.now()), "Reset time should be in the future");
        }

        @Test
        @DisplayName("Should reset rate limit counter")
        void testResetFunctionality() {
            // Exhaust the limit
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(testKey);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
            }

            // Verify we're at limit
            RateLimitResponse atLimit = strategy.getRateLimit(testKey);
            assertFalse(atLimit.isAllowed(), "Should be at limit");

            // Reset
            strategy.reset(testKey);

            // Should now allow requests again
            RateLimitResponse afterReset = strategy.getRateLimit(testKey);
            assertTrue(afterReset.isAllowed(), "Should allow after reset");
            assertEquals(LIMIT - 1, afterReset.getRemainingRequests(),
                    "Should have (limit - 1) remaining after reset");
        }

        @Test
        @DisplayName("Should use isAllowed() correctly")
        void testIsAllowedMethod() {
            for (int i = 0; i < LIMIT; i++) {
                assertTrue(strategy.isAllowed(testKey), "isAllowed should return true within limit");
            }

            assertFalse(strategy.isAllowed(testKey), "isAllowed should return false over limit");
        }
    }

    // ======================== SLIDING WINDOW STRATEGY TESTS ========================

    @Nested
    @DisplayName("SlidingWindowStrategy Tests")
    class SlidingWindowStrategyTests {

        private SlidingWindowStrategy strategy;
        private QuotaConfig config;

        @BeforeEach
        void setUp() {
            clearAllTestKeys();
            config = new QuotaConfig(QuotaConfig.Strategy.SLIDING_WINDOW, LIMIT, WINDOW);
            strategy = new SlidingWindowStrategy(redisTemplate, config);
        }

        @AfterEach
        void tearDown() {
            clearAllTestKeys();
        }

        @Test
        @DisplayName("Should allow requests within limit")
        void testRequestsWithinLimit() {
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(TEST_KEY);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
                assertTrue(response.getRemainingRequests() >= 0, "Remaining requests should be non-negative");
            }
        }

        @Test
        @DisplayName("Should deny requests over limit")
        void testRequestsOverLimit() {
            // Make LIMIT requests (should all be allowed)
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(TEST_KEY);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
            }

            // Next request should be denied
            RateLimitResponse deniedResponse = strategy.getRateLimit(TEST_KEY);
            assertFalse(deniedResponse.isAllowed(), "Request over limit should be denied");
            assertEquals(0, deniedResponse.getRemainingRequests(), "Remaining requests should be 0");
        }

        @Test
        @DisplayName("Should track timestamps in sliding window")
        void testSlidingWindowBehavior() {
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(TEST_KEY);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
            }

            // Next request should be denied (all in same window)
            RateLimitResponse response = strategy.getRateLimit(TEST_KEY);
            assertFalse(response.isAllowed(), "Should be denied when limit is reached");

            // The sliding window advantage: old requests should eventually expire
            // (Testing this would require waiting for the window to pass or mocking time)
        }

        @Test
        @DisplayName("Should show correct remaining requests")
        void testRemainingRequests() {
            // First call - we accept 1 request, leaving (limit - 1)
            RateLimitResponse initial = strategy.getRateLimit(TEST_KEY);
            assertTrue(initial.isAllowed(), "First request should be allowed");
            assertEquals(LIMIT - 1, initial.getRemainingRequests(),
                    "After 1st request should have (limit - 1)");
        }

        @Test
        @DisplayName("Should reset rate limit counter")
        void testResetFunctionality() {
            // Exhaust the limit
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(TEST_KEY);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
            }

            // Verify we're at limit
            RateLimitResponse limitReached = strategy.getRateLimit(TEST_KEY);
            assertFalse(limitReached.isAllowed(), "Should be at limit");

            // Reset
            strategy.reset(TEST_KEY);

            // Should now allow requests again
            RateLimitResponse afterReset = strategy.getRateLimit(TEST_KEY);
            assertTrue(afterReset.isAllowed(), "Should allow after reset");
        }

        @Test
        @DisplayName("Should use isAllowed() correctly")
        void testIsAllowedMethod() {
            for (int i = 0; i < LIMIT; i++) {
                assertTrue(strategy.isAllowed(TEST_KEY), "isAllowed should return true within limit");
            }

            assertFalse(strategy.isAllowed(TEST_KEY), "isAllowed should return false over limit");
        }
    }

    // ======================== TOKEN BUCKET STRATEGY TESTS ========================

    @Nested
    @DisplayName("TokenBucketStrategy Tests")
    class TokenBucketStrategyTests {

        private TokenBucketStrategy strategy;
        private QuotaConfig config;

        @BeforeEach
        void setUp() {
            clearAllTestKeys();
            config = new QuotaConfig(QuotaConfig.Strategy.TOKEN_BUCKET, LIMIT, WINDOW);
            strategy = new TokenBucketStrategy(redisTemplate, config);
        }

        @AfterEach
        void tearDown() {
            clearAllTestKeys();
        }

        @Test
        @DisplayName("Should allow requests within burst capacity")
        void testRequestsWithinLimit() {
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(TEST_KEY);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
                assertTrue(response.getRemainingRequests() >= 0, "Remaining requests should be non-negative");
            }
        }

        @Test
        @DisplayName("Should deny requests over burst capacity")
        void testRequestsOverLimit() {
            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = strategy.getRateLimit(TEST_KEY);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
            }

            // Next request should be denied
            RateLimitResponse deniedResponse = strategy.getRateLimit(TEST_KEY);
            assertFalse(deniedResponse.isAllowed(), "Request over burst capacity should be denied");
        }

        @Test
        @DisplayName("Should track remaining tokens")
        void testRemainingTokens() {
            // Token bucket starts full
            RateLimitResponse initial = strategy.getRateLimit(TEST_KEY);
            int initialRemaining = initial.getRemainingRequests();

            // After one more request
            RateLimitResponse after = strategy.getRateLimit(TEST_KEY);
            int afterRemaining = after.getRemainingRequests();

            assertTrue(afterRemaining < initialRemaining, "Remaining tokens should decrease after request");
        }

        @Test
        @DisplayName("Should show correct remaining tokens")
        void testCorrectRemainingCount() {
            // First request
            RateLimitResponse first = strategy.getRateLimit(TEST_KEY);
            assertTrue(first.isAllowed(), "First request should be allowed");
            int afterFirst = first.getRemainingRequests();

            // Second request
            RateLimitResponse second = strategy.getRateLimit(TEST_KEY);
            assertTrue(second.isAllowed(), "Second request should be allowed");
            int afterSecond = second.getRemainingRequests();

            assertEquals(afterFirst - 1, afterSecond, "Each request should consume exactly 1 token");
        }

        @Test
        @DisplayName("Should reset token bucket")
        void testResetFunctionality() {
            // Exhaust the limit
            for (int i = 0; i < LIMIT; i++) {
                strategy.getRateLimit(TEST_KEY);
            }

            // Verify we're at limit
            assertFalse(strategy.getRateLimit(TEST_KEY).isAllowed(), "Should be exhausted");

            // Reset
            strategy.reset(TEST_KEY);

            // Should now allow requests again
            assertTrue(strategy.getRateLimit(TEST_KEY).isAllowed(), "Should allow after reset");
        }

        @Test
        @DisplayName("Should use isAllowed() correctly")
        void testIsAllowedMethod() {
            for (int i = 0; i < LIMIT; i++) {
                assertTrue(strategy.isAllowed(TEST_KEY), "isAllowed should return true within burst");
            }

            assertFalse(strategy.isAllowed(TEST_KEY), "isAllowed should return false when exhausted");
        }
    }

    // ======================== METRICS RECORDING TESTS ========================

    @Nested
    @DisplayName("Metrics Recording Tests")
    class MetricsRecordingTests {

        private MeterRegistry testRegistry;
        private MetricsCollector testMetrics;

        @BeforeEach
        void setUp() {
            testRegistry = new SimpleMeterRegistry();
            testMetrics = new MetricsCollector(testRegistry);
        }

        @AfterEach
        void tearDown() {
            testRegistry = null;
            testMetrics = null;
        }

        @Test
        @DisplayName("Should record total requests metric")
        void testRecordTotalRequests() {
            testMetrics.recordRequest(true);
            testMetrics.recordRequest(false);

            Counter counter = testRegistry.find("ratelimit.requests.total").counter();
            assertNotNull(counter, "Total requests counter should exist");
            assertEquals(2.0, counter.count(), "Should have recorded 2 requests");
        }

        @Test
        @DisplayName("Should record allowed requests metric")
        void testRecordAllowedRequests() {
            testMetrics.recordRequest(true);
            testMetrics.recordRequest(true);

            Counter counter = testRegistry.find("ratelimit.requests.allowed").counter();
            assertNotNull(counter, "Allowed requests counter should exist");
            assertEquals(2.0, counter.count(), "Should have recorded 2 allowed requests");
        }

        @Test
        @DisplayName("Should record throttled requests metric")
        void testRecordThrottledRequests() {
            testMetrics.recordRequest(false);
            testMetrics.recordRequest(false);

            Counter counter = testRegistry.find("ratelimit.requests.throttled").counter();
            assertNotNull(counter, "Throttled requests counter should exist");
            assertEquals(2.0, counter.count(), "Should have recorded 2 throttled requests");
        }

        @Test
        @DisplayName("Should increment metrics correctly")
        void testIncrementMetrics() {
            testMetrics.recordRequest(true);

            Counter totalCounter = testRegistry.find("ratelimit.requests.total").counter();
            Counter allowedCounter = testRegistry.find("ratelimit.requests.allowed").counter();
            Counter throttledCounter = testRegistry.find("ratelimit.requests.throttled").counter();

            assertEquals(1.0, totalCounter.count(), "Total should be 1");
            assertEquals(1.0, allowedCounter.count(), "Allowed should be 1");
            assertEquals(0.0, throttledCounter.count(), "Throttled should be 0");

            testMetrics.recordRequest(false);

            assertEquals(2.0, totalCounter.count(), "Total should be 2");
            assertEquals(1.0, allowedCounter.count(), "Allowed should still be 1");
            assertEquals(1.0, throttledCounter.count(), "Throttled should now be 1");
        }

        @Test
        @DisplayName("Should record request latency")
        void testRecordLatency() {
            var sample = testMetrics.recordLatencyStart();

            // Simulate some work
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            testMetrics.recordLatencyStop(sample);

            var timer = testRegistry.find("ratelimit.request.latency").timer();
            assertNotNull(timer, "Latency timer should exist");
            assertTrue(timer.count() > 0, "Should have recorded at least one latency sample");
            assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 10,
                    "Latency should be at least 10ms");
        }
    }

    // ======================== INTEGRATION TESTS WITH SERVICE ========================

    @Nested
    @DisplayName("Integration Tests with RateLimitService")
    class ServiceIntegrationTests {

        private QuotaConfig fixedWindowConfig;
        private QuotaConfig slidingWindowConfig;
        private QuotaConfig tokenBucketConfig;

        @BeforeEach
        void setUp() {
            clearAllTestKeys();
            fixedWindowConfig = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, LIMIT, WINDOW);
            slidingWindowConfig = new QuotaConfig(QuotaConfig.Strategy.SLIDING_WINDOW, LIMIT, WINDOW);
            tokenBucketConfig = new QuotaConfig(QuotaConfig.Strategy.TOKEN_BUCKET, LIMIT, WINDOW);
        }

        @AfterEach
        void tearDown() {
            clearAllTestKeys();
        }

        @Test
        @DisplayName("Should enforce fixed window through service")
        void testServiceWithFixedWindow() {
            RateLimitRequest request = new RateLimitRequest(1, TEST_KEY, Instant.now());

            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = rateLimitService.checkRateLimit(request, fixedWindowConfig);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " within limit should be allowed");
            }

            RateLimitResponse overLimit = rateLimitService.checkRateLimit(request, fixedWindowConfig);
            assertFalse(overLimit.isAllowed(), "Request over limit should be denied");
        }

        @Test
        @DisplayName("Should enforce sliding window through service")
        void testServiceWithSlidingWindow() {
            String serviceTestKey = TEST_KEY + "-sliding-service";
            RateLimitRequest request = new RateLimitRequest(1, serviceTestKey, Instant.now());

            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = rateLimitService.checkRateLimit(request, slidingWindowConfig);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " within limit should be allowed");
            }

            RateLimitResponse overLimit = rateLimitService.checkRateLimit(request, slidingWindowConfig);
            assertFalse(overLimit.isAllowed(), "Request over limit should be denied");

            // Cleanup
            redisTemplate.delete("ratelimit:sliding:" + serviceTestKey);
        }

        @Test
        @DisplayName("Should enforce token bucket through service")
        void testServiceWithTokenBucket() {
            String serviceTestKey = TEST_KEY + "-token-service";
            RateLimitRequest request = new RateLimitRequest(1, serviceTestKey, Instant.now());

            for (int i = 0; i < LIMIT; i++) {
                RateLimitResponse response = rateLimitService.checkRateLimit(request, tokenBucketConfig);
                assertTrue(response.isAllowed(), "Request " + (i + 1) + " within burst should be allowed");
            }

            RateLimitResponse overBurst = rateLimitService.checkRateLimit(request, tokenBucketConfig);
            assertFalse(overBurst.isAllowed(), "Request over burst should be denied");

            // Cleanup
            redisTemplate.delete("ratelimit:token:" + serviceTestKey);
        }

        @Test
        @DisplayName("Should reset through service")
        void testResetThroughService() {
            RateLimitRequest request = new RateLimitRequest(1, TEST_KEY, Instant.now());

            // Exhaust limit
            for (int i = 0; i < LIMIT; i++) {
                rateLimitService.checkRateLimit(request, fixedWindowConfig);
            }

            assertFalse(rateLimitService.checkRateLimit(request, fixedWindowConfig).isAllowed(),
                    "Should be at limit");

            // Reset
            rateLimitService.resetRateLimit(TEST_KEY, fixedWindowConfig);

            assertTrue(rateLimitService.checkRateLimit(request, fixedWindowConfig).isAllowed(),
                    "Should allow after reset");
        }

        @Test
        @DisplayName("Should provide debug info")
        void testDebugInfo() {
            RateLimitRequest request = new RateLimitRequest(1, TEST_KEY, Instant.now());
            rateLimitService.checkRateLimit(request, fixedWindowConfig);

            var debugInfo = rateLimitService.getDebugInfo(TEST_KEY, fixedWindowConfig);

            assertNotNull(debugInfo, "Debug info should exist");
            assertTrue(debugInfo.containsKey("key"), "Debug info should have key");
            assertTrue(debugInfo.containsKey("strategy"), "Debug info should have strategy");
            assertTrue(debugInfo.containsKey("limit"), "Debug info should have limit");
            assertTrue(debugInfo.containsKey("allowed"), "Debug info should have allowed status");
            assertTrue(debugInfo.containsKey("remainingRequests"), "Debug info should have remaining requests");
            assertTrue(debugInfo.containsKey("resetTime"), "Debug info should have reset time");
        }
    }
}

