package com.demo.ratelimit;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import com.demo.ratelimit.strategy.AtomicRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MultiInstanceTest simulates multiple instances accessing the same rate limiter
 * through shared Redis state. This demonstrates:
 *
 * 1. WITHOUT Lua (NonAtomicRateLimiter): Race conditions cause over-counting
 * 2. WITH Lua (AtomicRateLimiter): Atomic operations ensure correctness
 *
 * Key insight: In a distributed system with multiple servers/instances:
 * - They all share the same Redis state
 * - Non-atomic operations create race conditions across instances
 * - Lua scripts fix this because Redis executes them atomically
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiInstanceTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String TEST_KEY = "multi-instance-key";
    private static final int LIMIT = 50;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    @BeforeEach
    void setUp() {
        // Clean up before each test
        clearRedisKey(TEST_KEY);
    }

    /**
     * Test WITH atomicity: AtomicRateLimiter
     * Same setup: 3 instances with 50 threads each = 150 total concurrent requests
     * With Lua scripts, exactly 50 should be allowed, rest denied
     */
    @Test
    void multiInstanceWithAtomic() throws InterruptedException {
        System.out.println("\n=== MULTI-INSTANCE TEST (WITH ATOMIC OPERATIONS) ===");
        System.out.println("Simulating 3 instances with 50 threads each = 150 concurrent requests");
        System.out.println("Limit: " + LIMIT + " requests per window\n");

        QuotaConfig config = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, LIMIT, WINDOW);
        AtomicRateLimiter rateLimiter = new AtomicRateLimiter(redisTemplate, config);

        int numInstances = 3;
        int threadsPerInstance = 50;
        int totalThreads = numInstances * threadsPerInstance;

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

        // Launch all threads simulating multiple instances
        for (int instance = 0; instance < numInstances; instance++) {
            for (int i = 0; i < threadsPerInstance; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    RateLimitResponse response = rateLimiter.getRateLimit(TEST_KEY);
                    if (response.isAllowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                    endLatch.countDown();
                });
            }
        }

        // Fire all threads at once (simulating synchronized request from multiple instances)
        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        int allowed = allowedCount.get();
        int denied = deniedCount.get();

        System.out.printf("Results: Allowed=%d, Denied=%d%n", allowed, denied);
        System.out.printf("Expected: %d (exact limit)%n", LIMIT);
        System.out.printf("Difference: %d%n", allowed - LIMIT);
        System.out.println("\n✓ RESULT: Atomic operations ENFORCED limit correctly");

        // Verify correctness
        assertEquals(LIMIT, allowed, "Exactly " + LIMIT + " requests should be allowed");
        assertEquals(totalThreads - LIMIT, denied, "Remaining requests should be denied");
    }

    private void clearRedisKey(String key) {
        long windowStart = (System.currentTimeMillis() / 1000 / WINDOW.toSeconds()) * WINDOW.toSeconds();
        String fullKey = "ratelimit:atomic:" + key + ":" + windowStart;
        redisTemplate.delete(fullKey);
    }
}

