package com.demo.ratelimit.integration;

import com.demo.ratelimit.metrics.MetricsCollector;
import com.demo.ratelimit.service.RateLimitService;
import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitRequest;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import com.demo.ratelimit.strategy.RateLimitStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

@SpringBootTest
public class RateLimitIntegrationTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private MetricsCollector metricsCollector;

    @BeforeEach
    void setUp() {
        // Clear redis test keys before each test if needed
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void testLuaScriptExecution_NormalBehavior() {
        String key = "test-user-lua-" + UUID.randomUUID();
        QuotaConfig config = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, 5, Duration.ofSeconds(60));
        RateLimitRequest request = new RateLimitRequest(1, key, Instant.now());

        // First 5 requests should be allowed
        for (int i = 0; i < 5; i++) {
            RateLimitResponse response = rateLimitService.checkRateLimit(request, config);
            assertTrue(response.isAllowed(), "Request " + (i + 1) + " should be allowed");
        }

        // 6th request should be throttled
        RateLimitResponse throttledResponse = rateLimitService.checkRateLimit(request, config);
        assertFalse(throttledResponse.isAllowed(), "Request 6 should be throttled");
    }

    @Test
    void testDistributedConcurrency_NoRaceConditions() throws InterruptedException {
        String key = "test-concurrent-" + UUID.randomUUID();
        // Limit is 10
        QuotaConfig config = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, 10, Duration.ofSeconds(60));
        RateLimitRequest request = new RateLimitRequest(2, key, Instant.now());

        int numThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger throttledCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // wait until all threads are ready
                    RateLimitResponse response = rateLimitService.checkRateLimit(request, config);
                    if (response.isAllowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        throttledCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads at once
        latch.countDown();
        doneLatch.await(); // wait for all threads to finish
        executor.shutdown();

        // Exactly 10 requests should be allowed, no more, no less.
        assertEquals(10, allowedCount.get(), "Exactly 10 requests should be allowed due to Lua atomicity");
        assertEquals(40, throttledCount.get(), "Exactly 40 requests should be throttled");
    }

    @Test
    void testFallbackWhenRedisIsDown() {
        // Setup a fake RedisTemplate pointing to a non-existent Redis server
        LettuceConnectionFactory badConnectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 9999)
        );
        badConnectionFactory.afterPropertiesSet();
        
        RedisTemplate<String, String> badRedisTemplate = new RedisTemplate<>();
        badRedisTemplate.setConnectionFactory(badConnectionFactory);
        badRedisTemplate.setKeySerializer(new StringRedisSerializer());
        badRedisTemplate.setValueSerializer(new StringRedisSerializer());
        badRedisTemplate.afterPropertiesSet();

        RateLimitStrategyFactory badFactory = new RateLimitStrategyFactory(badRedisTemplate);
        RateLimitService fallbackService = new RateLimitService(badFactory, metricsCollector);

        String key = "test-fallback-" + UUID.randomUUID();
        QuotaConfig config = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, 5, Duration.ofSeconds(60));
        RateLimitRequest request = new RateLimitRequest(3, key, Instant.now());

        // Even though Redis is down, it should catch the exception and allow the request (fail-open)
        RateLimitResponse response = fallbackService.checkRateLimit(request, config);
        
        assertTrue(response.isAllowed(), "Fallback should allow request when Redis is down");
        
        // Clean up connection factory
        badConnectionFactory.destroy();
    }
}
